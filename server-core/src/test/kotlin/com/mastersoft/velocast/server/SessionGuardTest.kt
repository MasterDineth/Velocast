package com.mastersoft.velocast.server

import com.mastersoft.velocast.protocol.ClientHandshake
import com.mastersoft.velocast.protocol.ControlWriter
import com.mastersoft.velocast.protocol.DeviceInfo
import com.mastersoft.velocast.protocol.ErrorCode
import com.mastersoft.velocast.protocol.FrameHeader
import com.mastersoft.velocast.protocol.FrameReader
import com.mastersoft.velocast.protocol.Hello
import com.mastersoft.velocast.protocol.MessageType
import com.mastersoft.velocast.protocol.Phase
import com.mastersoft.velocast.protocol.Pong
import com.mastersoft.velocast.protocol.TouchAction
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionGuardTest {
    private fun newGuardFixture(): GuardFixture {
        val clock = FakeServerClock(10_000L)
        val logger =
            Logger(minLevel = LogLevel.DEBUG, stderr = PrintStream(ByteArrayOutputStream()))
        val acceptor = FakeServerAcceptor()
        val env = FakeSessionEnvironment(clock, logger)
        val timer = FakeHandshakeTimer()
        val auth = HmacSha256Authenticator(TestClientDriver.DEFAULT_SECRET)
        val backoff = PeerBackoffTracker(clock)
        val guard = SessionGuard(
            acceptor = acceptor,
            authenticator = auth,
            environment = env,
            clock = clock,
            logger = logger,
            handshakeTimer = timer,
            handshakeTimeoutMs = 3_000L,
            sessionIdleTimeoutMs = 5_000L,
            backoffTracker = backoff
        )
        val acceptThread = Thread({ guard.runAcceptLoop() }, "test-guard-accept").apply {
            isDaemon = true
            start()
        }
        return GuardFixture(clock, logger, acceptor, env, timer, backoff, guard, acceptThread)
    }

    private data class GuardFixture(
        val clock: FakeServerClock,
        val logger: Logger,
        val acceptor: FakeServerAcceptor,
        val env: FakeSessionEnvironment,
        val timer: FakeHandshakeTimer,
        val backoff: PeerBackoffTracker,
        val guard: SessionGuard,
        val acceptThread: Thread
    ) : AutoCloseable {
        override fun close() {
            guard.close()
            acceptThread.join(2_000L)
            logger.close()
        }
    }

    @Test
    fun rejectsUnauthenticatedTrafficAndWrongSecretWithoutTouchOrMediaSetup() {
        newGuardFixture().use { f ->
            // 1. Wrong secret
            val badSecretPeer = FakePeerSocket(TestClientDriver.ip(10))
            f.acceptor.enqueue(badSecretPeer)
            val cwBad = ControlWriter(badSecretPeer.clientOut)
            val frBad = FrameReader(badSecretPeer.clientIn)
            val badClient = ClientHandshake(
                HmacSha256Authenticator(ByteArray(32) { 0x55 }),
                0,
                TestClientDriver.CLIENT_NONCE
            )
            cwBad.writePayload(badClient.hello())
            badSecretPeer.clientOut.flush()
            val sh = FrameHeader()
            assertTrue(frBad.readHeader(sh))
            frBad.readPayload(sh)
            cwBad.writePayload(badClient.onServerHello(frBad.payload, 0, sh.length))
            badSecretPeer.clientOut.flush()
            assertEquals(ErrorCode.AUTH_FAILED, TestClientDriver.expectFatalError(frBad))
            waitUntil { badSecretPeer.isClosed }
            assertEquals(0, f.env.deviceInfoQueries.get())
            assertEquals(0, f.env.pipelineCreations.get())

            // 2. Session message sent before authentication (advance past 1s backoff)
            f.clock.advanceBy(2_000L)
            val rawTouchPeer = FakePeerSocket(TestClientDriver.ip(10))
            f.acceptor.enqueue(rawTouchPeer)
            val cwRaw = ControlWriter(rawTouchPeer.clientOut).also { it.phase = Phase.SESSION }
            cwRaw.touch(0, TouchAction.DOWN, 0.5f, 0.5f)
            rawTouchPeer.clientOut.flush()
            val frRaw = FrameReader(rawTouchPeer.clientIn)
            assertEquals(ErrorCode.UNEXPECTED_MESSAGE, TestClientDriver.expectFatalError(frRaw))
            waitUntil { rawTouchPeer.isClosed }
            assertEquals(0, f.env.injectorCreations.get())
            assertEquals(0, f.env.pipelineCreations.get())
        }
    }

    @Test
    fun enforcesThreeSecondHandshakeTimeoutAndSlowlorisBudget() {
        newGuardFixture().use { f ->
            // Initial silent peer triggers 3s handshake timer
            val silentPeer = FakePeerSocket(TestClientDriver.ip(20))
            f.acceptor.enqueue(silentPeer)
            waitUntil { f.timer.entries.isNotEmpty() }
            assertEquals(3_000L, f.timer.entries[0].delayMs)
            f.timer.fireLatest()
            waitUntil {
                silentPeer.isClosed && f.backoff.failureCount(TestClientDriver.ip(20)) == 1
            }
            assertEquals(1, f.backoff.failureCount(TestClientDriver.ip(20)))

            // Mid-handshake hang after HELLO exceeds 3s fake clock budget
            f.clock.advanceBy(2_000L)
            val slowPeer = FakePeerSocket(TestClientDriver.ip(21))
            f.acceptor.enqueue(slowPeer)
            val cw = ControlWriter(slowPeer.clientOut)
            val fr = FrameReader(slowPeer.clientIn)
            cw.writePayload(Hello(2, 0, TestClientDriver.CLIENT_NONCE))
            slowPeer.clientOut.flush()
            val sh = FrameHeader()
            assertTrue(fr.readHeader(sh))
            fr.readPayload(sh)
            // Advance clock past 3s budget, then let timer fire
            f.clock.advanceBy(3_500L)
            f.timer.fireLatest()
            waitUntil { slowPeer.isClosed && f.backoff.failureCount(TestClientDriver.ip(21)) == 1 }
            assertEquals(1, f.backoff.failureCount(TestClientDriver.ip(21)))
        }
    }

    @Test
    fun exponentialBackoffIsPerIpAndResetsOnSuccessfulAuth() {
        newGuardFixture().use { f ->
            val badIp = TestClientDriver.ip(30)
            val otherIp = TestClientDriver.ip(31)

            // 1st failure -> 1s backoff
            val p1 = FakePeerSocket(badIp)
            f.acceptor.enqueue(p1)
            ControlWriter(p1.clientOut).apply {
                phase = Phase.SESSION
                ping(1)
            }
            p1.clientOut.flush()
            assertEquals(
                ErrorCode.UNEXPECTED_MESSAGE,
                TestClientDriver.expectFatalError(FrameReader(p1.clientIn))
            )
            waitUntil { f.backoff.failureCount(badIp) == 1 }
            assertEquals(1_000L, f.backoff.backoffRemainingMs(badIp))

            // Immediate retry during backoff is dropped before any byte is read
            val dropped = FakePeerSocket(badIp)
            f.acceptor.enqueue(dropped)
            waitUntil { dropped.isClosed }
            assertEquals(0L, dropped.serverBytesRead)

            // Different IP is unaffected
            val goodOther = FakePeerSocket(otherIp)
            f.acceptor.enqueue(goodOther)
            val (cwOther, _) = TestClientDriver.authenticate(goodOther)
            cwOther.bye()
            goodOther.clientOut.flush()
            waitUntil { f.guard.currentActiveSession == null }

            // 2nd failure on badIp after 1s -> 2s backoff; 3rd after 2s -> 4s backoff
            f.clock.advanceBy(1_000L)
            val p2 = FakePeerSocket(badIp)
            f.acceptor.enqueue(p2)
            ControlWriter(p2.clientOut).apply {
                phase = Phase.SESSION
                ping(2)
            }
            p2.clientOut.flush()
            waitUntil { f.backoff.failureCount(badIp) == 2 }
            assertEquals(2_000L, f.backoff.backoffRemainingMs(badIp))

            f.clock.advanceBy(2_000L)
            val p3 = FakePeerSocket(badIp)
            f.acceptor.enqueue(p3)
            ControlWriter(p3.clientOut).apply {
                phase = Phase.SESSION
                ping(3)
            }
            p3.clientOut.flush()
            waitUntil { f.backoff.failureCount(badIp) == 3 }
            assertEquals(4_000L, f.backoff.backoffRemainingMs(badIp))

            // After 4s, successful authentication resets backoff to 0
            f.clock.advanceBy(4_000L)
            val pSuccess = FakePeerSocket(badIp)
            f.acceptor.enqueue(pSuccess)
            val (cwOk, _) = TestClientDriver.authenticate(pSuccess)
            assertEquals(0, f.backoff.failureCount(badIp))
            assertEquals(0L, f.backoff.backoffRemainingMs(badIp))
            cwOk.bye()
            pSuccess.clientOut.flush()
        }
    }

    @Test
    fun onlyAuthenticatedPeerMayReplaceActiveSessionWithSinglePipelineOwnership() {
        newGuardFixture().use { f ->
            val legitimate = FakePeerSocket(TestClientDriver.ip(40))
            f.acceptor.enqueue(legitimate)
            val (cwLegit, frLegit) = TestClientDriver.authenticate(legitimate)
            waitUntil { f.env.createdPipelines.size == 1 }
            val firstPipeline = f.env.createdPipelines[0]
            assertEquals(1, f.env.maxConcurrentPipelines.get())

            // Legitimate client puts pointer 0 down
            cwLegit.touch(0, TouchAction.DOWN, 0.5f, 0.5f)
            legitimate.clientOut.flush()
            waitUntil { f.env.sink.touchCalls.size == 1 }

            // Unauthenticated attacker fails auth -> legitimate session stays unaffected!
            val attacker = FakePeerSocket(TestClientDriver.ip(41))
            f.acceptor.enqueue(attacker)
            val cwAttacker = ControlWriter(attacker.clientOut)
            val frAttacker = FrameReader(attacker.clientIn)
            val wrongAuth = ClientHandshake(
                HmacSha256Authenticator(ByteArray(32) { 0x99.toByte() }),
                0,
                TestClientDriver.CLIENT_NONCE
            )
            cwAttacker.writePayload(wrongAuth.hello())
            attacker.clientOut.flush()
            val sh = FrameHeader()
            assertTrue(frAttacker.readHeader(sh))
            frAttacker.readPayload(sh)
            cwAttacker.writePayload(wrongAuth.onServerHello(frAttacker.payload, 0, sh.length))
            attacker.clientOut.flush()
            assertEquals(ErrorCode.AUTH_FAILED, TestClientDriver.expectFatalError(frAttacker))
            waitUntil { attacker.isClosed }

            assertFalse(legitimate.isClosed)
            assertFalse(firstPipeline.closed)
            cwLegit.ping(77)
            legitimate.clientOut.flush()
            val pongHdr = FrameHeader()
            assertTrue(frLegit.readHeader(pongHdr))
            assertEquals(MessageType.PONG, pongHdr.type)
            frLegit.readPayload(pongHdr)
            assertEquals(77, Pong.decode(frLegit.payload, 0, pongHdr.length).token)

            // Authenticated newcomer replaces active session cleanly
            val replacement = FakePeerSocket(TestClientDriver.ip(42))
            f.acceptor.enqueue(replacement)
            val (cwNew, _) = TestClientDriver.authenticate(replacement)
            waitUntil { firstPipeline.closed && legitimate.isClosed }
            waitUntil { f.env.createdPipelines.size == 2 }
            // Displaced session cancelled its active pointer on teardown
            assertEquals(
                android.view.MotionEvent.ACTION_CANCEL,
                f.env.sink.touchCalls.last().action
            )
            // Never more than one live pipeline at a time
            assertEquals(1, f.env.maxConcurrentPipelines.get())
            assertEquals(DeviceInfo::class.java, f.env.sampleDeviceInfo::class.java)

            cwNew.bye()
            replacement.clientOut.flush()
        }
    }

    @Test
    fun concurrentAuthenticatedPreemptionAllowsOnlyOneWinnerAndSinglePipeline() {
        newGuardFixture().use { f ->
            val s1Peer = FakePeerSocket(TestClientDriver.ip(50))
            f.acceptor.enqueue(s1Peer)
            TestClientDriver.authenticate(s1Peer)
            waitUntil { f.env.createdPipelines.size == 1 }

            val s1ClosingStarted = CountDownLatch(1)
            val allowS1Finish = CountDownLatch(1)
            f.env.createdPipelines[0].onBeforeClose = {
                s1ClosingStarted.countDown()
                allowS1Finish.await(5, TimeUnit.SECONDS)
            }

            val s2Peer = FakePeerSocket(TestClientDriver.ip(51))
            val s3Peer = FakePeerSocket(TestClientDriver.ip(52))
            f.acceptor.enqueue(s2Peer)
            f.acceptor.enqueue(s3Peer)

            val (s2Cw, s2Fr) = sendHelloAndClientProof(s2Peer)
            assertTrue(s1ClosingStarted.await(5, TimeUnit.SECONDS))
            val (s3Cw, s3Fr) = sendHelloAndClientProof(s3Peer)

            waitUntil { f.timer.entries.size >= 3 && f.timer.entries.take(3).all { it.cancelled } }
            allowS1Finish.countDown()

            waitUntil {
                val activePeer = f.guard.currentActiveSession?.peerSocket
                s1Peer.isClosed && (
                    (!s2Peer.isClosed && s3Peer.isClosed && activePeer === s2Peer) ||
                        (s2Peer.isClosed && !s3Peer.isClosed && activePeer === s3Peer)
                    )
            }
            assertEquals(1, f.env.maxConcurrentPipelines.get())

            val winnerIsS2 = !s2Peer.isClosed
            val winnerPeer = if (winnerIsS2) s2Peer else s3Peer
            val winnerCw = if (winnerIsS2) s2Cw else s3Cw
            val winnerFr = if (winnerIsS2) s2Fr else s3Fr
            assertSame(winnerPeer, f.guard.currentActiveSession?.peerSocket)

            verifyWinnerSessionResponsive(winnerCw, winnerFr, winnerPeer)
        }
    }

    private fun sendHelloAndClientProof(socket: FakePeerSocket): Pair<ControlWriter, FrameReader> {
        val cw = ControlWriter(socket.clientOut)
        val fr = FrameReader(socket.clientIn)
        val client = ClientHandshake(
            HmacSha256Authenticator(TestClientDriver.DEFAULT_SECRET),
            0,
            TestClientDriver.CLIENT_NONCE
        )
        cw.writePayload(client.hello())
        socket.clientOut.flush()

        val sh = FrameHeader()
        assertTrue(fr.readHeader(sh))
        fr.readPayload(sh)
        assertEquals(MessageType.SERVER_HELLO, sh.type)

        cw.writePayload(client.onServerHello(fr.payload, 0, sh.length))
        socket.clientOut.flush()
        return Pair(cw, fr)
    }

    private fun verifyWinnerSessionResponsive(
        cw: ControlWriter,
        fr: FrameReader,
        peer: FakePeerSocket
    ) {
        val h = FrameHeader()
        assertTrue(fr.readHeader(h))
        assertEquals(MessageType.AUTH_PROOF, h.type)
        fr.readPayload(h)

        cw.phase = Phase.SESSION
        fr.phase = Phase.SESSION

        assertTrue(fr.readHeader(h))
        assertEquals(MessageType.DEVICE_INFO, h.type)
        fr.readPayload(h)
        TestClientDriver.drainInitialStatuses(fr, h)

        cw.ping(123)
        peer.clientOut.flush()
        assertTrue(fr.readHeader(h))
        assertEquals(MessageType.PONG, h.type)
        fr.readPayload(h)
        assertEquals(123, Pong.decode(fr.payload, 0, h.length).token)

        cw.bye()
        peer.clientOut.flush()
    }

    private fun waitUntil(timeoutMs: Long = 3_000L, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!predicate()) {
            if (System.currentTimeMillis() >= deadline) {
                throw AssertionError("Condition not satisfied within ${timeoutMs}ms")
            }
            Thread.sleep(10L)
        }
    }
}
