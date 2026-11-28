package com.mastersoft.velocast.client

import com.mastersoft.velocast.protocol.ConstantTime
import com.mastersoft.velocast.protocol.KeyStoreSecurityException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.Locale

internal data class ParsedDeviceStoreFile(val schemaVersion: Int, val ciphertext: ByteArray)

internal data class DeviceRecordV1(
    val id: String,
    val displayName: String,
    val model: String?,
    val lastAddress: String?,
    val adbKeyAlias: String?,
    val method: LaunchMethod,
    val lastSeen: Long
)

internal data class DeviceRecordV2(
    val id: String,
    val displayName: String,
    val model: String?,
    val androidApi: Int?,
    val lastAddress: String?,
    val adbKeyAlias: String?,
    val secretRef: String?,
    val method: LaunchMethod,
    val lastSeen: Long
)

/**
 * Built-in schema migrations for [EncryptedFileDeviceStore] (`S07B`).
 */
object DeviceStoreMigrations {
    val defaultMigrations: List<DeviceStoreMigration> = listOf(
        V1ToV2Migration,
        V2ToV3Migration
    )

    fun migrateToLatest(
        fromVersion: Int,
        payload: ByteArray,
        targetVersion: Int = DeviceStoreCodec.CURRENT_SCHEMA_VERSION,
        migrations: List<DeviceStoreMigration> = defaultMigrations
    ): ByteArray {
        validateVersionRange(fromVersion, targetVersion)
        if (fromVersion == targetVersion) return payload

        val bySource = migrations.associateBy { it.fromVersion }
        var currentVersion = fromVersion
        var currentPayload = payload
        while (currentVersion < targetVersion) {
            val migration = bySource[currentVersion]
                ?: throw DeviceStoreMigrationException(
                    "Missing DeviceStore migration from v$currentVersion toward v$targetVersion"
                )
            val nextPayload = runStep(migration, currentVersion, targetVersion, currentPayload)
            if (currentPayload !== payload) {
                currentPayload.fill(0)
            }
            currentPayload = nextPayload
            currentVersion = migration.toVersion
        }
        return currentPayload
    }

    private fun validateVersionRange(fromVersion: Int, targetVersion: Int) {
        if (fromVersion < DeviceStoreCodec.SCHEMA_VERSION_1) {
            throw DeviceStoreMigrationException("Unsupported DeviceStore version: $fromVersion")
        }
        if (fromVersion > targetVersion) {
            throw DeviceStoreMigrationException(
                "DeviceStore version $fromVersion is newer than supported $targetVersion"
            )
        }
    }

    private fun runStep(
        migration: DeviceStoreMigration,
        currentVersion: Int,
        targetVersion: Int,
        currentPayload: ByteArray
    ): ByteArray {
        validateStepTarget(migration, currentVersion, targetVersion)
        return try {
            migration.migrate(currentPayload)
        } catch (e: IOException) {
            throw asMigrationException(currentVersion, migration.toVersion, e)
        } catch (e: IllegalArgumentException) {
            throw DeviceStoreMigrationException(
                "Malformed DeviceStore data during v$currentVersion -> v${migration.toVersion}",
                e
            )
        }
    }

    private fun validateStepTarget(
        migration: DeviceStoreMigration,
        currentVersion: Int,
        targetVersion: Int
    ) {
        if (migration.toVersion <= currentVersion || migration.toVersion > targetVersion) {
            throw DeviceStoreMigrationException(
                "Invalid migration step v${migration.fromVersion} -> v${migration.toVersion}"
            )
        }
    }

    private fun asMigrationException(
        currentVersion: Int,
        toVersion: Int,
        error: IOException
    ): DeviceStoreMigrationException = if (error is DeviceStoreMigrationException) {
        error
    } else {
        DeviceStoreMigrationException(
            "Failed migrating DeviceStore v$currentVersion -> v$toVersion",
            error
        )
    }

    private object V1ToV2Migration : DeviceStoreMigration {
        override val fromVersion: Int = DeviceStoreCodec.SCHEMA_VERSION_1
        override val toVersion: Int = DeviceStoreCodec.SCHEMA_VERSION_2

