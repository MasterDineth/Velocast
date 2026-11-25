package com.mastersoft.velocast.protocol

import com.mastersoft.velocast.protocol.testing.HmacTestAuthenticator
import java.io.ByteArrayInputStream
import kotlin.math.floor
import kotlin.random.Random
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Property-based fuzzing: one million random and mutated inputs go through every reader, decoder
 * and handshake step; the only permitted outcomes are "accepted" and [ProtocolException]. Anything
 * else (another exception, a hang, runaway allocation) fails the test and prints the input.
 * Everything a reader accepts is also re-checked against rules written independently here.
 */
class FuzzTest {
    private companion object {
        const val INPUTS = 1_000_000
        const val SEED = 20_261_125L
        const val TARGETS = 8
    }

    private val auth = HmacTestAuthenticator(ByteArray(32) { it.toByte() })
    private val geometry = DisplayGeometry(1080, 2400, 0)
    private val device = DeviceInfo(35, LaunchMode.SHELL, geometry, "Acme", "X1")

    @Test(timeout = 600_000)
    fun oneMillionInputsOnlyEverRaiseProtocolException() {
        val rnd = Random(SEED)
        val accepted = LongArray(TARGETS)
        val rejected = LongArray(TARGETS)
        repeat(INPUTS) { i ->
            val target = i % TARGETS
            val input = generate(rnd, target)
            val ok = try {
                run(rnd, target, input)
                true
            } catch (_: ProtocolException) {
                false
            } catch (t: Throwable) {
                val shown = VectorSupport.toHex(input.copyOf(minOf(input.size, 400)))
                throw AssertionError("target $target, input $shown: $t", t)
            }
            if (ok) accepted[target]++ else rejected[target]++
        }
        for (t in 0 until TARGETS) {
            assertTrue("target $t never accepted an input", accepted[t] > 0)
            assertTrue("target $t never rejected an input", rejected[t] > 0)
        }
    }

    private fun generate(rnd: Random, target: Int): ByteArray = when (target) {
        0 -> raw(rnd)
        1, 4 -> Gen.mutate(rnd, Gen.controlBytes(rnd))
        2 -> raw(rnd)
        3, 5 -> Gen.mutate(rnd, Gen.frameBytes(rnd))
        6 -> Gen.mutate(rnd, payloadSeed(rnd))
        else -> Gen.mutate(rnd, handshakeSeed(rnd))
    }

    private fun payloadSeed(rnd: Random): ByteArray {
        val payload = if (rnd.nextBoolean()) Gen.clientPayload(rnd) else Gen.serverPayload(rnd)
        return payload.encode()
    }

    private fun handshakeSeed(rnd: Random): ByteArray = if (rnd.nextBoolean()) {
        Hello(ProtocolV2.VERSION, rnd.nextInt(), rnd.nextBytes(ProtocolV2.NONCE_SIZE)).encode()
    } else {
        val nonce = rnd.nextBytes(ProtocolV2.NONCE_SIZE)
        ServerHello(ProtocolV2.VERSION, rnd.nextInt(), nonce, device).encode()
    }

    private fun raw(rnd: Random): ByteArray {
        val bytes = rnd.nextBytes(rnd.nextInt(0, 80))
        // Half of the time start with a plausible type byte to get past the first check.
        if (bytes.isNotEmpty() && rnd.nextBoolean()) {
            bytes[0] = MessageType.entries.random(rnd).id.toByte()
        }
        return bytes
    }

    private fun run(rnd: Random, target: Int, input: ByteArray) {
        when (target) {
            0, 1 -> controlStream(input, Phase.SESSION)
            4 -> controlStream(input, Phase.HANDSHAKE)
            2, 3 -> frameStream(input, Phase.SESSION)
            5 -> frameStream(input, Phase.HANDSHAKE)
            6 -> payloadDecoders(rnd, input)
            else -> handshake(rnd, input)
        }
    }

    private fun controlStream(input: ByteArray, phase: Phase) {
        val reader = ControlReader(ByteArrayInputStream(input)).also { it.phase = phase }
        val p = ControlPacket()
        while (reader.read(p)) ControlOracle.check(p, reader.payload, phase)
    }

    private fun frameStream(input: ByteArray, phase: Phase) {
        val reader = FrameReader(ByteArrayInputStream(input)).also { it.phase = phase }
        val h = FrameHeader()
        while (reader.readHeader(h)) {
            reader.readPayload(h)
            FrameOracle.check(h, phase)
        }
    }

