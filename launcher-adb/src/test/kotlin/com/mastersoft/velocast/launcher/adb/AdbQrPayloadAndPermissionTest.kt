package com.mastersoft.velocast.launcher.adb

import com.mastersoft.velocast.protocol.SecretRedactor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AdbQrPayloadAndPermissionTest {

    @Test
    fun `generated QR payload follows standard AOSP WIFI ADB format and round-trips`() {
        val scrubber = AdbSecretScrubber()
        val payload = AdbQrPayload.generate(scrubber = scrubber)
        val encoded = payload.encode()

        assertTrue(encoded.startsWith("WIFI:T:ADB;S:studio-"))
        assertTrue(encoded.endsWith(";;"))
        assertEquals(payload, AdbQrPayload.parse(encoded))
        assertFalse(payload.toString().contains(payload.password))
        assertEquals(
            "qr=${SecretRedactor.REDACTED_MARKER}",
            scrubber.redact("qr=${payload.password}")
        )
    }

    @Test
    fun `QR payload escapes and unescapes Wi-Fi special characters`() {
        val trickyService = "studio-a;b:c,d\"e\\f"
        val trickyPassword = "p;w:1,2\"3\\456"
        val original = AdbQrPayload(trickyService, trickyPassword)
        val wire = original.encode()

        assertEquals(
            "WIFI:T:ADB;S:studio-a\\;b\\:c\\,d\\\"e\\\\f;P:p\\;w\\:1\\,2\\\"3\\\\456;;",
            wire
        )
        val parsed = AdbQrPayload.parse(wire)
        assertEquals(trickyService, parsed.serviceName)
        assertEquals(trickyPassword, parsed.password)
        assertEquals(original, parsed)
        assertNotEquals(original, AdbQrPayload(trickyService, "different-pw"))
    }

    @Test
    fun `QR payload rejects malformed or non-ADB Wi-Fi strings`() {
        val invalidInputs = listOf(
            "",
            "WIFI:T:WPA;S:HomeWifi;P:hunter2;;",
            "WIFI:T:ADB;S:studio-only;;",
            "WIFI:T:ADB;P:only-password;;",
            "WIFI:T:ADB;S:unterminated;P:123456;",
            "WIFI:T:ADB;S:bad\\;P:123456;;"
        )
        for (input in invalidInputs) {
            try {
                AdbQrPayload.parse(input)
                fail("Expected IllegalArgumentException for '$input'")
            } catch (_: IllegalArgumentException) {
                // Expected.
            }
        }
    }

    @Test
    fun `LocalNetworkPermissionGate skips permission check before targetSdk 37`() {
        var checkInvoked = false
        for (deviceSdk in listOf(26, 30, 34, 36, 37, 38)) {
            val status = LocalNetworkPermissionGate.evaluate(
                deviceSdkInt = deviceSdk,
                targetSdkInt = 36,
                isPermissionGranted = {
                    checkInvoked = true
                    false
                }
            )
            assertEquals(
                LocalNetworkPermissionStatus.IMPLICITLY_GRANTED_PRE_API_37,
                status
            )
        }
        assertFalse("Permission check must not run when targetSdk < 37", checkInvoked)
    }

    @Test
    fun `LocalNetworkPermissionGate enforces permission only on API 37+ target and OS`() {
        assertEquals(
            LocalNetworkPermissionStatus.IMPLICITLY_GRANTED_PRE_API_37,
            LocalNetworkPermissionGate.evaluate(
                deviceSdkInt = 36,
                targetSdkInt = 37,
                isPermissionGranted = { false }
            )
        )
        assertEquals(
            LocalNetworkPermissionStatus.REQUIRED,
            LocalNetworkPermissionGate.evaluate(
                deviceSdkInt = 37,
                targetSdkInt = 37,
                isPermissionGranted = { false }
            )
        )
        assertEquals(
            LocalNetworkPermissionStatus.GRANTED,
            LocalNetworkPermissionGate.evaluate(
                deviceSdkInt = 37,
                targetSdkInt = 37,
                isPermissionGranted = { true }
            )
        )
    }
}
