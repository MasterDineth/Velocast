package com.mastersoft.velocast.server

import com.mastersoft.velocast.server.platform.SavedDeviceState
import java.io.Closeable
import java.io.IOException
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Spawns and manages the out-of-process [CleanUp] child launched with `setsid` so device state
 * is restored even if the main server is terminated with `SIGKILL`.
 */
interface CleanUpGuardian : Closeable {
    fun disarm()
}

class ProcessCleanUpGuardian private constructor(
    private val process: Process,
    private val logger: Logger
) : CleanUpGuardian {
    private val lock = Any()
    private var disarmed = false
    private var closed = false

    override fun disarm() {
        synchronized(lock) {
            if (disarmed || closed) return
            disarmed = true
            try {
                OutputStreamWriter(process.outputStream, StandardCharsets.UTF_8).use { writer ->
                    writer.write("DISARM\n")
                    writer.flush()
                }
            } catch (e: IOException) {
                logger.debug(TAG, "Failed writing DISARM to CleanUp stdin", e)
            }
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
        }
        try {
            if (!process.waitFor(EXIT_AWAIT_MS, TimeUnit.MILLISECONDS)) {
                process.destroy()
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            logger.debug(TAG, "Interrupted awaiting CleanUp exit", e)
            process.destroy()
        }
    }

    companion object {
        private const val TAG = "CleanUpGuardian"
        private const val EXIT_AWAIT_MS = 1_000L
        private const val SETSID_BIN = "/system/bin/setsid"
        private const val APP_PROCESS_BIN = "/system/bin/app_process"

        fun buildCommand(
            state: SavedDeviceState,
            restoreDisplayPower: Boolean,
            stateDir: Path?
        ): List<String> {
            val cmd = ArrayList<String>(BASE_CMD_CAPACITY)
            cmd += SETSID_BIN
            cmd += APP_PROCESS_BIN
            cmd += "/system/bin"
            cmd += CleanUp::class.java.name
            cmd += "--accel-rotation"
            cmd += state.accelerometerRotation.toString()
            cmd += "--user-rotation"
            cmd += state.userRotation.toString()
            cmd += "--display-power"
            cmd += if (state.displayPowerOn) "1" else "0"
            cmd += "--restore-display-power"
            cmd += if (restoreDisplayPower) "1" else "0"
            if (stateDir != null) {
                cmd += "--state-dir"
                cmd += stateDir.toString()
            }
            return cmd
        }

        fun start(
            state: SavedDeviceState,
            restoreDisplayPower: Boolean,
            stateDir: Path?,
            logger: Logger
        ): CleanUpGuardian? {
            val cmd = buildCommand(state, restoreDisplayPower, stateDir)
            return try {
                val builder = ProcessBuilder(cmd)
                val cp = System.getProperty("java.class.path")
                if (!cp.isNullOrBlank()) {
                    builder.environment()["CLASSPATH"] = cp
                }
                val proc = builder.start()
                logger.info(TAG, "Started CleanUp guardian process with setsid")
                ProcessCleanUpGuardian(proc, logger)
            } catch (e: IOException) {
                logger.warn(TAG, "Unable to launch CleanUp guardian process", e)
                null
            } catch (e: SecurityException) {
                logger.warn(TAG, "CleanUp guardian launch denied", e)
                null
            }
        }

        private const val BASE_CMD_CAPACITY = 16
    }
}
