package com.mastersoft.velocast.server

import com.mastersoft.velocast.protocol.Authenticator
import java.io.Closeable
import java.io.PrintStream
import java.net.InetAddress
import java.security.SecureRandom

/**
 * Coordinates binding the server socket, printing the single `READY` line to stdout, and running
 * [SessionGuard].
 */
class ServerRunner(
    private val args: ServerArgs,
    private val authenticator: Authenticator,
    private val environment: SessionEnvironment,
    private val clock: ServerClock,
    private val logger: Logger,
    private val stdout: PrintStream = System.out,
    private val random: SecureRandom = SecureRandom(),
    private val acceptorFactory: (InetAddress, Int, Logger) -> ServerAcceptor = { addr, port, log ->
        TcpServerAcceptor(addr, port, log, args.sendBufferBytes)
    }
) : Closeable {
    private val lock = Any()
    private var guard: SessionGuard? = null
    private var closed = false

    fun run() {
        val acceptor = acceptorFactory(args.bindAddress, args.port, logger)
        val nonceBytes = ByteArray(STARTUP_NONCE_BYTES).also { random.nextBytes(it) }
        val nonceHex = toLowerHex(nonceBytes)

        val sessionGuard = SessionGuard(
            acceptor = acceptor,
            authenticator = authenticator,
            environment = environment,
            clock = clock,
            logger = logger,
            random = random
        )

        synchronized(lock) {
            if (closed) {
                sessionGuard.close()
                return
            }
            guard = sessionGuard
        }

        stdout.println("READY port=${acceptor.localPort} nonce=$nonceHex")
        stdout.flush()

        try {
            sessionGuard.runAcceptLoop()
        } finally {
            close()
        }
    }

    override fun close() {
        val toClose = synchronized(lock) {
            if (closed) return
            closed = true
            guard.also { guard = null }
        }
        toClose?.close()
    }

    private fun toLowerHex(bytes: ByteArray): String {
        val hexChars = HEX_DIGITS
        val out = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and BYTE_MASK
            out[i * 2] = hexChars[v ushr HIGH_NIBBLE_SHIFT]
            out[i * 2 + 1] = hexChars[v and LOW_NIBBLE_MASK]
        }
        return String(out)
    }

    private companion object {
        const val STARTUP_NONCE_BYTES = 16
        const val BYTE_MASK = 0xFF
        const val HIGH_NIBBLE_SHIFT = 4
        const val LOW_NIBBLE_MASK = 0x0F
        val HEX_DIGITS = "0123456789abcdef".toCharArray()
    }
}
