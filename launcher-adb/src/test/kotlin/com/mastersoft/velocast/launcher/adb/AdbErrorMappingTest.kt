package com.mastersoft.velocast.launcher.adb

import com.mastersoft.velocast.client.SessionError
import com.mastersoft.velocast.protocol.ErrorCode
import com.mastersoft.velocast.protocol.SecretRedactor
import java.io.EOFException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.crypto.AEADBadTagException
import javax.net.ssl.SSLHandshakeException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdbErrorMappingTest {
    private val activeHomeLan = NetworkEnvironmentProbe {
        LocalNetworkSnapshot(
            isLanAvailable = true,
            ipv4Subnets = listOf(
                Ipv4Subnet.fromAddress(byteArrayOf(192.toByte(), 168.toByte(), 1, 10), 24)
            )
        )
    }
    private val disconnectedLan = NetworkEnvironmentProbe {
        LocalNetworkSnapshot(isLanAvailable = false)
    }

    @Test
    fun `maps refused connections and same-subnet timeouts to DEBUGGING_OFF`() {
        val mapper = AdbErrorMapper(activeHomeLan)

        val refused = mapper.map(
            ConnectException("Failed to connect to /192.168.1.44:39123: ECONNREFUSED"),
            AdbOperationStage.CONNECT,
            targetHost = "192.168.1.44"
        )
        assertTrue(refused is AdbFailure.DebuggingOff)
        assertEquals(ErrorCode.DEBUGGING_OFF, refused.errorCode)
        assertTrue(refused.toSessionError() is SessionError.DebuggingOff)

        val sameSubnetTimeout = mapper.map(
            SocketTimeoutException("connect timed out"),
            AdbOperationStage.CONNECT,
            targetHost = "192.168.1.44"
        )
        assertTrue(sameSubnetTimeout is AdbFailure.DebuggingOff)

        val discoveryTimeout = mapper.mapDiscoveryTimeout(AdbServiceType.TLS_CONNECT)
        assertTrue(discoveryTimeout is AdbFailure.DebuggingOff)
    }

    @Test
    fun `maps unreachable routes, offline Wi-Fi, and cross-subnet timeouts to DIFFERENT_NETWORK`() {
        val mapperOnHomeWifi = AdbErrorMapper(activeHomeLan)
        val mapperOffline = AdbErrorMapper(disconnectedLan)

        val crossSubnet = mapperOnHomeWifi.map(
            SocketTimeoutException("connect timed out"),
            AdbOperationStage.CONNECT,
            targetHost = "192.168.50.88"
        )
        assertTrue(crossSubnet is AdbFailure.DifferentNetwork)
        assertEquals(ErrorCode.DIFFERENT_NETWORK, crossSubnet.errorCode)
        assertTrue(crossSubnet.toSessionError() is SessionError.DifferentNetwork)

        val noRoute = mapperOnHomeWifi.map(
            NoRouteToHostException("EHOSTUNREACH (No route to host)"),
            AdbOperationStage.PAIRING,
            targetHost = "10.0.0.12"
        )
        assertTrue(noRoute is AdbFailure.DifferentNetwork)

        val enetUnreach = mapperOnHomeWifi.map(
            SocketException("Network is unreachable (ENETUNREACH)"),
            AdbOperationStage.CONNECT
        )
        assertTrue(enetUnreach is AdbFailure.DifferentNetwork)

        val unknownHost = mapperOnHomeWifi.map(
            UnknownHostException("bad-phone.local"),
            AdbOperationStage.DISCOVERY
        )
        assertTrue(unknownHost is AdbFailure.DifferentNetwork)

        val offlineDiscovery = mapperOffline.mapDiscoveryTimeout(AdbServiceType.TLS_PAIRING)
        assertTrue(offlineDiscovery is AdbFailure.DifferentNetwork)
    }

    @Test
    fun `distinguishes NOT_PAIRED vs AUTH_EXPIRED during TLS connect handshake`() {
        val mapper = AdbErrorMapper(activeHomeLan)
        val tlsReject = SSLHandshakeException("Received fatal alert: certificate_required")

        val notPaired = mapper.map(
            tlsReject,
            AdbOperationStage.CONNECT,
            targetHost = "192.168.1.44",
            wasPreviouslyPaired = false
        )
        assertTrue(notPaired is AdbFailure.NotPaired)
        assertEquals(ErrorCode.NOT_PAIRED, notPaired.errorCode)
        assertTrue(notPaired.toSessionError() is SessionError.NotPaired)

        val authExpired = mapper.map(
            tlsReject,
            AdbOperationStage.CONNECT,
            targetHost = "192.168.1.44",
            wasPreviouslyPaired = true
        )
        assertTrue(authExpired is AdbFailure.AuthExpired)
        assertEquals(ErrorCode.AUTH_EXPIRED, authExpired.errorCode)
        assertTrue(authExpired.toSessionError() is SessionError.AuthExpired)
    }

    @Test
    fun `maps SPAKE2 and PeerInfo failures to WrongPairingCode and scrubs secrets`() {
        val mapper = AdbErrorMapper(activeHomeLan)

        val badTag = mapper.map(
            AEADBadTagException("Tag mismatch; password=948271"),
            AdbOperationStage.PAIRING,
            targetHost = "192.168.1.44"
        )
        assertTrue(badTag is AdbFailure.WrongPairingCode)
        assertEquals("error_wrong_pairing_code", badTag.causeKey)
        assertFalse(badTag.detail.orEmpty().contains("948271"))
        assertTrue(badTag.detail.orEmpty().contains(SecretRedactor.REDACTED_MARKER))

        val peerClosedAfterBadCode = mapper.map(
            EOFException("Peer closed pairing stream after SPAKE2"),
            AdbOperationStage.PAIRING,
            targetHost = "192.168.1.44"
        )
        assertTrue(peerClosedAfterBadCode is AdbFailure.WrongPairingCode)
    }
}