        override fun migrate(payload: ByteArray): ByteArray {
            val records = DeviceStoreCodec.decodeV1(payload)
            val upgraded = records.map { v1 ->
                DeviceRecordV2(
                    id = v1.id,
                    displayName = v1.displayName,
                    model = v1.model,
                    androidApi = null,
                    lastAddress = v1.lastAddress,
                    adbKeyAlias = v1.adbKeyAlias,
                    secretRef = null,
                    method = v1.method,
                    lastSeen = v1.lastSeen
                )
            }
            return DeviceStoreCodec.encodeV2(upgraded)
        }
    }

    private object V2ToV3Migration : DeviceStoreMigration {
        override val fromVersion: Int = DeviceStoreCodec.SCHEMA_VERSION_2
        override val toVersion: Int = DeviceStoreCodec.CURRENT_SCHEMA_VERSION

        override fun migrate(payload: ByteArray): ByteArray {
            val records = DeviceStoreCodec.decodeV2(payload)
            val upgraded = records.map { v2 ->
                Device(
                    id = v2.id,
                    displayName = v2.displayName,
                    model = v2.model,
                    androidApi = v2.androidApi,
                    lastAddress = v2.lastAddress,
                    adbKeyAlias = v2.adbKeyAlias,
                    secretRef = v2.secretRef,
                    method = v2.method,
                    lastSeen = v2.lastSeen,
                    preparedItems = emptySet(),
                    hostAppVersion = null
                )
            }
            return DeviceStoreCodec.encodeV3(upgraded)
        }
    }
}

internal object DeviceStoreEnvelopeIo {
    private const val MAGIC_0 = 'V'.code.toByte()
    private const val MAGIC_1 = 'L'.code.toByte()
    private const val MAGIC_2 = 'C'.code.toByte()
    private const val MAGIC_3 = 'D'.code.toByte()
    private val MAGIC = byteArrayOf(MAGIC_0, MAGIC_1, MAGIC_2, MAGIC_3)

    private const val HEADER_BYTES = 6
    private const val MAX_FILE_BYTES = 2 * 1024 * 1024
    private const val TEMP_SUFFIX = ".tmp"
    private const val POSIX_DIR_PERMS = "rwx------"
    private const val POSIX_FILE_PERMS = "rw-------"
    private const val ALIAS_MAX_LEN = 64
    private const val ALIAS_PREFIX = "device_"
    private const val ALIAS_SUFFIX = "_pairing"
    private const val ALIAS_HASH_LEN = 8
    private const val ALIAS_MAX_CORE_LEN =
        ALIAS_MAX_LEN - ALIAS_PREFIX.length - ALIAS_SUFFIX.length - ALIAS_HASH_LEN - 1
    private const val BYTE_MASK = 0xFF
    private const val BYTE_SHIFT = 8
    private const val HIGH_NIBBLE_SHIFT = 4
    private const val LOW_NIBBLE_MASK = 0x0F
    private val HEX = "0123456789abcdef".toCharArray()

    fun buildHeaderAad(schemaVersion: Int): ByteArray {
        val out = ByteArray(HEADER_BYTES)
        MAGIC.copyInto(out, 0)
        out[MAGIC.size] = ((schemaVersion ushr BYTE_SHIFT) and BYTE_MASK).toByte()
        out[MAGIC.size + 1] = (schemaVersion and BYTE_MASK).toByte()
        return out
    }

    fun parseEnvelopeFile(raw: ByteArray): ParsedDeviceStoreFile {
        if (raw.size <= HEADER_BYTES || raw.size > MAX_FILE_BYTES) {
            throw KeyStoreSecurityException("Malformed or truncated DeviceStore file")
        }
        if (!ConstantTime.equals(MAGIC, raw.copyOfRange(0, MAGIC.size))) {
            throw KeyStoreSecurityException("Invalid DeviceStore magic header")
        }
        val version = ((raw[MAGIC.size].toInt() and BYTE_MASK) shl BYTE_SHIFT) or
            (raw[MAGIC.size + 1].toInt() and BYTE_MASK)
        return ParsedDeviceStoreFile(
            schemaVersion = version,
            ciphertext = raw.copyOfRange(HEADER_BYTES, raw.size)
        )
    }

