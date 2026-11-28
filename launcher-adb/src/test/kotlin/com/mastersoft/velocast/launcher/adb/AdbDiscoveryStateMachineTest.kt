package com.mastersoft.velocast.launcher.adb

import java.io.Closeable
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AdbDiscoveryStateMachineTest {
    private val activeLanProbe = NetworkEnvironmentProbe {
        LocalNetworkSnapshot(
            isLanAvailable = true,
            ipv4Subnets = listOf(
                Ipv4Subnet.fromAddress(byteArrayOf(192.toByte(), 168.toByte(), 1, 2), 24)
            )
        )
    }

    @Test
    fun `transitions to Found and updates port when wireless debugging toggles`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val backend = FakeNsdBackend()
        val discovery = AdbMdnsDiscovery(
            backend = backend,
            errorMapper = AdbErrorMapper(activeLanProbe),
            scope = backgroundScope
        )
        assertEquals(AdbDiscoveryState.Idle, discovery.state.value)

        discovery.startDiscovery(AdbServiceType.TLS_CONNECT, timeoutMs = 5_000L)
        assertEquals(
            AdbDiscoveryState.Searching(AdbServiceType.TLS_CONNECT, 5_000L),
            discovery.state.value
        )

        // Initial wireless debugging port 37111.
        backend.emitResolved(
            DiscoveredAdbService(
                serviceName = "adb-6f8d1516-oldSfx",
                serviceType = AdbServiceType.TLS_CONNECT,
                host = "192.168.1.40",
                port = 37_111
            )
        )
        val firstFound = discovery.state.value as AdbDiscoveryState.Found
        assertEquals(1, firstFound.services.size)
        assertEquals(37_111, firstFound.services.single().port)

        // User toggles Wireless debugging off and back on: new random port 44822 on same phone.
        backend.emitLost("adb-6f8d1516-oldSfx", AdbServiceType.TLS_CONNECT)
        assertTrue(discovery.state.value is AdbDiscoveryState.Searching)

        backend.emitResolved(
            DiscoveredAdbService(
                serviceName = "adb-6f8d1516-newSfx",
                serviceType = AdbServiceType.TLS_CONNECT,
                host = "192.168.1.40",
                port = 44_822
            )
        )
        val secondFound = discovery.state.value as AdbDiscoveryState.Found
        assertEquals(1, secondFound.services.size)
        assertEquals(44_822, secondFound.services.single().port)

        // Even if mDNS delivers a new port BEFORE `onServiceLost` arrives, stale port is replaced.
        backend.emitResolved(
            DiscoveredAdbService(
                serviceName = "adb-6f8d1516-thirdSfx",
                serviceType = AdbServiceType.TLS_CONNECT,
                host = "192.168.1.40",
                port = 45_999
            )
        )
        val thirdFound = discovery.state.value as AdbDiscoveryState.Found
        assertEquals(1, thirdFound.services.size)
        assertEquals(45_999, thirdFound.services.single().port)
        dispatcher.scheduler.runCurrent()
        discovery.stopDiscovery()
        assertEquals(AdbDiscoveryState.Idle, discovery.state.value)
    }

    @Test
    fun `times out with typed failure and supports manual host port fallback`() = runTest {
        val backend = FakeNsdBackend()
        val discovery = AdbMdnsDiscovery(
            backend = backend,
            errorMapper = AdbErrorMapper(activeLanProbe),
            scope = backgroundScope
        )

        discovery.startDiscovery(AdbServiceType.TLS_PAIRING, timeoutMs = 4_000L)
        advanceTimeBy(4_001L)
        runCurrent()

        val timedOut = discovery.state.value as AdbDiscoveryState.TimedOut
        assertEquals(AdbServiceType.TLS_PAIRING, timedOut.serviceType)
        assertTrue(timedOut.failure is AdbFailure.DebuggingOff)
        assertTrue(timedOut.manualFallbackAvailable)
        assertEquals(1, backend.closedSubscriptions)

        val manual = discovery.useManualEndpoint("192.168.1.40:38451", AdbServiceType.TLS_PAIRING)
        assertEquals(AdbEndpoint("192.168.1.40", 38_451), manual)
        assertEquals(
            AdbDiscoveryState.UsingManualEndpoint(AdbServiceType.TLS_PAIRING, manual),
            discovery.state.value
        )

        assertEquals(
            AdbEndpoint("fe80::1", 39_001),
            AdbEndpoint.parse("[fe80::1]:39001")
        )
    }

    @Test
    fun `awaitService resolves matching QR pairing service and fails on timeout`() = runTest {
        val backend = FakeNsdBackend()
        val discovery = AdbMdnsDiscovery(
            backend = backend,
            errorMapper = AdbErrorMapper(activeLanProbe),
            scope = backgroundScope
        )

        val waiter = async {
            discovery.awaitService(
                serviceType = AdbServiceType.TLS_PAIRING,
                timeoutMs = 5_000L
            ) { it.serviceName == "studio-target123" }
        }
        runCurrent()

        // Unrelated pairing service is ignored.
        backend.emitResolved(
            DiscoveredAdbService(
                serviceName = "studio-other999",
                serviceType = AdbServiceType.TLS_PAIRING,
                host = "192.168.1.77",
                port = 33_333
            )
        )
        runCurrent()
        assertTrue(!waiter.isCompleted)

        // Target QR service resolves.
        backend.emitResolved(
            DiscoveredAdbService(
                serviceName = "studio-target123",
                serviceType = AdbServiceType.TLS_PAIRING,
                host = "192.168.1.40",
                port = 41_222
            )
        )
        val resolved = waiter.await()
        assertEquals("192.168.1.40", resolved.host)
        assertEquals(41_222, resolved.port)

        // Timeout raises AdbClientException(DebuggingOff).
        try {
            discovery.awaitService(AdbServiceType.TLS_CONNECT, timeoutMs = 2_000L)
            fail("Expected AdbClientException on timeout")
        } catch (e: AdbClientException) {
            assertTrue(e.failure is AdbFailure.DebuggingOff)
        }
    }
}

internal class FakeNsdBackend : NsdBackend {
    private val listeners = CopyOnWriteArrayList<Pair<AdbServiceType, NsdDiscoveryListener>>()
    var closedSubscriptions: Int = 0
        private set

    override fun startDiscovery(
        serviceType: AdbServiceType,
        listener: NsdDiscoveryListener
    ): Closeable {
        val entry = serviceType to listener
        listeners += entry
        return Closeable {
            if (listeners.remove(entry)) {
                closedSubscriptions++
            }
        }
    }

    fun emitResolved(service: DiscoveredAdbService) {
        for ((type, listener) in listeners) {
            if (type == service.serviceType) {
                listener.onServiceResolved(service)
            }
        }
    }

    fun emitLost(serviceName: String, serviceType: AdbServiceType) {
        for ((type, listener) in listeners) {
            if (type == serviceType) {
                listener.onServiceLost(serviceName, serviceType)
            }
        }
    }
}