    private fun payloadDecoders(rnd: Random, input: ByteArray) {
        val prefix = rnd.nextInt(0, 5)
        val buf = ByteArray(prefix) + input
        val decoders = listOf<(ByteArray, Int, Int) -> Any>(
            Hello::decode,
            ServerHello::decode,
            AuthProof::decode,
            VideoSize::decode,
            CodecConfig::decode,
            Status::decode,
            ErrorMessage::decode,
            Pong::decode,
            Bye::decode,
            ClipboardMessage::decode,
            TextInput::decode
        )
        var anyAccepted = false
        var firstError: ProtocolException? = null
        for (d in decoders) {
            try {
                d(buf, prefix, input.size)
                anyAccepted = true
            } catch (e: ProtocolException) {
                firstError = firstError ?: e
            }
        }
        if (!anyAccepted) throw firstError!!
    }

    private fun handshake(rnd: Random, input: ByteArray) {
        var accepted = false
        var firstError: ProtocolException? = null
        try {
            val server = ServerHandshake(auth, 0x0F, ByteArray(16) { 1 }, device)
            server.onHello(input, 0, input.size)
            accepted = true
            val proof = rnd.nextBytes(ProtocolV2.PROOF_SIZE)
            try {
                server.onClientProof(proof, 0, proof.size)
                throw AssertionError("random proof accepted")
            } catch (_: ProtocolException) {
                // expected
            }
        } catch (e: ProtocolException) {
            firstError = e
        }
        try {
            val client = ClientHandshake(auth, 3, ByteArray(16))
            client.hello()
            client.onServerHello(input, 0, input.size)
            accepted = true
        } catch (e: ProtocolException) {
            firstError = firstError ?: e
        }
        if (!accepted) throw firstError!!
    }
}

/** Independent re-statement of client-to-server rules for fuzz verification. */
private object ControlOracle {
    private val handshakeTypes = setOf(MessageType.HELLO, MessageType.AUTH_PROOF)
    private val forbiddenKeys = setOf(0, 5, 6, 27, 79, 82)

    fun check(p: ControlPacket, payload: ByteArray, phase: Phase) {
        checkPhaseAndSeq(p, phase)
        when (p.type) {
            MessageType.TOUCH -> checkTouch(p)

            MessageType.KEY -> checkKey(p)

            MessageType.CONFIG_STREAM -> checkConfig(p)

            MessageType.ACTION,
            MessageType.PING,
            MessageType.BYE,
            MessageType.KEYFRAME_REQ,
            MessageType.PAUSE,
            MessageType.RESUME -> checkSimplePacket(p)

            MessageType.HELLO,
            MessageType.AUTH_PROOF,
            MessageType.TEXT,
            MessageType.CLIPBOARD -> checkPayloadPacket(p, payload)

            else -> violate("accepted server-only type ${p.type}")
        }
    }

    private fun checkPhaseAndSeq(p: ControlPacket, phase: Phase) {
        val isHandshake = p.type in handshakeTypes
        if (phase == Phase.HANDSHAKE && !isHandshake && p.type != MessageType.BYE) {
            violate("session message ${p.type} in handshake")
        }
        if (phase == Phase.SESSION && isHandshake) {
            violate("handshake message ${p.type} in session")
        }
        if (p.seq !in 0..0xFFFF) violate("seq")
    }

    private fun checkTouch(p: ControlPacket) {
        if (p.pointer !in 0..31 || p.param !in 0..3) violate("touch ids")
        val validCoords = p.x in 0f..1f && p.y in 0f..1f
        if (!validCoords) violate("touch coordinates ${p.x} ${p.y}")
    }

    private fun checkKey(p: ControlPacket) {
        if (p.pointer !in 0..1 || !hasZeroCoordinates(p)) violate("key fields")
        if (p.param in forbiddenKeys || p.param !in 0..255) {
            violate("forbidden key ${p.param}")
        }
    }

    private fun checkConfig(p: ControlPacket) {
        if (p.pointer != 0) violate("config pointer")
        if (p.param !in 1_000_000..40_000_000) violate("bitrate")
        val validFps = p.x in 15f..120f && p.x == floor(p.x.toDouble()).toFloat()
        if (!validFps) violate("fps ${p.x}")
        if (p.y !in 0.5f..1f) violate("scale ${p.y}")
    }

