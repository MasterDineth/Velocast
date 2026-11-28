package com.mastersoft.velocast.client

import android.content.Context
import com.mastersoft.velocast.protocol.EnvelopeCipher
import com.mastersoft.velocast.protocol.InMemoryKeyStore
import com.mastersoft.velocast.protocol.KeyStore
import com.mastersoft.velocast.protocol.KeyStoreSecurityException
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.GeneralSecurityException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Primary launch method associated with a saved [Device]. */
enum class LaunchMethod(val wireName: String) {
    ADB_WIFI("adb_wifi"),
    ADB_USB("adb_usb"),
    SHIZUKU("shizuku"),
    ROOT("root")
    ;

    companion object {
        fun fromWireName(raw: String): LaunchMethod? = entries.firstOrNull { it.wireName == raw }
    }
}

/**
 * Saved target device metadata persisted by [DeviceStore] (`S07B`).
 *
 * Raw secrets are never stored in [Device]; [secretRef] holds only the Keystore alias under
 * which the 256-bit secret is stored in [KeyStore].
 */
data class Device(
    val id: String,
    val displayName: String,
    val model: String? = null,
    val androidApi: Int? = null,
    val lastAddress: String? = null,
    val adbKeyAlias: String? = null,
    val secretRef: String? = null,
    val method: LaunchMethod = LaunchMethod.ADB_WIFI,
    val lastSeen: Long = 0L,
    val preparedItems: Set<String> = emptySet(),
    val hostAppVersion: String? = null
) {
    init {
        require(id.isNotBlank()) { "Device id must not be blank" }
        require(displayName.isNotBlank()) { "Device displayName must not be blank" }
        require(androidApi == null || androidApi > 0) {
            "androidApi must be positive, got $androidApi"
        }
        require(lastSeen >= 0L) { "lastSeen must be >= 0, got $lastSeen" }
        secretRef?.let { validateSecretRef(it) }
    }

    private companion object {
        const val MAX_SECRET_REF_LEN = 64
        val SECRET_REF_REGEX = Regex("^[A-Za-z0-9._-]{1,$MAX_SECRET_REF_LEN}$")
        val RAW_HEX_SECRET_REGEX = Regex("^[0-9a-fA-F]{64}$")

        fun validateSecretRef(ref: String) {
            require(!RAW_HEX_SECRET_REGEX.matches(ref)) {
                "Device.secretRef must be a KeyStore alias, not a raw 256-bit hex secret"
            }
            require(SECRET_REF_REGEX.matches(ref) && ref != "." && ref != "..") {
                "Invalid KeyStore secretRef alias '$ref'"
            }
        }
    }
}

/** Raised when migrating a persisted [DeviceStore] schema fails or encounters an unknown version. */
class DeviceStoreMigrationException(message: String, cause: Throwable? = null) :
    IOException(message, cause)

/** Deterministic payload transformation from schema [fromVersion] to [toVersion]. */
interface DeviceStoreMigration {
    val fromVersion: Int
    val toVersion: Int

    @Throws(DeviceStoreMigrationException::class)
    fun migrate(payload: ByteArray): ByteArray
}

/**
 * Persistent encrypted store for saved [Device] records (`S07B`).
 */
interface DeviceStore {
    val devices: StateFlow<List<Device>>

    fun list(): List<Device>

    fun get(id: String): Device?

    fun findByAddress(address: String): Device?

    fun upsert(device: Device)

    fun updatePreparedItems(id: String, preparedItems: Set<String>): Device?

    fun touchLastSeen(id: String, timestampMs: Long, lastAddress: String? = null): Device?

    fun delete(id: String, deleteSecret: Boolean = true): Boolean

    fun clear(deleteSecrets: Boolean = true)

    fun putDeviceSecret(deviceId: String, secret: ByteArray): String

    fun getDeviceSecret(deviceId: String): ByteArray?
}