    fun buildSecretAlias(deviceId: String): String {
        val cleaned = buildString(deviceId.length) {
            for (ch in deviceId) {
                append(if (isAliasChar(ch)) ch else '_')
            }
        }.ifEmpty { "device" }
        val candidate = "$ALIAS_PREFIX$cleaned$ALIAS_SUFFIX"
        return if (candidate.length <= ALIAS_MAX_LEN) {
            candidate
        } else {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(deviceId.toByteArray(Charsets.UTF_8))
            val hashSuffix = buildString(ALIAS_HASH_LEN) {
                for (i in 0 until ALIAS_HASH_LEN / 2) {
                    val v = digest[i].toInt() and BYTE_MASK
                    append(HEX[v ushr HIGH_NIBBLE_SHIFT])
                    append(HEX[v and LOW_NIBBLE_MASK])
                }
            }.lowercase(Locale.ROOT)
            val prefix = cleaned.take(ALIAS_MAX_CORE_LEN)
            "$ALIAS_PREFIX${prefix}_$hashSuffix$ALIAS_SUFFIX"
        }
    }

    private fun isAliasChar(ch: Char): Boolean = when (ch) {
        in 'A'..'Z', in 'a'..'z', in '0'..'9', '.', '_', '-' -> true
        else -> false
    }

    fun writeAtomically(directory: Path, target: Path, bytes: ByteArray) {
        Files.createDirectories(directory)
        applyPosixPermissions(directory, POSIX_DIR_PERMS)
        val temp = directory.resolve("${target.fileName}$TEMP_SUFFIX")
        Files.write(
            temp,
            bytes,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE
        )
        applyPosixPermissions(temp, POSIX_FILE_PERMS)
        try {
            Files.move(
                temp,
                target,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
        }
        applyPosixPermissions(target, POSIX_FILE_PERMS)
    }

    private fun applyPosixPermissions(path: Path, perms: String) {
        if (!FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) return
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(perms))
        } catch (_: IOException) {
            // Non-POSIX filesystems rely on Android app-private sandbox permissions.
        }
    }
}

internal object DeviceStoreBinaryIo {
    const val MAX_DEVICES = 1024
    const val MAX_PREPARED_ITEMS = 128
    private const val MAX_STRING_BYTES = 4096

    fun readBoundedCount(input: DataInputStream, max: Int, label: String): Int {
        val count = input.readInt()
        if (count < 0 || count > max) {
            throw DeviceStoreMigrationException("Invalid $label: $count")
        }
        return count
    }

    fun writeString(out: DataOutputStream, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_STRING_BYTES) { "String exceeds $MAX_STRING_BYTES UTF-8 bytes" }
        out.writeInt(bytes.size)
        out.write(bytes)
    }

    fun writeNullableString(out: DataOutputStream, value: String?) {
        if (value == null) {
            out.writeBoolean(false)
        } else {
            out.writeBoolean(true)
            writeString(out, value)
        }
    }

    fun readString(input: DataInputStream): String {
        val len = readBoundedCount(input, MAX_STRING_BYTES, "string length")
        val buf = ByteArray(len)
        input.readFully(buf)
        return String(buf, Charsets.UTF_8)
    }

    fun readNullableString(input: DataInputStream): String? =
        if (input.readBoolean()) readString(input) else null

    fun writeNullableInt(out: DataOutputStream, value: Int?) {
        if (value == null) {
            out.writeBoolean(false)
        } else {
            out.writeBoolean(true)
            out.writeInt(value)
        }
    }

    fun readNullableInt(input: DataInputStream): Int? =
        if (input.readBoolean()) input.readInt() else null
}

internal object DeviceStoreCodec {
    const val SCHEMA_VERSION_1 = 1
    const val SCHEMA_VERSION_2 = 2
    const val CURRENT_SCHEMA_VERSION = 3

