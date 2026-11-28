package com.mastersoft.velocast.launcher.adb

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.Locale

/**
 * Provides the bundled `:server-core` dex jar file and its SHA-256 digest for [AdbLauncher].
 */
interface ServerJarSource {
    @Throws(IOException::class)
    fun resolveLocalJar(): File

    @Throws(IOException::class)
    fun sha256Hex(): String = Sha256.hashFileHex(resolveLocalJar())
}

/** [ServerJarSource] backed directly by an existing local [File]. */
class FileServerJarSource(private val file: File) : ServerJarSource {
    override fun resolveLocalJar(): File {
        if (!file.isFile) {
            throw IOException("Server jar file does not exist: ${file.path}")
        }
        return file
    }
}

/**
 * [ServerJarSource] that extracts the bundled server jar from Android app `assets/` into
 * `Context.noBackupFilesDir` whenever the extracted copy is missing or its SHA-256 differs.
 */
class AssetServerJarSource(context: Context, private val assetName: String = DEFAULT_ASSET_NAME) :
    ServerJarSource {
    private val appContext = context.applicationContext
    private val extractLock = Any()

    override fun resolveLocalJar(): File = synchronized(extractLock) {
        val dir = appContext.noBackupFilesDir.toPath().resolve(DEFAULT_SUBDIRECTORY)
        Files.createDirectories(dir)
        val targetPath = dir.resolve(assetName)
        val tempPath = dir.resolve("$assetName$TEMP_SUFFIX")

        val assetDigest = copyAssetToTempAndHash(tempPath)
        val existingDigest = if (Files.isRegularFile(targetPath)) {
            Sha256.hashFileHex(targetPath.toFile())
        } else {
            null
        }
        if (existingDigest == assetDigest) {
            Files.deleteIfExists(tempPath)
        } else {
            moveAtomically(tempPath, targetPath)
        }
        targetPath.toFile()
    }

    private fun copyAssetToTempAndHash(tempPath: Path): String {
        val digest = MessageDigest.getInstance(Sha256.ALGORITHM)
        appContext.assets.open(assetName).use { rawIn ->
            DigestInputStream(rawIn, digest).use { digestIn ->
                Files.newOutputStream(tempPath).use { out ->
                    streamCopy(digestIn, out)
                }
            }
        }
        return Sha256.toLowerHex(digest.digest())
    }

    private fun streamCopy(input: DigestInputStream, output: java.io.OutputStream) {
        val buffer = ByteArray(Sha256.STREAM_BUFFER_BYTES)
        var read = input.read(buffer)
        while (read >= 0) {
            if (read > 0) {
                output.write(buffer, 0, read)
            }
            read = input.read(buffer)
        }
    }

    private fun moveAtomically(source: Path, target: Path) {
        try {
            Files.move(
                source,
                target,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    companion object {
        const val DEFAULT_ASSET_NAME = "velocast-server.jar"
        const val DEFAULT_SUBDIRECTORY = "velocast_server_jar"
        private const val TEMP_SUFFIX = ".tmp"
    }
}

/** SHA-256 helper for local server jar hashing and `sha256sum` stdout parsing. */
object Sha256 {
    internal const val ALGORITHM = "SHA-256"
    internal const val STREAM_BUFFER_BYTES = 16 * 1024
    private const val HEX_CHARS_LEN = 64
    private const val BYTE_MASK = 0xFF
    private const val HIGH_NIBBLE_SHIFT = 4
    private const val LOW_NIBBLE_MASK = 0x0F
    private val HEX = "0123456789abcdef".toCharArray()
    private val HEX_64_REGEX = Regex("^[0-9a-fA-F]{$HEX_CHARS_LEN}$")

    @Throws(IOException::class)
    fun hashFileHex(file: File): String {
        if (!file.isFile) {
            throw IOException("Cannot hash missing file: ${file.path}")
        }
        val digest = MessageDigest.getInstance(ALGORITHM)
        val buffer = ByteArray(STREAM_BUFFER_BYTES)
        FileInputStream(file).use { fis ->
            var read = fis.read(buffer)
            while (read >= 0) {
                if (read > 0) {
                    digest.update(buffer, 0, read)
                }
                read = fis.read(buffer)
            }
        }
        return toLowerHex(digest.digest())
    }

    fun hashBytesHex(bytes: ByteArray): String =
        toLowerHex(MessageDigest.getInstance(ALGORITHM).digest(bytes))

    fun parseRemoteSha256Output(result: AdbShellResult): String? {
        val firstLine = if (result.exitCode == 0) {
            result.stdout.lineSequence()
                .map(String::trim)
                .firstOrNull(String::isNotEmpty)
        } else {
            null
        }
        val firstToken = firstLine?.split(WHITESPACE_REGEX, limit = 2)?.firstOrNull().orEmpty()
        return if (HEX_64_REGEX.matches(firstToken)) {
            firstToken.lowercase(Locale.ROOT)
        } else {
            null
        }
    }

    internal fun toLowerHex(bytes: ByteArray): String {
        val out = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and BYTE_MASK
            out[i * 2] = HEX[v ushr HIGH_NIBBLE_SHIFT]
            out[i * 2 + 1] = HEX[v and LOW_NIBBLE_MASK]
        }
        return String(out)
    }

    private val WHITESPACE_REGEX = Regex("\\s+")
}
