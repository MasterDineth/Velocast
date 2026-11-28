package com.mastersoft.velocast.server

import com.mastersoft.velocast.protocol.SecretRedactor
import java.io.BufferedWriter
import java.io.Closeable
import java.io.IOException
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.util.Locale

enum class LogLevel(val priority: Int) {
    DEBUG(PRIORITY_DEBUG),
    INFO(PRIORITY_INFO),
    WARN(PRIORITY_WARN),
    ERROR(PRIORITY_ERROR)
    ;

    companion object {
        fun parse(raw: String): LogLevel = when (raw.trim().uppercase(Locale.ROOT)) {
            "DEBUG" -> DEBUG
            "INFO" -> INFO
            "WARN" -> WARN
            "ERROR" -> ERROR
            else -> throw IllegalArgumentException("Unknown log level: $raw")
        }
    }
}

private const val PRIORITY_DEBUG = 0
private const val PRIORITY_INFO = 1
private const val PRIORITY_WARN = 2
private const val PRIORITY_ERROR = 3

data class LogEntry(
    val timestampMs: Long,
    val level: LogLevel,
    val tag: String,
    val message: String,
    val causeSummary: String? = null
) {
    fun formatLine(): String = buildString {
        append(timestampMs)
        append(" [")
        append(level.name)
        append("] [")
        append(tag)
        append("] ")
        append(message)
        if (causeSummary != null) {
            append('\n')
            append(causeSummary)
        }
    }
}

/**
 * Thread-safe logger with level filtering, a bounded in-memory ring buffer, and optional
 * state-dir file persistence. Never writes to stdout (which is reserved for the READY line).
 */
class Logger(
    val minLevel: LogLevel = LogLevel.INFO,
    val ringCapacity: Int = DEFAULT_RING_CAPACITY,
    logFile: Path? = null,
    stderr: PrintStream? = if (logFile == null) defaultStderr() else null,
    val redactor: SecretRedactor = SecretRedactor(),
    private val wallClockMs: () -> Long = System::currentTimeMillis
) : Closeable {
    private val lock = Any()
    private val ring = arrayOfNulls<LogEntry>(ringCapacity)
    private var ringHead = 0
    private var ringSize = 0
    private var activeStderr: PrintStream? = stderr
    private var writer: BufferedWriter? = null
    private var closed = false

    init {
        require(ringCapacity > 0) { "ringCapacity must be positive" }
        if (logFile != null) {
            writer = tryOpenLogFile(logFile)
        }
    }

    fun debug(tag: String, message: String, cause: Throwable? = null) {
        log(LogLevel.DEBUG, tag, message, cause)
    }

    fun info(tag: String, message: String, cause: Throwable? = null) {
        log(LogLevel.INFO, tag, message, cause)
    }

    fun warn(tag: String, message: String, cause: Throwable? = null) {
        log(LogLevel.WARN, tag, message, cause)
    }

    fun error(tag: String, message: String, cause: Throwable? = null) {
        log(LogLevel.ERROR, tag, message, cause)
    }

    fun log(level: LogLevel, tag: String, message: String, cause: Throwable? = null) {
        if (level.priority < minLevel.priority) return
        val entry = LogEntry(
            timestampMs = wallClockMs(),
            level = level,
            tag = tag,
            message = redactor.redact(message),
            causeSummary = cause?.let { redactor.redact(it) }
        )
        val line = entry.formatLine()
        synchronized(lock) {
            appendRingLocked(entry)
            activeStderr?.println(line)
            activeStderr?.flush()
            if (!closed) {
                try {
                    writer?.apply {
                        write(line)
                        newLine()
                        flush()
                    }
                } catch (e: IOException) {
                    val fallback = activeStderr ?: defaultStderr().also { activeStderr = it }
                    fallback.println("${wallClockMs()} [ERROR] [Logger] Failed writing log: $e")
                }
            }
        }
    }

    fun snapshot(): List<LogEntry> = synchronized(lock) {
        val result = ArrayList<LogEntry>(ringSize)
        val start = if (ringSize < ringCapacity) 0 else ringHead
        for (i in 0 until ringSize) {
            val idx = (start + i) % ringCapacity
            ring[idx]?.let { result.add(it) }
        }
        result
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            try {
                writer?.close()
            } catch (e: IOException) {
                activeStderr?.println(
                    "${wallClockMs()} [ERROR] [Logger] Failed closing log file: $e"
                )
            } finally {
                writer = null
            }
        }
    }

    private fun appendRingLocked(entry: LogEntry) {
        ring[ringHead] = entry
        ringHead = (ringHead + 1) % ringCapacity
        if (ringSize < ringCapacity) {
            ringSize++
        }
    }

    private fun tryOpenLogFile(logFile: Path): BufferedWriter? = try {
        openLogFile(logFile) { path, err ->
            activeStderr?.println("${wallClockMs()} [WARN] [Logger] Could not chmod $path: $err")
        }
    } catch (e: IOException) {
        fallBackToStderr("Failed opening log file $logFile", e)
        null
    } catch (e: SecurityException) {
        fallBackToStderr("Permission denied opening log file $logFile", e)
        null
    }

    private fun fallBackToStderr(message: String, cause: Throwable) {
        val fallback = activeStderr ?: defaultStderr().also { activeStderr = it }
        val safeMsg = redactor.redact(message)
        val safeCause = redactor.redact(cause)
        fallback.println("${wallClockMs()} [WARN] [Logger] $safeMsg\n$safeCause")
        fallback.flush()
    }

    companion object {
        const val DEFAULT_RING_CAPACITY = 256
        private const val POSIX_DIR_PERMS = "rwx------"
        private const val POSIX_FILE_PERMS = "rw-------"

        private fun defaultStderr(): PrintStream = java.lang.System.err

        private fun openLogFile(
            logFile: Path,
            onChmodError: (Path, Exception) -> Unit
        ): BufferedWriter {
            val parent = logFile.parent
            if (parent != null) {
                Files.createDirectories(parent)
                applyOwnerOnlyPermissions(parent, POSIX_DIR_PERMS, onChmodError)
            }
            val opened = Files.newBufferedWriter(
                logFile,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND,
                StandardOpenOption.WRITE
            )
            applyOwnerOnlyPermissions(logFile, POSIX_FILE_PERMS, onChmodError)
            return opened
        }

        private fun applyOwnerOnlyPermissions(
            path: Path,
            perms: String,
            onChmodError: (Path, Exception) -> Unit
        ) {
            val supportsPosix =
                FileSystems.getDefault().supportedFileAttributeViews().contains("posix")
            if (!supportsPosix) return
            try {
                Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(perms))
            } catch (e: IOException) {
                onChmodError(path, e)
            } catch (e: SecurityException) {
                onChmodError(path, e)
            }
        }
    }
}
