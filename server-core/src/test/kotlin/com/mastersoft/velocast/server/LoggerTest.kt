package com.mastersoft.velocast.server

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoggerTest {
    @Test
    fun filtersLevelsPreservesRingOrderAndFormatsCauseWithoutStdout() {
        val origOut = System.out
        val capturedOut = ByteArrayOutputStream()
        val capturedErr = ByteArrayOutputStream()
        val tempDir = Files.createTempDirectory("velocast-logger-test")
        val logPath = tempDir.resolve("nested/server.log")
        var clock = 100L

        try {
            System.setOut(PrintStream(capturedOut))
            val logger = Logger(
                minLevel = LogLevel.INFO,
                ringCapacity = 3,
                logFile = logPath,
                stderr = PrintStream(capturedErr),
                wallClockMs = { clock++ }
            )
            logger.use {
                it.debug("Tag", "ignored debug")
                it.info("Tag", "first")
                it.warn("Tag", "second")
                it.error("Tag", "third", IllegalStateException("boom"))
                it.info("Tag", "fourth")

                val entries = it.snapshot()
                assertEquals(3, entries.size)
                assertEquals(listOf("second", "third", "fourth"), entries.map { e -> e.message })
                assertTrue(entries[1].causeSummary?.contains("IllegalStateException: boom") == true)
            }

            assertEquals("Logger must never write to stdout", 0, capturedOut.size())
            val errText = capturedErr.toString(Charsets.UTF_8.name())
            assertFalse(errText.contains("ignored debug"))
            assertTrue(errText.contains("IllegalStateException: boom"))

            val fileText = String(Files.readAllBytes(logPath), Charsets.UTF_8)
            assertTrue(fileText.contains("[INFO] [Tag] first"))
            assertTrue(fileText.contains("[ERROR] [Tag] third"))
            assertTrue(fileText.contains("IllegalStateException: boom"))
        } finally {
            System.setOut(origOut)
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun fileLoggerDoesNotDuplicateToSystemErrUnlessOpenFails() {
        val origErr = System.err
        val capturedErr = ByteArrayOutputStream()
        val tempDir = Files.createTempDirectory("velocast-logger-dedup-test")
        val logPath = tempDir.resolve("server.log")
        val blockingFile = tempDir.resolve("not-a-dir").also {
            Files.write(it, byteArrayOf(1))
        }
        val failingLogPath = blockingFile.resolve("server.log")

        try {
            System.setErr(PrintStream(capturedErr))
            Logger(minLevel = LogLevel.INFO, logFile = logPath).use { logger ->
                logger.info("Server", "single-copy-line")
            }
            val stderrOnOk = capturedErr.toString(Charsets.UTF_8.name())
            assertFalse(stderrOnOk.contains("single-copy-line"))

            val fileText = String(Files.readAllBytes(logPath), Charsets.UTF_8)
            assertEquals(1, fileText.lines().count { it.contains("single-copy-line") })

            Logger(minLevel = LogLevel.INFO, logFile = failingLogPath).use { logger ->
                logger.warn("Server", "fallback-to-stderr")
            }
            val stderrOnFail = capturedErr.toString(Charsets.UTF_8.name())
            assertTrue(stderrOnFail.contains("Failed opening log file"))
            assertTrue(stderrOnFail.contains("fallback-to-stderr"))
        } finally {
            System.setErr(origErr)
            tempDir.toFile().deleteRecursively()
        }
    }
}
