// TODO(S03): Refactor daemon classes and package structure in S03.
package com.mastersoft.velocast.daemon

import android.os.Looper
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * VeloCast Daemon — Headless root server launched via `app_process`.
 *
 * Includes global uncaught exception logging, flushed diagnostics, and a 20-second
 * heartbeat watchdog so dropped Wi-Fi sessions cleanly release MediaCodec & VirtualDisplay
 * while keeping the TCP ServerSocket alive for immediate reconnections.
 */
object VeloCastDaemonMain {

    private const val CLIENT_IDLE_TIMEOUT_MS = 20_000

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
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            System.err.println("[VeloCastDaemon] UNCAUGHT on ${thread.name}: ${throwable.message}")
            throwable.printStackTrace(System.err)
            System.err.flush()
        }

        if (Looper.getMainLooper() == null) {
            @Suppress("DEPRECATION")
            Looper.prepareMainLooper()
        }

        VirtualDisplayBridge.bypassHiddenApis()

        val config = parseArgs(args)
        val liveDims = VirtualDisplayBridge.queryDefaultDisplaySize(config.width, config.height)
        println(
            "[VeloCastDaemon] Starting on TCP port ${config.port} " +
                "(host=${liveDims.width}x${liveDims.height})"
        )
        System.out.flush()

        val inputInjector = InputInjector(liveDims.width, liveDims.height)

        try {
            ServerSocket().use { serverSocket ->
                serverSocket.reuseAddress = true
                serverSocket.bind(InetSocketAddress("0.0.0.0", config.port), 16)
                println("[VeloCastDaemon] Listening on 0.0.0.0:${config.port}")
                System.out.flush()

                while (true) {
                    val clientSocket = try {
                        serverSocket.accept()
                    } catch (e: Exception) {
                        if (serverSocket.isClosed) break
                        System.err.println("[VeloCastDaemon] Accept error: ${e.message}")
                        Thread.sleep(500L)
                        continue
                    }
                    activeSession?.let { old ->
                        println(
                            "[VeloCastDaemon] Preempting previous session from ${old.socket.remoteSocketAddress}"
                        )
                        System.out.flush()
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
            System.err.println(
                "[VeloCastDaemon] Fatal server error on port ${config.port}: ${t.message}"
            )
            t.printStackTrace(System.err)
            System.err.flush()
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
        private val initialConfigLatch = CountDownLatch(1)

        fun start() {
            workerThread = Thread({ runSession() }, "velocast-session-worker").apply { start() }
        }

        fun close() {
            initialConfigLatch.countDown()
            encoder.stop()
            try {
                socket.close()
            } catch (_: Throwable) {}
            controlThread?.interrupt()
            workerThread?.interrupt()
            try {
                workerThread?.join(1500L)
            } catch (_: Throwable) {}
        }

        private fun runSession() {
            val remoteAddr = socket.remoteSocketAddress
            println("[VeloCastDaemon] Client connected: $remoteAddr")
            System.out.flush()

            socket.tcpNoDelay = true
            socket.keepAlive = true
            socket.soTimeout = CLIENT_IDLE_TIMEOUT_MS
            socket.sendBufferSize = 256 * 1024
            socket.receiveBufferSize = 64 * 1024

            val dims = VirtualDisplayBridge.queryDefaultDisplaySize(config.width, config.height)
            inputInjector.displayWidth = dims.width
            inputInjector.displayHeight = dims.height

            val rawOut = socket.getOutputStream()

            val ct = Thread({
                val scratch = ByteArray(Protocol.CONTROL_PACKET_SIZE)
                val pongBuf = ByteArray(16)
                var gotInitialConfig = false
                try {
                    val input = DataInputStream(BufferedInputStream(socket.getInputStream(), 4096))
                    while (!socket.isClosed) {
                        val packet = Protocol.readControlPacket(input, scratch)
                        when (packet.action) {
                            Protocol.ACTION_KEY_PRESS -> {
                                inputInjector.injectKeyPress(packet.param)
                            }

                            Protocol.ACTION_CONFIG_STREAM -> {
                                if (!gotInitialConfig) {
                                    gotInitialConfig = true
                                    encoder.applyInitialConfig(
                                        newBitrateBps = packet.param,
                                        newFps = packet.normX.toInt(),
                                        newScale = packet.normY
                                    )
                                    initialConfigLatch.countDown()
                                    if (encoder.isRunning()) {
                                        encoder.requestReconfigure(
                                            newBitrateBps = packet.param,
                                            newFps = packet.normX.toInt(),
                                            newScale = packet.normY
                                        )
                                    }
                                } else {
                                    encoder.requestReconfigure(
                                        newBitrateBps = packet.param,
                                        newFps = packet.normX.toInt(),
                                        newScale = packet.normY
                                    )
                                }
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
                                    rawOut.flush()
                                }
                            }

                            else -> {
                                inputInjector.handleTouchPacket(packet)
                            }
                        }
                    }
                } catch (_: SocketTimeoutException) {
                    println(
                        "[VeloCastDaemon] Client $remoteAddr timed out (${CLIENT_IDLE_TIMEOUT_MS}ms); releasing encoder"
                    )
                } catch (_: EOFException) {
                    println("[VeloCastDaemon] Client $remoteAddr closed control stream")
                } catch (t: Throwable) {
                    if (!socket.isClosed) {
                        System.err.println("[VeloCastDaemon] Control reader error: ${t.message}")
                        System.err.flush()
                    }
                } finally {
                    initialConfigLatch.countDown()
                    encoder.stop()
                    try {
                        socket.close()
                    } catch (_: Throwable) {}
                    inputInjector.resetPointers()
                    System.out.flush()
                }
            }, "velocast-control-reader")
            ct.isDaemon = true
            ct.start()
            controlThread = ct

            try {
                initialConfigLatch.await(1500L, TimeUnit.MILLISECONDS)
                if (!socket.isClosed) {
                    encoder.streamTo(rawOut, streamWriteLock)
                }
            } catch (t: Throwable) {
                println(
                    "[VeloCastDaemon] Video stream ended for $remoteAddr: ${t.javaClass.simpleName}: ${t.message}"
                )
            } finally {
                encoder.stop()
                try {
                    socket.close()
                } catch (_: Throwable) {}
                ct.join(1000L)
                inputInjector.resetPointers()
                if (activeSession === this) {
                    activeSession = null
                }
                println("[VeloCastDaemon] Session closed & encoder released: $remoteAddr")
                System.out.flush()
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
