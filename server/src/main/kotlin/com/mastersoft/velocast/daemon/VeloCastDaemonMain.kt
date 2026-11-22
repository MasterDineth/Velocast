package com.mastersoft.velocast.daemon

import android.os.Looper
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * VeloCast Daemon — Headless root server launched via `app_process`.
 *
 * Includes a 10-second socket read watchdog (paired with the client's 2s heartbeat ping)
 * so dropped Wi-Fi sessions immediately release MediaCodec and VirtualDisplay, preventing
 * runaway CPU/thermal load on the host device.
 */
object VeloCastDaemonMain {

    private const val CLIENT_IDLE_TIMEOUT_MS = 10_000

    private data class ServerConfig(
        val port: Int = Protocol.DEFAULT_PORT,
        val width: Int = 1080,
        val height: Int = 2400,
        val bitrateBps: Int = 8_000_000,
        val fps: Int = 60
    )

    @Volatile
    private var activeSession: ActiveSession? = null

    @JvmStatic
    fun main(args: Array<String>) {
        if (Looper.getMainLooper() == null) {
            @Suppress("DEPRECATION")
            Looper.prepareMainLooper()
        }

        VirtualDisplayBridge.bypassHiddenApis()

        val config = parseArgs(args)
        val liveDims = VirtualDisplayBridge.queryDefaultDisplaySize(config.width, config.height)
        println(
            "[VeloCastDaemon] Starting on TCP port ${config.port} " +
                "(host=${liveDims.width}x${liveDims.height}, default=${config.bitrateBps / 1_000_000} Mbps @ ${config.fps}fps)"
        )

        val inputInjector = InputInjector(liveDims.width, liveDims.height)

        try {
            ServerSocket().use { serverSocket ->
                serverSocket.reuseAddress = true
                serverSocket.bind(InetSocketAddress("0.0.0.0", config.port), 16)
                println("[VeloCastDaemon] Listening on 0.0.0.0:${config.port} (idle CPU ~0% until client connects)")

                while (true) {
                    val clientSocket = serverSocket.accept()
                    activeSession?.let { old ->
                        println("[VeloCastDaemon] Preempting previous session from ${old.socket.remoteSocketAddress}")
                        old.close()
                    }

                    val encoder = ScreenEncoder(
                        baseWidth = config.width,
                        baseHeight = config.height,
                        bitrateBps = config.bitrateBps,
                        fps = config.fps
                    )

                    val session = ActiveSession(
                        socket = clientSocket,
                        encoder = encoder,
                        config = config,
                        inputInjector = inputInjector
                    )
                    activeSession = session
                    session.start()
                }
            }
        } catch (t: Throwable) {
            System.err.println("[VeloCastDaemon] Fatal server error on port ${config.port}: ${t.message}")
            t.printStackTrace()
        }
    }

    private class ActiveSession(
        val socket: Socket,
        val encoder: ScreenEncoder,
        val config: ServerConfig,
        val inputInjector: InputInjector
    ) {
        @Volatile
        private var controlThread: Thread? = null
        @Volatile
        private var workerThread: Thread? = null
        private val streamWriteLock = Any()

        fun start() {
            workerThread = Thread({ runSession() }, "velocast-session-worker").apply { start() }
        }

        fun close() {
            encoder.stop()
            try { socket.close() } catch (_: Throwable) {}
            controlThread?.interrupt()
            workerThread?.interrupt()
        }

        private fun runSession() {
            val remoteAddr = socket.remoteSocketAddress
            println("[VeloCastDaemon] Client connected: $remoteAddr")

            socket.tcpNoDelay = true
            socket.keepAlive = true
            socket.soTimeout = CLIENT_IDLE_TIMEOUT_MS
            socket.sendBufferSize = 256 * 1024
            socket.receiveBufferSize = 64 * 1024

            val dims = VirtualDisplayBridge.queryDefaultDisplaySize(config.width, config.height)
            inputInjector.displayWidth = dims.width
            inputInjector.displayHeight = dims.height

            // Direct unbuffered OutputStream paired with single coalesced writes in Protocol.writeCoalescedFrame
            val rawOut = socket.getOutputStream()

            val ct = Thread({
                val scratch = ByteArray(Protocol.CONTROL_PACKET_SIZE)
                val pongBuf = ByteArray(16)
                try {
                    val input = DataInputStream(BufferedInputStream(socket.getInputStream(), 4096))
                    while (!socket.isClosed) {
                        val packet = Protocol.readControlPacket(input, scratch)
                        when (packet.action) {
                            Protocol.ACTION_KEY_PRESS -> {
                                inputInjector.injectKeyPress(packet.param)
                            }
                            Protocol.ACTION_CONFIG_STREAM -> {
                                encoder.requestReconfigure(
                                    newBitrateBps = packet.param,
                                    newFps = packet.normX.toInt(),
                                    newScale = packet.normY
                                )
                            }
                            Protocol.ACTION_EXPAND_NOTIFICATIONS -> {
                                inputInjector.expandNotificationShade()
                            }
                            Protocol.ACTION_ROTATE_SCREEN -> {
                                inputInjector.toggleScreenRotation()
                            }
                            Protocol.ACTION_PING -> {
                                synchronized(streamWriteLock) {
                                    Protocol.writePongPacket(rawOut, packet.param, pongBuf)
                                }
                            }
                            else -> {
                                inputInjector.handleTouchPacket(packet)
                            }
                        }
                    }
                } catch (_: SocketTimeoutException) {
                    println("[VeloCastDaemon] Client $remoteAddr timed out (${CLIENT_IDLE_TIMEOUT_MS}ms without heartbeat); releasing encoder")
                } catch (_: EOFException) {
                    println("[VeloCastDaemon] Client $remoteAddr closed control stream")
                } catch (t: Throwable) {
                    if (!socket.isClosed) {
                        System.err.println("[VeloCastDaemon] Control reader error: ${t.message}")
                    }
                } finally {
                    encoder.stop()
                    try { socket.close() } catch (_: Throwable) {}
                    inputInjector.resetPointers()
                }
            }, "velocast-control-reader")
            ct.isDaemon = true
            ct.start()
            controlThread = ct

            try {
                encoder.streamTo(rawOut, streamWriteLock)
            } catch (t: Throwable) {
                println("[VeloCastDaemon] Video stream ended for $remoteAddr: ${t.message}")
            } finally {
                encoder.stop()
                try { socket.close() } catch (_: Throwable) {}
                ct.join(1000L)
                inputInjector.resetPointers()
                if (activeSession === this) {
                    activeSession = null
                }
                println("[VeloCastDaemon] Session closed & encoder released: $remoteAddr")
            }
        }
    }

    private fun parseArgs(args: Array<String>): ServerConfig {
        var port = Protocol.DEFAULT_PORT
        var width = 1080
        var height = 2400
        var bitrate = 8_000_000
        var fps = 60

        var i = 0
        while (i < args.size) {
            when (args[i]) {
                "--port" -> port = args.getOrNull(++i)?.toIntOrNull() ?: port
                "--width" -> width = args.getOrNull(++i)?.toIntOrNull() ?: width
                "--height" -> height = args.getOrNull(++i)?.toIntOrNull() ?: height
                "--bitrate" -> bitrate = args.getOrNull(++i)?.toIntOrNull() ?: bitrate
                "--fps" -> fps = args.getOrNull(++i)?.toIntOrNull() ?: fps
            }
            i++
        }
        return ServerConfig(port, width, height, bitrate, fps)
    }
}
