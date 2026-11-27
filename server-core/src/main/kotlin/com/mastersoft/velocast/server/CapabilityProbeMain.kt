package com.mastersoft.velocast.server

import android.os.Looper
import com.mastersoft.velocast.server.platform.AndroidCapabilityProbeBackend
import com.mastersoft.velocast.server.platform.FakeContext
import com.mastersoft.velocast.server.platform.VirtualDisplayBridge
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.system.exitProcess

/**
 * Standalone `app_process` entry point for the S05 shell-user capability probe.
 *
 * Prints one JSON line per capability to stdout and, when `--out <path>` is passed, writes the
 * identical JSON-lines output to `<path>` for `adb pull`.
 */
object CapabilityProbeMain {
    private const val TAG = "CapabilityProbeMain"

    data class Args(val outPath: Path? = null, val logLevel: LogLevel = LogLevel.WARN) {
        companion object {
            fun parse(args: Array<String>): Args {
                var outPath: Path? = null
                var logLevel = LogLevel.WARN
                var index = 0
                while (index < args.size) {
                    when (val flag = args[index]) {
                        "--out" -> {
                            val next = index + 1
                            require(next < args.size) { "Missing value for --out" }
                            val raw = args[++index]
                            require(raw.isNotBlank()) { "--out must not be blank" }
                            outPath = Paths.get(raw)
                        }

                        "--log-level" -> {
                            val next = index + 1
                            require(next < args.size) { "Missing value for --log-level" }
                            logLevel = LogLevel.parse(args[++index])
                        }

                        else -> throw IllegalArgumentException("Unknown argument: $flag")
                    }
                    index++
                }
                return Args(outPath = outPath, logLevel = logLevel)
            }
        }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val parsed = Args.parse(args)
        val logger = Logger(minLevel = parsed.logLevel)
        try {
            if (Looper.getMainLooper() == null) {
                @Suppress("DEPRECATION")
                Looper.prepareMainLooper()
            }
            VirtualDisplayBridge.bypassHiddenApis(logger)
            val platformContext = FakeContext()
            val backend = AndroidCapabilityProbeBackend(platformContext, logger)
            val probe = CapabilityProbe(backend)

            val buffer = ByteArrayOutputStream()
            val teeOut = PrintStream(buffer, true, StandardCharsets.UTF_8.name())
            val records = probe.runAll(out = teeOut)
            val jsonLines = buffer.toString(StandardCharsets.UTF_8.name())

            System.out.print(jsonLines)
            System.out.flush()

            val outPath = parsed.outPath
            if (outPath != null) {
                outPath.parent?.let { Files.createDirectories(it) }
                Files.write(outPath, jsonLines.toByteArray(StandardCharsets.UTF_8))
            }
            logger.info(TAG, "Completed ${records.size} capability probes")
        } finally {
            logger.close()
        }
        // Binder worker threads started by app_process keep the JVM alive unless exited explicitly.
        exitProcess(0)
    }
}