/**
 * File-backed encrypted [DeviceStore] storing `devices.vcd` sealed with an AEAD [EnvelopeCipher]
 * (backed by `AndroidKeyStore` on Android) under `Context.noBackupFilesDir`.
 *
 * Binds `"VLCD" || u16be(schemaVersion)` as AEAD associated data and automatically migrates and
 * re-seals older schemas on load. Secrets themselves are stored strictly through [secretStore].
 */
class EncryptedFileDeviceStore(
    private val directory: Path,
    private val envelopeCipher: EnvelopeCipher,
    private val secretStore: KeyStore? = null,
    private val migrations: List<DeviceStoreMigration> = DeviceStoreMigrations.defaultMigrations
) : DeviceStore {
    constructor(
        context: Context,
        subDirectory: String = DEFAULT_SUBDIRECTORY,
        masterKeyAlias: String = DEFAULT_MASTER_KEY_ALIAS,
        secretStore: KeyStore = AndroidKeyStoreSecretStore(context),
        migrations: List<DeviceStoreMigration> = DeviceStoreMigrations.defaultMigrations
    ) : this(
        directory = context.applicationContext.noBackupFilesDir.toPath().resolve(subDirectory),
        envelopeCipher = AndroidKeyStoreEnvelopeCipher(masterKeyAlias),
        secretStore = secretStore,
        migrations = migrations
    )

    private val lock = Any()
    private val storeFile: Path = directory.resolve(STORE_FILE_NAME)
    private val entries = LinkedHashMap<String, Device>()
    private val mutableDevices = MutableStateFlow<List<Device>>(emptyList())

    override val devices: StateFlow<List<Device>> = mutableDevices.asStateFlow()

    init {
        loadDeviceStoreFromDisk(
            storeFile = storeFile,
            envelopeCipher = envelopeCipher,
            migrations = migrations,
            entries = entries,
            directory = directory
        )
        mutableDevices.value = entries.values.sortedDevices()
    }

    override fun list(): List<Device> = synchronized(lock) { entries.values.sortedDevices() }

    override fun get(id: String): Device? = synchronized(lock) { entries[id] }

    override fun findByAddress(address: String): Device? {
        val normalized = address.trim()
        return if (normalized.isEmpty()) {
            null
        } else {
            synchronized(lock) {
                entries.values.firstOrNull { it.lastAddress == normalized }
            }
        }
    }

    override fun upsert(device: Device) {
        synchronized(lock) {
            entries[device.id] = device
            persistDeviceStoreToDisk(directory, storeFile, envelopeCipher, entries.values.toList())
            mutableDevices.value = entries.values.sortedDevices()
        }
    }

    override fun updatePreparedItems(id: String, preparedItems: Set<String>): Device? =
        synchronized(lock) {
            val existing = entries[id] ?: return@synchronized null
            val updated = existing.copy(preparedItems = preparedItems.toSet())
            entries[id] = updated
            persistDeviceStoreToDisk(directory, storeFile, envelopeCipher, entries.values.toList())
            mutableDevices.value = entries.values.sortedDevices()
            updated
        }

    override fun touchLastSeen(id: String, timestampMs: Long, lastAddress: String?): Device? =
        synchronized(lock) {
            val existing = entries[id] ?: return@synchronized null
            val updated = existing.copy(
                lastSeen = timestampMs,
                lastAddress = lastAddress ?: existing.lastAddress
            )
            entries[id] = updated
            persistDeviceStoreToDisk(directory, storeFile, envelopeCipher, entries.values.toList())
            mutableDevices.value = entries.values.sortedDevices()
            updated
        }

    override fun delete(id: String, deleteSecret: Boolean): Boolean = synchronized(lock) {
        val removed = entries.remove(id) ?: return@synchronized false
        if (deleteSecret) {
            removed.secretRef?.let { secretStore?.deleteSecret(it) }
        }
        persistDeviceStoreToDisk(directory, storeFile, envelopeCipher, entries.values.toList())
        mutableDevices.value = entries.values.sortedDevices()
        true
    }

    override fun clear(deleteSecrets: Boolean) {
        synchronized(lock) {
            if (deleteSecrets) {
                for (device in entries.values) {
                    device.secretRef?.let { secretStore?.deleteSecret(it) }
                }
            }
            entries.clear()
            Files.deleteIfExists(storeFile)
            mutableDevices.value = emptyList()
        }
    }

    override fun putDeviceSecret(deviceId: String, secret: ByteArray): String {
        val store = checkNotNull(secretStore) {
            "DeviceStore requires a KeyStore to store device secrets"
        }
        return synchronized(lock) {
            val existing = requireNotNull(entries[deviceId]) {
                "Cannot store secret for unknown device '$deviceId'"
            }
            val alias = existing.secretRef ?: DeviceStoreEnvelopeIo.buildSecretAlias(deviceId)
            store.putSecret(alias, secret)
            if (existing.secretRef != alias) {
                entries[deviceId] = existing.copy(secretRef = alias)
                persistDeviceStoreToDisk(
                    directory,
                    storeFile,
                    envelopeCipher,
                    entries.values.toList()
                )
                mutableDevices.value = entries.values.sortedDevices()
            }
            alias
        }
    }

    override fun getDeviceSecret(deviceId: String): ByteArray? = synchronized(lock) {
        val alias = entries[deviceId]?.secretRef ?: return@synchronized null
        val store = checkNotNull(secretStore) {
            "DeviceStore requires a KeyStore to load device secrets"
        }
        store.getSecret(alias)
    }

    companion object {
        const val DEFAULT_SUBDIRECTORY = "velocast_devices"
        const val DEFAULT_MASTER_KEY_ALIAS = "com.mastersoft.velocast.viewer.device_store_key.v1"
        const val STORE_FILE_NAME = "devices.vcd"
    }
}

