package com.mastersoft.velocast.launcher.adb

import com.mastersoft.velocast.client.ClientSocket
import com.mastersoft.velocast.client.ClientSocketConnector
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.SocketTimeoutException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * [ClientSocket] adapter backed by an open ADB `tcp:<port>` stream (`S07B`).
 */
class AdbStreamClientSocket(private val stream: AdbByteStream) : ClientSocket {
    override val inputStream: InputStream
        get() = stream.inputStream

    override val outputStream: OutputStream
        get() = stream.outputStream

    override val isClosed: Boolean
        get() = stream.isClosed

    override fun close() {
        stream.close()
    }
}

/**
 * Opens an ADB `tcp:<port>` stream to the remote server bound to loopback (`--bind loopback`),
 * used automatically for USB OTG and as a Wi-Fi fallback when direct TCP fails (`S07B`).
 */
class AdbTunnelSocketConnector(private val adbClient: AdbClient) : ClientSocketConnector {
    @Throws(IOException::class)
    override fun connect(
        host: String,
        port: Int,
        readTimeoutMs: Int,
        connectTimeoutMs: Int
    ): ClientSocket {
        require(port in MIN_PORT..MAX_PORT) { "Invalid tunnel port: $port" }
        val boundedConnectMs = connectTimeoutMs.toLong().coerceAtLeast(1L)
        val stream = try {
            runBlocking {
                withTimeout(boundedConnectMs) {
                    adbClient.openStream("tcp:$port")
                }
            }
        } catch (e: TimeoutCancellationException) {
            throw SocketTimeoutException("Timed out opening ADB tunnel tcp:$port: ${e.message}")
                .apply { initCause(e) }
        }
        stream.setReadTimeout(readTimeoutMs.coerceAtLeast(0))
        return AdbStreamClientSocket(stream)
    }

    private companion object {
        const val MIN_PORT = 1
        const val MAX_PORT = 65_535
    }
}
