package com.mastersoft.velocast.client

import com.mastersoft.velocast.protocol.InMemoryKeyStore
import com.mastersoft.velocast.protocol.JvmAesGcmEnvelopeCipher
import com.mastersoft.velocast.protocol.KeyStoreSecurityException
import com.mastersoft.velocast.protocol.SessionSecrets
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DeviceStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `persists full device metadata encrypted and reloads across store instances`() {
        val dir = tempFolder.newFolder("devices").toPath()
        val masterKey = ByteArray(32) { idx -> (idx + 7).toByte() }
        val cipher = JvmAesGcmEnvelopeCipher(masterKey)
        val keyStore = InMemoryKeyStore()

        val device = Device(
            id = "adb-6f8d1516",
            displayName = "Old Redmi Note 10",
            model = "M2101K6G",
            androidApi = 36,
            lastAddress = "192.168.1.42:37199",
            adbKeyAlias = "default_adb_key",
            method = LaunchMethod.ADB_WIFI,
            lastSeen = 1_764_332_000_000L,
            preparedItems = setOf("stay_on_while_plugged_in", "adb_allowed_connection_time"),
            hostAppVersion = "2.0.0"
        )

        val store1 = EncryptedFileDeviceStore(dir, cipher, keyStore)
        store1.upsert(device)
        val rawSecret = SessionSecrets.generate()
        val expectedSecretCopy = rawSecret.copyOf()
        val alias = store1.putDeviceSecret(device.id, rawSecret)
        rawSecret.fill(0)

        val rawBytes = Files.readAllBytes(dir.resolve(EncryptedFileDeviceStore.STORE_FILE_NAME))
        val rawText = String(rawBytes, Charsets.ISO_8859_1)
        assertFalse("Device displayName leaked in plaintext", rawText.contains("Old Redmi Note 10"))
        assertFalse("Device model leaked in plaintext", rawText.contains("M2101K6G"))
        assertFalse("Device address leaked in plaintext", rawText.contains("192.168.1.42"))
        assertFalse("hostAppVersion leaked in plaintext", rawText.contains("2.0.0"))

        val store2 = EncryptedFileDeviceStore(dir, cipher, keyStore)
        val reloaded = store2.get(device.id)
        assertNotNull(reloaded)
        assertEquals(device.copy(secretRef = alias), reloaded)
        assertEquals(reloaded, store2.findByAddress("192.168.1.42:37199"))
        assertArrayEquals(expectedSecretCopy, store2.getDeviceSecret(device.id))

        val updated = store2.updatePreparedItems(
            device.id,
            device.preparedItems + "battery_whitelist"
        )
        assertEquals(3, updated?.preparedItems?.size)
        val touched = store2.touchLastSeen(
            device.id,
            1_764_333_000_000L,
            "192.168.1.42:41223"
        )
        assertEquals("192.168.1.42:41223", touched?.lastAddress)

        assertTrue(store2.delete(device.id, deleteSecret = true))
        assertNull(store2.get(device.id))
        assertFalse(keyStore.hasSecret(alias))
    }

    @Test
    fun `rejects raw 256-bit hex secret inside secretRef`() {
        val rawHex = "a1b2c3d4".repeat(8)
        try {
            Device(
                id = "dev-1",
                displayName = "Broken Phone",
                secretRef = rawHex
            )
            fail("Expected IllegalArgumentException for raw hex secret in secretRef")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }

    @Test
    fun `detects tampered header and tampered ciphertext`() {
        val dir = tempFolder.newFolder("tamper").toPath()
        val cipher = JvmAesGcmEnvelopeCipher(ByteArray(32) { 0x11 })
        val store = EncryptedFileDeviceStore(dir, cipher, InMemoryKeyStore())
        store.upsert(Device(id = "dev-1", displayName = "Phone 1"))

        val filePath = dir.resolve(EncryptedFileDeviceStore.STORE_FILE_NAME)
        val original = Files.readAllBytes(filePath)

        val badVersion = original.copyOf()
        badVersion[5] = (badVersion[5].toInt() xor 0x01).toByte()
        Files.write(filePath, badVersion)
        assertThrowsSecurityException(dir, cipher)

        val badCiphertext = original.copyOf()
        badCiphertext[badCiphertext.size - 1] =
            (badCiphertext[badCiphertext.size - 1].toInt() xor 0xFF).toByte()
        Files.write(filePath, badCiphertext)
        assertThrowsSecurityException(dir, cipher)
    }

    @Test
    fun `migrates encrypted v1 schema to v3 and re-seals on disk`() {
        val dirV1 = tempFolder.newFolder("migrate-v1").toPath()
        val cipher = JvmAesGcmEnvelopeCipher(ByteArray(32) { 0x2A })
        val v1Records = listOf(
            DeviceRecordV1(
                id = "old-pixel",
                displayName = "Pixel 4a",
                model = "Pixel 4a",
                lastAddress = "192.168.0.15:39123",
                adbKeyAlias = "default_adb_key",
                method = LaunchMethod.ADB_WIFI,
                lastSeen = 100_000L
            )
        )
        writeEncryptedSchemaFile(
            path = dirV1.resolve(EncryptedFileDeviceStore.STORE_FILE_NAME),
            schemaVersion = DeviceStoreCodec.SCHEMA_VERSION_1,
            plaintext = DeviceStoreCodec.encodeV1(v1Records),
            cipher = cipher
        )

        val migratedFromV1 = EncryptedFileDeviceStore(dirV1, cipher, InMemoryKeyStore())
        val v1Loaded = migratedFromV1.get("old-pixel")
        assertEquals(
            Device(
                id = "old-pixel",
                displayName = "Pixel 4a",
                model = "Pixel 4a",
                androidApi = null,
                lastAddress = "192.168.0.15:39123",
                adbKeyAlias = "default_adb_key",
                secretRef = null,
                method = LaunchMethod.ADB_WIFI,
                lastSeen = 100_000L,
                preparedItems = emptySet(),
                hostAppVersion = null
            ),
            v1Loaded
        )
        val reSealedV1 = DeviceStoreEnvelopeIo.parseEnvelopeFile(
            Files.readAllBytes(dirV1.resolve(EncryptedFileDeviceStore.STORE_FILE_NAME))
        )
        assertEquals(DeviceStoreCodec.CURRENT_SCHEMA_VERSION, reSealedV1.schemaVersion)
    }

    @Test
    fun `migrates encrypted v2 schema to v3 and preserves secretRef`() {
        val cipher = JvmAesGcmEnvelopeCipher(ByteArray(32) { 0x2A })
        val dirV2 = tempFolder.newFolder("migrate-v2").toPath()
        val v2Records = listOf(
            DeviceRecordV2(
                id = "old-redmi",
                displayName = "Redmi Note 10 Pro",
                model = "M2101K6G",
                androidApi = 36,
                lastAddress = "192.168.1.99:40001",
                adbKeyAlias = "redmi_key",
                secretRef = "device_old_redmi_pairing",
                method = LaunchMethod.SHIZUKU,
                lastSeen = 200_000L
            )
        )
        writeEncryptedSchemaFile(
            path = dirV2.resolve(EncryptedFileDeviceStore.STORE_FILE_NAME),
            schemaVersion = DeviceStoreCodec.SCHEMA_VERSION_2,
            plaintext = DeviceStoreCodec.encodeV2(v2Records),
            cipher = cipher
        )

        val migratedFromV2 = EncryptedFileDeviceStore(dirV2, cipher, InMemoryKeyStore())
        val v2Loaded = migratedFromV2.get("old-redmi")
        assertEquals(36, v2Loaded?.androidApi)
        assertEquals("device_old_redmi_pairing", v2Loaded?.secretRef)
        assertEquals(LaunchMethod.SHIZUKU, v2Loaded?.method)
        assertTrue(v2Loaded?.preparedItems?.isEmpty() == true)
        assertNull(v2Loaded?.hostAppVersion)
    }

    @Test
    fun `rejects unsupported future schema version and missing migration hop`() {
        val dir = tempFolder.newFolder("bad-migration").toPath()
        val cipher = JvmAesGcmEnvelopeCipher(ByteArray(32) { 0x33 })
        val file = dir.resolve(EncryptedFileDeviceStore.STORE_FILE_NAME)

        writeEncryptedSchemaFile(
            path = file,
            schemaVersion = 99,
            plaintext = byteArrayOf(1, 2, 3),
            cipher = cipher
        )
        try {
            EncryptedFileDeviceStore(dir, cipher, InMemoryKeyStore())
            fail("Expected DeviceStoreMigrationException for schema v99")
        } catch (_: DeviceStoreMigrationException) {
            // Expected.
        }

        writeEncryptedSchemaFile(
            path = file,
            schemaVersion = DeviceStoreCodec.SCHEMA_VERSION_1,
            plaintext = DeviceStoreCodec.encodeV1(emptyList()),
            cipher = cipher
        )
        try {
            EncryptedFileDeviceStore(
                directory = dir,
                envelopeCipher = cipher,
                secretStore = InMemoryKeyStore(),
                migrations = emptyList()
            )
            fail("Expected DeviceStoreMigrationException when v1->v2 migration is missing")
        } catch (_: DeviceStoreMigrationException) {
            // Expected.
        }
    }

    private fun writeEncryptedSchemaFile(
        path: Path,
        schemaVersion: Int,
        plaintext: ByteArray,
        cipher: JvmAesGcmEnvelopeCipher
    ) {
        val aad = DeviceStoreEnvelopeIo.buildHeaderAad(schemaVersion)
        val encrypted = cipher.encrypt(aad, plaintext)
        val bytes = ByteArray(aad.size + encrypted.size)
        aad.copyInto(bytes, 0)
        encrypted.copyInto(bytes, aad.size)
        Files.write(path, bytes)
    }

    private fun assertThrowsSecurityException(dir: Path, cipher: JvmAesGcmEnvelopeCipher) {
        try {
            EncryptedFileDeviceStore(dir, cipher, InMemoryKeyStore())
            fail("Expected KeyStoreSecurityException")
        } catch (_: KeyStoreSecurityException) {
            // Expected.
        }
    }
}