    fun encodeV1(records: List<DeviceRecordV1>): ByteArray {
        val baos = ByteArrayOutputStream()
        DataOutputStream(baos).use { out ->
            out.writeInt(records.size)
            for (d in records) {
                DeviceStoreBinaryIo.writeString(out, d.id)
                DeviceStoreBinaryIo.writeString(out, d.displayName)
                DeviceStoreBinaryIo.writeNullableString(out, d.model)
                DeviceStoreBinaryIo.writeNullableString(out, d.lastAddress)
                DeviceStoreBinaryIo.writeNullableString(out, d.adbKeyAlias)
                DeviceStoreBinaryIo.writeString(out, d.method.wireName)
                out.writeLong(d.lastSeen)
            }
        }
        return baos.toByteArray()
    }

    fun decodeV1(payload: ByteArray): List<DeviceRecordV1> = try {
        DataInputStream(ByteArrayInputStream(payload)).use { input ->
            val count = DeviceStoreBinaryIo.readBoundedCount(
                input,
                DeviceStoreBinaryIo.MAX_DEVICES,
                "device count"
            )
            List(count) {
                val id = DeviceStoreBinaryIo.readString(input)
                val displayName = DeviceStoreBinaryIo.readString(input)
                val model = DeviceStoreBinaryIo.readNullableString(input)
                val lastAddress = DeviceStoreBinaryIo.readNullableString(input)
                val adbKeyAlias = DeviceStoreBinaryIo.readNullableString(input)
                val method = LaunchMethod.fromWireName(DeviceStoreBinaryIo.readString(input))
                    ?: throw DeviceStoreMigrationException("Unknown LaunchMethod in v1 record")
                val lastSeen = input.readLong()
                DeviceRecordV1(
                    id = id,
                    displayName = displayName,
                    model = model,
                    lastAddress = lastAddress,
                    adbKeyAlias = adbKeyAlias,
                    method = method,
                    lastSeen = lastSeen
                )
            }
        }
    } catch (e: EOFException) {
        throw DeviceStoreMigrationException("Truncated DeviceStore v1 payload", e)
    }

    fun encodeV2(records: List<DeviceRecordV2>): ByteArray {
        val baos = ByteArrayOutputStream()
        DataOutputStream(baos).use { out ->
            out.writeInt(records.size)
            for (d in records) {
                DeviceStoreBinaryIo.writeString(out, d.id)
                DeviceStoreBinaryIo.writeString(out, d.displayName)
                DeviceStoreBinaryIo.writeNullableString(out, d.model)
                DeviceStoreBinaryIo.writeNullableInt(out, d.androidApi)
                DeviceStoreBinaryIo.writeNullableString(out, d.lastAddress)
                DeviceStoreBinaryIo.writeNullableString(out, d.adbKeyAlias)
                DeviceStoreBinaryIo.writeNullableString(out, d.secretRef)
                DeviceStoreBinaryIo.writeString(out, d.method.wireName)
                out.writeLong(d.lastSeen)
            }
        }
        return baos.toByteArray()
    }

    fun decodeV2(payload: ByteArray): List<DeviceRecordV2> = try {
        DataInputStream(ByteArrayInputStream(payload)).use { input ->
            val count = DeviceStoreBinaryIo.readBoundedCount(
                input,
                DeviceStoreBinaryIo.MAX_DEVICES,
                "device count"
            )
            List(count) {
                val id = DeviceStoreBinaryIo.readString(input)
                val displayName = DeviceStoreBinaryIo.readString(input)
                val model = DeviceStoreBinaryIo.readNullableString(input)
                val androidApi = DeviceStoreBinaryIo.readNullableInt(input)
                val lastAddress = DeviceStoreBinaryIo.readNullableString(input)
                val adbKeyAlias = DeviceStoreBinaryIo.readNullableString(input)
                val secretRef = DeviceStoreBinaryIo.readNullableString(input)
                val method = LaunchMethod.fromWireName(DeviceStoreBinaryIo.readString(input))
                    ?: throw DeviceStoreMigrationException("Unknown LaunchMethod in v2 record")
                val lastSeen = input.readLong()
                DeviceRecordV2(
                    id = id,
                    displayName = displayName,
                    model = model,
                    androidApi = androidApi,
                    lastAddress = lastAddress,
                    adbKeyAlias = adbKeyAlias,
                    secretRef = secretRef,
                    method = method,
                    lastSeen = lastSeen
                )
            }
        }
    } catch (e: EOFException) {
        throw DeviceStoreMigrationException("Truncated DeviceStore v2 payload", e)
    }