private fun Collection<Device>.sortedDevices(): List<Device> =
    sortedWith(compareByDescending<Device> { it.lastSeen }.thenBy { it.displayName })

private fun loadDeviceStoreFromDisk(
    storeFile: Path,
    envelopeCipher: EnvelopeCipher,
    migrations: List<DeviceStoreMigration>,
    entries: MutableMap<String, Device>,
    directory: Path
) {
    if (!Files.isRegularFile(storeFile)) return
    val raw = Files.readAllBytes(storeFile)
    val parsed = DeviceStoreEnvelopeIo.parseEnvelopeFile(raw)
    val aad = DeviceStoreEnvelopeIo.buildHeaderAad(parsed.schemaVersion)
    val decrypted = try {
        envelopeCipher.decrypt(aad, parsed.ciphertext)
    } catch (e: GeneralSecurityException) {
        throw KeyStoreSecurityException("Failed authenticating DeviceStore file", e)
    }
    val latestPayload = try {
        if (parsed.schemaVersion == DeviceStoreCodec.CURRENT_SCHEMA_VERSION) {
            decrypted
        } else {
            DeviceStoreMigrations.migrateToLatest(
                fromVersion = parsed.schemaVersion,
                payload = decrypted,
                migrations = migrations
            )
        }
    } finally {
        if (parsed.schemaVersion != DeviceStoreCodec.CURRENT_SCHEMA_VERSION) {
            decrypted.fill(0)
        }
    }
    val loaded = try {
        DeviceStoreCodec.decodeV3(latestPayload)
    } finally {
        latestPayload.fill(0)
    }
    entries.clear()
    for (device in loaded) {
        entries[device.id] = device
    }
    if (parsed.schemaVersion != DeviceStoreCodec.CURRENT_SCHEMA_VERSION) {
        persistDeviceStoreToDisk(directory, storeFile, envelopeCipher, entries.values.toList())
    }
}

