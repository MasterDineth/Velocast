package com.mastersoft.velocast.client

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class DataChannelTransportTest {

    @Test
    fun `MirrorClient routes DIRECT_TCP to socketConnector and ADB_TUNNEL to tunnelConnector`() =
        runBlocking {
            var directCalls = 0
            var tunnelCalls = 0
            val directConnector = ClientSocketConnector { host, port, _, _ ->
                directCalls++
                assertEquals("192.168.1.50", host)
                assertEquals(27183, port)
                EmptyClientSocket()
            }
            val tunnelConnector = ClientSocketConnector { host, port, _, _ ->
                tunnelCalls++
                assertEquals("127.0.0.1", host)
                assertEquals(39876, port)
                EmptyClientSocket()
            }

            val client = MirrorClient(
                ioDispatcher = Dispatchers.IO,
                socketConnector = directConnector
            )
            val scope = CoroutineScope(Dispatchers.IO + Job())
            try {
                client.connect(
                    endpoint = ServerEndpoint(
                        host = "192.168.1.50",
                        port = 27183,
                        authenticator = defaultTestAuthenticator(),
                        dataChannelMode = DataChannelMode.DIRECT_TCP
                    ),
                    readTimeoutMs = 1_000L,
                    scope = scope
                )
                assertEquals(1, directCalls)
                assertEquals(0, tunnelCalls)

                client.connect(
                    endpoint = ServerEndpoint(
                        host = "127.0.0.1",
                        port = 39876,
                        authenticator = defaultTestAuthenticator(),
                        dataChannelMode = DataChannelMode.ADB_TUNNEL,
                        dataChannelReason = "usb_transport",
                        tunnelConnector = tunnelConnector
                    ),
                    readTimeoutMs = 1_000L,
                    scope = scope
                )
                assertEquals(1, directCalls)
                assertEquals(1, tunnelCalls)
            } finally {
                client.close()
                scope.cancel()
            }
        }

    @Test
    fun `MirrorClient fails loudly when ADB_TUNNEL has no tunnelConnector`() = runBlocking {
        val client = MirrorClient(ioDispatcher = Dispatchers.IO)
        val scope = CoroutineScope(Dispatchers.IO + Job())
        try {
            client.connect(
                endpoint = ServerEndpoint(
                    host = "127.0.0.1",
                    port = 39876,
                    authenticator = defaultTestAuthenticator(),
                    dataChannelMode = DataChannelMode.ADB_TUNNEL,
                    tunnelConnector = null
                ),
                readTimeoutMs = 1_000L,
                scope = scope
            )
            fail("Expected IOException when tunnelConnector is missing")
        } catch (e: IOException) {
            assertTrue(e.message?.contains("tunnelConnector") == true)
        } finally {
            client.close()
            scope.cancel()
        }
    }

    @Test
    fun `SessionMetrics records dataChannelMode and logs DATA_CHANNEL diagnostics entry`() {
        val metrics = SessionMetrics()
        metrics.recordDataChannel(DataChannelMode.ADB_TUNNEL, "wifi_direct_tcp_fallback")

        val snap = metrics.state.value
        assertEquals(DataChannelMode.ADB_TUNNEL, snap.dataChannelMode)
        assertEquals("wifi_direct_tcp_fallback", snap.dataChannelReason)

        val entry = metrics.diagnosticsLog.snapshot().last()
        assertEquals("DATA_CHANNEL", entry.category)
        assertTrue(entry.message.contains("ADB_TUNNEL"))
        assertTrue(entry.message.contains("wifi_direct_tcp_fallback"))
    }

    private class EmptyClientSocket : ClientSocket {
        override val inputStream: InputStream = ByteArrayInputStream(ByteArray(0))
        override val outputStream: OutputStream = ByteArrayOutputStream()
        override var isClosed: Boolean = false
            private set

        override fun close() {
            isClosed = true
        }
    }
}