    fun encodeV3(devices: List<Device>): ByteArray {
        val baos = ByteArrayOutputStream()
        DataOutputStream(baos).use { out ->
            out.writeInt(devices.size)
            for (d in devices) {
                DeviceStoreBinaryIo.writeString(out, d.id)
                DeviceStoreBinaryIo.writeString(out, d.displayName)
                DeviceStoreBinaryIo.writeNullableString(out, d.model)
                DeviceStoreBinaryIo.writeNullableInt(out, d.androidApi)
                DeviceStoreBinaryIo.writeNullableString(out, d.lastAddress)
                DeviceStoreBinaryIo.writeNullableString(out, d.adbKeyAlias)
                DeviceStoreBinaryIo.writeNullableString(out, d.secretRef)
                DeviceStoreBinaryIo.writeString(out, d.method.wireName)
                out.writeLong(d.lastSeen)
                out.writeInt(d.preparedItems.size)
                for (item in d.preparedItems.sorted()) {
                    DeviceStoreBinaryIo.writeString(out, item)
                }
                DeviceStoreBinaryIo.writeNullableString(out, d.hostAppVersion)
            }
        }
        return baos.toByteArray()
    }

    fun decodeV3(payload: ByteArray): List<Device> = try {
        DataInputStream(ByteArrayInputStream(payload)).use { input ->
            val count = DeviceStoreBinaryIo.readBoundedCount(
                input,
                DeviceStoreBinaryIo.MAX_DEVICES,
                "device count"
            )
            List(count) {
                val id = DeviceStoreBinaryIo.readString(input)
                val displayName = DeviceStoreBinaryIo.readString(input)
                val model = DeviceStoreBinaryIo.readNullableString(input)
                val androidApi = DeviceStoreBinaryIo.readNullableInt(input)
                val lastAddress = DeviceStoreBinaryIo.readNullableString(input)
                val adbKeyAlias = DeviceStoreBinaryIo.readNullableString(input)
                val secretRef = DeviceStoreBinaryIo.readNullableString(input)
                val method = LaunchMethod.fromWireName(DeviceStoreBinaryIo.readString(input))
                    ?: throw KeyStoreSecurityException("Unknown LaunchMethod in DeviceStore v3")
                val lastSeen = input.readLong()
                val itemCount = DeviceStoreBinaryIo.readBoundedCount(
                    input,
                    DeviceStoreBinaryIo.MAX_PREPARED_ITEMS,
                    "preparedItems count"
                )
                val preparedItems = LinkedHashSet<String>(itemCount)
                repeat(itemCount) {
                    preparedItems.add(DeviceStoreBinaryIo.readString(input))
                }
                val hostAppVersion = DeviceStoreBinaryIo.readNullableString(input)
                Device(
                    id = id,
                    displayName = displayName,
                    model = model,
                    androidApi = androidApi,
                    lastAddress = lastAddress,
                    adbKeyAlias = adbKeyAlias,
                    secretRef = secretRef,
                    method = method,
                    lastSeen = lastSeen,
                    preparedItems = preparedItems,
                    hostAppVersion = hostAppVersion
                )
            }
        }
    } catch (e: EOFException) {
        throw KeyStoreSecurityException("Truncated DeviceStore v3 payload", e)
    } catch (e: IllegalArgumentException) {
        throw KeyStoreSecurityException("Malformed DeviceStore v3 record", e)
    }
}
