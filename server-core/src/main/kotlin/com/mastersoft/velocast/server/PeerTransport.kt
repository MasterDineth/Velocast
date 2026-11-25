package com.mastersoft.velocast.server

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Socket abstraction so [SessionGuard] and [ServerSession] run cleanly over real TCP sockets in
 * production and deterministic fake sockets in unit tests.
 */
interface PeerSocket : Closeable {
    val remoteAddress: InetAddress
    val inputStream: InputStream
    val outputStream: OutputStream
    val isClosed: Boolean

    fun setSoTimeout(timeoutMs: Int)
}

interface ServerAcceptor : Closeable {
    val localPort: Int
    val isClosed: Boolean

    fun accept(): PeerSocket
}

class TcpPeerSocket(private val socket: Socket, private val logger: Logger) : PeerSocket {
    init {
        try {
            socket.tcpNoDelay = true
            socket.keepAlive = true
            socket.sendBufferSize = SEND_BUFFER_BYTES
            socket.receiveBufferSize = RECV_BUFFER_BYTES
        } catch (e: IOException) {
            logger.warn(TAG, "Failed configuring TCP socket options", e)
        }
    }

    override val remoteAddress: InetAddress
        get() = socket.inetAddress ?: IpLiterals.LOOPBACK_V4

    override val inputStream: InputStream
        get() = socket.getInputStream()

    override val outputStream: OutputStream
        get() = socket.getOutputStream()

    override val isClosed: Boolean
        get() = socket.isClosed

    override fun setSoTimeout(timeoutMs: Int) {
        socket.soTimeout = timeoutMs
    }

    override fun close() {
        socket.close()
    }

    private companion object {
        const val TAG = "TcpPeerSocket"
        const val SEND_BUFFER_BYTES = 256 * 1024
        const val RECV_BUFFER_BYTES = 64 * 1024
    }
}

class TcpServerAcceptor(bindAddress: InetAddress, port: Int, private val logger: Logger) :
    ServerAcceptor {
    private val serverSocket = ServerSocket().apply {
        reuseAddress = true
        bind(InetSocketAddress(bindAddress, port), BACKLOG)
    }

    override val localPort: Int
        get() = serverSocket.localPort

    override val isClosed: Boolean
        get() = serverSocket.isClosed

    override fun accept(): PeerSocket = TcpPeerSocket(serverSocket.accept(), logger)

    override fun close() {
        try {
            serverSocket.close()
        } catch (e: IOException) {
            logger.warn(TAG, "Error closing ServerSocket", e)
        }
    }

    private companion object {
        const val TAG = "TcpServerAcceptor"
        const val BACKLOG = 16
    }
}
