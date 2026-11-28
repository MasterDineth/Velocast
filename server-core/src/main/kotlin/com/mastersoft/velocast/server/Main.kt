package com.mastersoft.velocast.server

import android.os.Looper
import com.mastersoft.velocast.protocol.HkdfAuthenticator
import com.mastersoft.velocast.server.platform.VirtualDisplayBridge
import java.io.IOException

/**
 * Headless VeloCast server entry point launched via `app_process`.
 *
 * Arguments:
 *   --port N             TCP port (`0` for ephemeral; default `27183`)
 *   --bind IP|loopback   Bind address (`loopback` -> `127.0.0.1`, default `loopback`)
 *   --state-dir PATH     Optional directory for server logs and state
 *   --secret-from-stdin  Read hex-encoded session/pairing secret line from stdin (required)
 *   --log-level LEVEL    `DEBUG`, `INFO`, `WARN`, or `ERROR` (default `INFO`)
 */
object Main {
    private const val TAG = "Main"
    private const val LOG_FILE_NAME = "server.log"

    @JvmStatic
    fun main(args: Array<String>) {
        val parsedArgs = ServerArgs.parse(args)
        require(parsedArgs.secretFromStdin) { "--secret-from-stdin is required" }

        val logFile = parsedArgs.stateDir?.resolve(LOG_FILE_NAME)
        val logger = Logger(minLevel = parsedArgs.logLevel, logFile = logFile)

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            logger.error(TAG, "Uncaught exception on thread ${thread.name}", throwable)
        }

        prepareLooperIfNeeded()
        VirtualDisplayBridge.bypassHiddenApis(logger)

        val secret = SecretReader.readHexLine(System.`in`)
        val authenticator = try {
            logger.redactor.registerSecret(secret)
            HkdfAuthenticator(secret, parsedArgs.authMode)
        } finally {
            secret.fill(0)
        }

        val clock = AndroidServerClock()
        val environment = AndroidSessionEnvironment(
            clock = clock,
            logger = logger,
            stateDir = parsedArgs.stateDir,
            bitrateMode = parsedArgs.bitrateMode
        )
        val runner = ServerRunner(
            args = parsedArgs,
            authenticator = authenticator,
            environment = environment,
            clock = clock,
            logger = logger
        )

        Runtime.getRuntime().addShutdownHook(
            Thread({
                runner.close()
                authenticator.close()
                logger.close()
            }, "velocast-shutdown")
        )

        try {
            runner.run()
        } catch (e: IOException) {
            logger.error(TAG, "Fatal server I/O error on port ${parsedArgs.port}", e)
            throw e
        } finally {
            runner.close()
            authenticator.close()
            logger.close()
        }
    }

    private fun prepareLooperIfNeeded() {
        if (Looper.getMainLooper() == null) {
            @Suppress("DEPRECATION")
            Looper.prepareMainLooper()
        }
    }
}
