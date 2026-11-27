package com.mastersoft.velocast.client

import com.mastersoft.velocast.protocol.ErrorCode
import com.mastersoft.velocast.protocol.ErrorMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionErrorTest {

    @Test
    fun `maps every required ErrorCode to expected SessionError and causeKey`() {
        val cases = listOf(
            ErrorCode.DEBUGGING_OFF to SessionError.DebuggingOff("usb off"),
            ErrorCode.DIFFERENT_NETWORK to SessionError.DifferentNetwork("subnet"),
            ErrorCode.AUTH_EXPIRED to SessionError.AuthExpired("7 days"),
            ErrorCode.SERVER_START_FAILED to SessionError.ServerStartFailed("port busy"),
            ErrorCode.INJECTION_DENIED to SessionError.InjectionDenied("xiaomi"),
            ErrorCode.SCREEN_OFF to SessionError.ScreenOff("panel off"),
            ErrorCode.LOCKED to SessionError.Locked("keyguard"),
            ErrorCode.VERSION_MISMATCH to SessionError.VersionMismatch("v1"),
            ErrorCode.LINK_LOST to SessionError.LinkLost("timeout")
        )

        for ((code, expected) in cases) {
            val actual = SessionError.fromErrorCode(code, expected.detail)
            assertEquals(expected, actual)
            assertEquals(code, actual.code)
            assertTrue(actual.causeKey.startsWith("error_"))
            assertEquals(code == ErrorCode.LINK_LOST, actual.isRetryable)
        }
    }

    @Test
    fun `maps protocol ErrorMessage and normalizes blank detail`() {
        val msg = ErrorMessage(ErrorCode.INJECTION_DENIED, fatal = false, detail = "   ")
        val error = SessionError.fromErrorMessage(msg)

        assertTrue(error is SessionError.InjectionDenied)
        assertEquals(ErrorCode.INJECTION_DENIED, error.code)
        assertNull(error.detail)
        assertFalse(error.isRetryable)
    }

    @Test
    fun `maps protocol violations to ProtocolViolation`() {
        val error = SessionError.fromErrorCode(ErrorCode.AUTH_FAILED, "bad proof")

        assertTrue(error is SessionError.ProtocolViolation)
        assertEquals(ErrorCode.AUTH_FAILED, error.code)
        assertEquals("error_protocol_auth_failed", error.causeKey)
        assertEquals("bad proof", error.detail)
        assertFalse(error.isRetryable)
    }
}