private fun persistDeviceStoreToDisk(
    directory: Path,
    storeFile: Path,
    envelopeCipher: EnvelopeCipher,
    devices: List<Device>
) {
    val payload = DeviceStoreCodec.encodeV3(devices)
    val aad = DeviceStoreEnvelopeIo.buildHeaderAad(DeviceStoreCodec.CURRENT_SCHEMA_VERSION)
    val ciphertext = try {
        envelopeCipher.encrypt(aad, payload)
    } catch (e: GeneralSecurityException) {
        throw KeyStoreSecurityException("Failed encrypting DeviceStore file", e)
    } finally {
        payload.fill(0)
    }
    val fileBytes = ByteArray(aad.size + ciphertext.size)
    aad.copyInto(fileBytes, 0)
    ciphertext.copyInto(fileBytes, aad.size)
    DeviceStoreEnvelopeIo.writeAtomically(directory, storeFile, fileBytes)
}

/** In-memory [DeviceStore] implementation for unit testing. */
class InMemoryDeviceStore(private val secretStore: KeyStore = InMemoryKeyStore()) : DeviceStore {
    private val lock = Any()
    private val entries = LinkedHashMap<String, Device>()
    private val mutableDevices = MutableStateFlow<List<Device>>(emptyList())

    override val devices: StateFlow<List<Device>> = mutableDevices.asStateFlow()

    override fun list(): List<Device> = synchronized(lock) { entries.values.sortedDevices() }

    override fun get(id: String): Device? = synchronized(lock) { entries[id] }

    override fun findByAddress(address: String): Device? {
        val normalized = address.trim()
        return if (normalized.isEmpty()) {
            null
        } else {
            synchronized(lock) {
                entries.values.firstOrNull { it.lastAddress == normalized }
            }
        }
    }

    override fun upsert(device: Device) {
        synchronized(lock) {
            entries[device.id] = device
            mutableDevices.value = entries.values.sortedDevices()
        }
    }

    override fun updatePreparedItems(id: String, preparedItems: Set<String>): Device? =
        synchronized(lock) {
            val existing = entries[id] ?: return@synchronized null
            val updated = existing.copy(preparedItems = preparedItems.toSet())
            entries[id] = updated
            mutableDevices.value = entries.values.sortedDevices()
            updated
        }

    override fun touchLastSeen(id: String, timestampMs: Long, lastAddress: String?): Device? =
        synchronized(lock) {
            val existing = entries[id] ?: return@synchronized null
            val updated = existing.copy(
                lastSeen = timestampMs,
                lastAddress = lastAddress ?: existing.lastAddress
            )
            entries[id] = updated
            mutableDevices.value = entries.values.sortedDevices()
            updated
        }

    override fun delete(id: String, deleteSecret: Boolean): Boolean = synchronized(lock) {
        val removed = entries.remove(id) ?: return@synchronized false
        if (deleteSecret) {
            removed.secretRef?.let { secretStore.deleteSecret(it) }
        }
        mutableDevices.value = entries.values.sortedDevices()
        true
    }

    override fun clear(deleteSecrets: Boolean) {
        synchronized(lock) {
            if (deleteSecrets) {
                for (device in entries.values) {
                    device.secretRef?.let { secretStore.deleteSecret(it) }
                }
            }
            entries.clear()
            mutableDevices.value = emptyList()
        }
    }

    override fun putDeviceSecret(deviceId: String, secret: ByteArray): String = synchronized(lock) {
        val existing = requireNotNull(entries[deviceId]) {
            "Cannot store secret for unknown device '$deviceId'"
        }
        val alias = existing.secretRef ?: DeviceStoreEnvelopeIo.buildSecretAlias(deviceId)
        secretStore.putSecret(alias, secret)
        if (existing.secretRef != alias) {
            entries[deviceId] = existing.copy(secretRef = alias)
            mutableDevices.value = entries.values.sortedDevices()
        }
        alias
    }

    override fun getDeviceSecret(deviceId: String): ByteArray? = synchronized(lock) {
        val alias = entries[deviceId]?.secretRef ?: return@synchronized null
        secretStore.getSecret(alias)
    }
}