    private fun checkSimplePacket(p: ControlPacket) {
        if (p.pointer != 0 || !hasZeroCoordinates(p)) violate("unused fields on ${p.type}")
        val validParam = when (p.type) {
            MessageType.ACTION -> p.param in 1..9
            MessageType.PING -> true
            MessageType.BYE -> p.param == 0 || ErrorCode.fromId(p.param) != null
            else -> p.param == 0
        }
        if (!validParam) violate("invalid param ${p.param} on ${p.type}")
    }

    private fun checkPayloadPacket(p: ControlPacket, payload: ByteArray) {
        if (p.pointer != 0 || !hasZeroCoordinates(p)) violate("unused fields on ${p.type}")
        when (p.type) {
            MessageType.HELLO -> if (p.payloadLength != 26) violate("hello length")

            MessageType.AUTH_PROOF -> if (p.payloadLength != 32) violate("auth length")

            MessageType.TEXT -> {
                if (p.payloadLength !in 1..4096) violate("text size")
                checkUtf8(payload, 0, p.payloadLength)
            }

            MessageType.CLIPBOARD -> {
                if (p.payloadLength !in 1..262_144) violate("clipboard size")
                if (payload[0].toInt() !in 0..1) violate("clipboard flags")
                checkUtf8(payload, 1, p.payloadLength - 1)
            }

            else -> violate("unexpected payload type ${p.type}")
        }
    }

    private fun hasZeroCoordinates(p: ControlPacket): Boolean =
        p.x.toRawBits() == 0 && p.y.toRawBits() == 0

    private fun checkUtf8(bytes: ByteArray, offset: Int, length: Int) {
        val text = String(bytes, offset, length, Charsets.UTF_8)
        val original = bytes.copyOfRange(offset, offset + length)
        if (!text.toByteArray(Charsets.UTF_8).contentEquals(original)) {
            violate("accepted text is not canonical UTF-8")
        }
        if (text.indexOf('\u0000') >= 0) violate("accepted NUL")
    }
}

/** Independent re-statement of server-to-client frame rules for fuzz verification. */
private object FrameOracle {
    private val handshakeTypes = setOf(MessageType.SERVER_HELLO, MessageType.AUTH_PROOF)
    private val anyPhaseTypes = setOf(MessageType.BYE, MessageType.ERROR)

    fun check(h: FrameHeader, phase: Phase) {
        checkPhaseAndLength(h, phase)
        checkFlags(h)
        checkTimestamps(h)
    }

    private fun checkPhaseAndLength(h: FrameHeader, phase: Phase) {
        val isHandshake = h.type in handshakeTypes
        if (phase == Phase.HANDSHAKE && !isHandshake && h.type !in anyPhaseTypes) {
            violate("session frame ${h.type} in handshake")
        }
        if (phase == Phase.SESSION && isHandshake) {
            violate("handshake frame ${h.type} in session")
        }
        if (phase == Phase.HANDSHAKE && h.length > 256) violate("pre-auth payload ${h.length}")
        if (h.length !in 1..ProtocolV2.MAX_PAYLOAD) violate("length ${h.length}")
        if (h.type == MessageType.PONG && h.length != 12) violate("pong length")
    }

    private fun checkFlags(h: FrameHeader) {
        if ((h.flags and 0xF0) != 0) violate("reserved flags")
        if (((h.flags and FrameFlags.CONFIG) != 0) != (h.type == MessageType.CONFIG)) {
            violate("config flag")
        }
        if (((h.flags and FrameFlags.ERROR) != 0) != (h.type == MessageType.ERROR)) {
            violate("error flag")
        }
        if ((h.flags and FrameFlags.KEYFRAME) != 0 && h.type != MessageType.VIDEO) {
            violate("keyframe flag")
        }
    }

    private fun checkTimestamps(h: FrameHeader) {
        if (h.type == MessageType.VIDEO) {
            if (h.ptsUs < 0 || h.captureUs < 0) violate("negative timestamps")
            if (h.isReset && !h.isKeyframe) violate("reset without keyframe")
        } else if (h.ptsUs != 0L || h.captureUs != 0L) {
            violate("timestamps on ${h.type}")
        }
    }
}

private fun violate(message: String): Nothing = throw AssertionError(message)
