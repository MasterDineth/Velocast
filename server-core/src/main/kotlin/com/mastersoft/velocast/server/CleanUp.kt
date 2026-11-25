package com.mastersoft.velocast.server

import com.mastersoft.velocast.protocol.ProtocolV2
import com.mastersoft.velocast.server.platform.AndroidDeviceStateController
import com.mastersoft.velocast.server.platform.SavedDeviceState
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Out-of-process cleanup guardian launched with `setsid` at session start (FUN-06).
 *
 * Reads commands on stdin until EOF:
 * - If `"DISARM"` arrives, the main server already restored state cleanly, so `CleanUp` exits 0.
 * - If stdin closes at EOF without `"DISARM"` (e.g., `kill -9` of the server), `CleanUp` restores
 *   the recorded rotation lock and display power state before exiting.
 */
object CleanUp {
    private const val TAG = "CleanUp"
    private const val DISARM_COMMAND = "DISARM"
    private const val LOG_FILE_NAME = "cleanup.log"

    data class Args(
        val state: SavedDeviceState,
        val restoreDisplayPower: Boolean,
        val stateDir: Path? = null,
        val testOutput: Path? = null
    ) {
        companion object {
            fun parse(args: Array<String>): Args {
                var accel = 1
                var userRot = 0
                var displayPower = true
                var restoreDisplay = false
                var stateDir: Path? = null
                var testOutput: Path? = null

                var i = 0
                while (i < args.size) {
                    when (val flag = args[i]) {
                        "--accel-rotation" -> {
                            accel = if (nextValue(args, i++, flag).toInt() == 0) 0 else 1
                        }

                        "--user-rotation" -> {
                            userRot = nextValue(args, i++, flag).toInt()
                                .coerceIn(0, ProtocolV2.MAX_ROTATION)
                        }

                        "--display-power" -> {
                            displayPower = nextValue(args, i++, flag).toInt() != 0
                        }

                        "--restore-display-power" -> {
                            restoreDisplay = nextValue(args, i++, flag).toInt() != 0
                        }

                        "--state-dir" -> {
                            stateDir = Paths.get(nextValue(args, i++, flag))
                        }

                        "--test-output" -> {
                            testOutput = Paths.get(nextValue(args, i++, flag))
                        }

                        else -> throw IllegalArgumentException("Unknown CleanUp argument: $flag")
                    }
                    i++
                }
                return Args(
                    state = SavedDeviceState(accel, userRot, displayPower),
                    restoreDisplayPower = restoreDisplay,
                    stateDir = stateDir,
                    testOutput = testOutput
                )
            }

            private fun nextValue(args: Array<String>, idx: Int, flag: String): String {
                val next = idx + 1
                require(next < args.size) { "Missing value for $flag" }
                return args[next]
            }
        }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val parsed = Args.parse(args)
        val logger = Logger(
            minLevel = LogLevel.INFO,
            logFile = parsed.stateDir?.resolve(LOG_FILE_NAME)
        )
        try {
            run(parsed, System.`in`, logger) { target ->
                if (parsed.testOutput != null) {
                    writeTestMarker(parsed.testOutput, target, parsed.restoreDisplayPower)
                } else {
                    restoreAndroidState(target, parsed.restoreDisplayPower, logger)
                }
            }
        } finally {
            logger.close()
        }
    }

    internal fun run(
        args: Args,
        stdin: InputStream,
        logger: Logger,
        onRestore: (SavedDeviceState) -> Unit
    ): Boolean {
        val disarmed = awaitDisarmOrEof(stdin, logger)
        if (!disarmed) {
            logger.info(
                TAG,
                "Server stdin closed without DISARM; restoring state=${args.state}"
            )
            onRestore(args.state)
        } else {
            logger.debug(TAG, "CleanUp disarmed after clean server restore")
        }
        return !disarmed
    }

    private fun awaitDisarmOrEof(stdin: InputStream, logger: Logger): Boolean = try {
        BufferedReader(InputStreamReader(stdin, StandardCharsets.UTF_8)).use { reader ->
            var disarmed = false
            var line = reader.readLine()
            while (line != null && !disarmed) {
                if (line.trim() == DISARM_COMMAND) {
                    disarmed = true
                } else {
                    line = reader.readLine()
                }
            }
            disarmed
        }
    } catch (e: IOException) {
        logger.warn(TAG, "CleanUp stdin read ended with error", e)
        false
    }

    private fun restoreAndroidState(
        state: SavedDeviceState,
        restoreDisplayPower: Boolean,
        logger: Logger
    ) {
        val controller = AndroidDeviceStateController(logger)
        if (restoreDisplayPower) {
            controller.restoreState(state)
        } else {
            AndroidDeviceStateController.restoreRotationState(
                state.accelerometerRotation,
                state.userRotation,
                logger
            )
        }
    }

    private fun writeTestMarker(
        output: Path,
        state: SavedDeviceState,
        restoreDisplayPower: Boolean
    ) {
        val parent = output.parent
        if (parent != null) {
            Files.createDirectories(parent)
        }
        val content = "accel=${state.accelerometerRotation} " +
            "user=${state.userRotation} " +
            "power=${if (state.displayPowerOn) 1 else 0} " +
            "restorePower=${if (restoreDisplayPower) 1 else 0}\n"
        Files.write(output, content.toByteArray(StandardCharsets.UTF_8))
    }
}
