package com.mastersoft.velocast.launcher.adb

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Output and exit status of a remote `shell(...)` command execution. */
data class AdbShellResult(val stdout: String, val stderr: String = "", val exitCode: Int = 0) {
    val allOutput: String
        get() = when {
            stderr.isEmpty() -> stdout
            stdout.isEmpty() -> stderr
            else -> stdout + stderr
        }
}

/**
 * Executes ADB `shell` (`shell,v2,raw:` with fallback to classic `shell:`) and `sync:` (`push`)
 * operations over an established [AdbConnectionSession].
 */
internal object AdbServices {
    const val DEFAULT_FILE_MODE = 420 // 0644 octal

    private const val BYTE_MASK = 0xFF
    private const val FEATURE_SHELL_V2 = "shell_v2"
    private const val SHELL_V2_STDOUT = 1
    private const val SHELL_V2_STDERR = 2
    private const val SHELL_V2_EXIT = 3
    private const val SHELL_V2_HEADER_BYTES = 5
    private const val MAX_SHELL_V2_PACKET_BYTES = 4 * 1024 * 1024

    private const val S_IFREG = 0x8000
    private const val MODE_PERM_MASK = 0x0FFF
    private const val MILLIS_PER_SECOND = 1_000L
    private const val SYNC_CHUNK_BYTES = 64 * 1024
    private const val SYNC_HEADER_BYTES = 8
    private const val SYNC_ID_BYTES = 4
    private const val SYNC_ID_SEND = "SEND"
    private const val SYNC_ID_DATA = "DATA"
    private const val SYNC_ID_DONE = "DONE"
    private const val SYNC_ID_OKAY = "OKAY"
    private const val SYNC_ID_FAIL = "FAIL"
    private const val SYNC_ID_QUIT = "QUIT"

    fun executeShell(session: AdbConnectionSession, command: String): AdbShellResult {
        require(command.isNotEmpty()) { "Shell command must not be empty" }
        return if (FEATURE_SHELL_V2 in session.connectedDevice.features) {
            executeShellV2(session, command)
        } else {
            executeLegacyShell(session, command)
        }
    }

    fun pushFile(
        session: AdbConnectionSession,
        localFile: File,
        remotePath: String,
        mode: Int = DEFAULT_FILE_MODE
    ) {
        require(localFile.isFile) { "Local file does not exist: ${localFile.path}" }
        require(remotePath.isNotBlank()) { "Remote path must not be blank" }
        val statMode = S_IFREG or (mode and MODE_PERM_MASK)
        val sendSpec = "$remotePath,$statMode".toByteArray(Charsets.UTF_8)
        val mtimeSeconds = (localFile.lastModified() / MILLIS_PER_SECOND).toInt()

        session.openStream("sync:").use { stream ->
            val out = stream.outputStream
            val input = DataInputStream(stream.inputStream)

            writeSyncRequest(out, SYNC_ID_SEND, sendSpec.size)
            out.write(sendSpec)

            val buffer = ByteArray(SYNC_CHUNK_BYTES)
            FileInputStream(localFile).use { fis ->
                var read = fis.read(buffer)
                while (read >= 0) {
                    if (read > 0) {
                        writeSyncRequest(out, SYNC_ID_DATA, read)
                        out.write(buffer, 0, read)
                    }
                    read = fis.read(buffer)
                }
            }
            writeSyncRequest(out, SYNC_ID_DONE, mtimeSeconds)
            out.flush()

            readSyncStatus(input, remotePath)
            writeSyncRequest(out, SYNC_ID_QUIT, 0)
            out.flush()
        }
    }

    private fun executeShellV2(session: AdbConnectionSession, command: String): AdbShellResult {
        session.openStream("shell,v2,raw:$command").use { stream ->
            val input = DataInputStream(stream.inputStream)
            val stdoutBuf = ByteArrayOutputStream()
            val stderrBuf = ByteArrayOutputStream()
            var exitCode = 0
            val header = ByteArray(SHELL_V2_HEADER_BYTES)
            var finished = false

            while (!finished && tryReadFullyOrEof(input, header)) {
                val packetId = header[0].toInt() and BYTE_MASK
                val length = ByteBuffer.wrap(header, 1, Int.SIZE_BYTES)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .int
                if (length < 0 || length > MAX_SHELL_V2_PACKET_BYTES) {
                    throw IOException("Invalid shell_v2 packet length: $length")
                }
                val payload = ByteArray(length)
                if (length > 0) {
                    input.readFully(payload)
                }
                when (packetId) {
                    SHELL_V2_STDOUT -> stdoutBuf.write(payload)

                    SHELL_V2_STDERR -> stderrBuf.write(payload)

                    SHELL_V2_EXIT -> {
                        if (payload.isNotEmpty()) {
                            exitCode = payload[0].toInt() and BYTE_MASK
                        }
                        finished = true
                    }
                }
            }
            return AdbShellResult(
                stdout = stdoutBuf.toString(Charsets.UTF_8.name()),
                stderr = stderrBuf.toString(Charsets.UTF_8.name()),
                exitCode = exitCode
            )
        }
    }

    private fun executeLegacyShell(session: AdbConnectionSession, command: String): AdbShellResult {
        session.openStream("shell:$command").use { stream ->
            val text = stream.inputStream.bufferedReader(Charsets.UTF_8).readText()
            return AdbShellResult(stdout = text, stderr = "", exitCode = 0)
        }
    }

    private fun tryReadFullyOrEof(input: DataInputStream, target: ByteArray): Boolean {
        val first = input.read()
        if (first < 0) return false
        target[0] = first.toByte()
        if (target.size > 1) {
            try {
                input.readFully(target, 1, target.size - 1)
            } catch (e: EOFException) {
                throw IOException("Truncated shell_v2 packet header", e)
            }
        }
        return true
    }

    private fun writeSyncRequest(output: OutputStream, id: String, arg: Int) {
        val header = ByteBuffer.allocate(SYNC_HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        val idBytes = id.toByteArray(Charsets.US_ASCII)
        header.put(idBytes, 0, SYNC_ID_BYTES)
        header.putInt(arg)
        output.write(header.array())
    }

    private fun readSyncStatus(input: DataInputStream, remotePath: String) {
        val header = ByteArray(SYNC_HEADER_BYTES)
        input.readFully(header)
        val statusId = String(header, 0, SYNC_ID_BYTES, Charsets.US_ASCII)
        val length = ByteBuffer.wrap(header, SYNC_ID_BYTES, SYNC_ID_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
            .int
        when (statusId) {
            SYNC_ID_OKAY -> {
                if (length > 0) {
                    input.readFully(ByteArray(length.coerceAtMost(SYNC_CHUNK_BYTES)))
                }
            }

            SYNC_ID_FAIL -> {
                val safeLen = length.coerceIn(0, SYNC_CHUNK_BYTES)
                val reasonBytes = ByteArray(safeLen).also { if (safeLen > 0) input.readFully(it) }
                val reason = String(reasonBytes, Charsets.UTF_8)
                throw AdbClientException(
                    AdbFailure.TransportError("ADB push to '$remotePath' failed: $reason")
                )
            }

            else -> throw IOException("Unexpected ADB sync status '$statusId'")
        }
    }
}
