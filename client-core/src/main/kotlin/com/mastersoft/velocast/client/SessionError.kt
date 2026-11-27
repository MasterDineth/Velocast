package com.mastersoft.velocast.client

import com.mastersoft.velocast.protocol.ErrorCode
import com.mastersoft.velocast.protocol.ErrorMessage

/**
 * Typed client session errors mapped from protocol [ErrorCode] values and launcher/transport
 * failures. Every error exposes a stable [causeKey] for UI string lookup and optional [detail].
 */
sealed class SessionError(val code: ErrorCode, val causeKey: String, val detail: String? = null) {
    val isRetryable: Boolean
        get() = code == ErrorCode.LINK_LOST

    class DebuggingOff(detail: String? = null) :
        SessionError(ErrorCode.DEBUGGING_OFF, "error_debugging_off", detail) {
        override fun equals(other: Any?): Boolean = other is DebuggingOff && detail == other.detail
        override fun hashCode(): Int = 31 * code.hashCode() + (detail?.hashCode() ?: 0)
        override fun toString(): String = "DebuggingOff(detail=$detail)"
    }

    class WirelessOffAfterReboot(detail: String? = null) :
        SessionError(
            ErrorCode.WIRELESS_OFF_AFTER_REBOOT,
            "error_wireless_off_after_reboot",
            detail
        ) {
        override fun equals(other: Any?): Boolean =
            other is WirelessOffAfterReboot && detail == other.detail
        override fun hashCode(): Int = 31 * code.hashCode() + (detail?.hashCode() ?: 0)
        override fun toString(): String = "WirelessOffAfterReboot(detail=$detail)"
    }

    class DifferentNetwork(detail: String? = null) :
        SessionError(ErrorCode.DIFFERENT_NETWORK, "error_different_network", detail) {
        override fun equals(other: Any?): Boolean =
            other is DifferentNetwork && detail == other.detail
        override fun hashCode(): Int = 31 * code.hashCode() + (detail?.hashCode() ?: 0)
        override fun toString(): String = "DifferentNetwork(detail=$detail)"
    }

    class AuthExpired(detail: String? = null) :
        SessionError(ErrorCode.AUTH_EXPIRED, "error_auth_expired", detail) {
        override fun equals(other: Any?): Boolean = other is AuthExpired && detail == other.detail
        override fun hashCode(): Int = 31 * code.hashCode() + (detail?.hashCode() ?: 0)
        override fun toString(): String = "AuthExpired(detail=$detail)"
    }

    class NotPaired(detail: String? = null) :
        SessionError(ErrorCode.NOT_PAIRED, "error_not_paired", detail) {
        override fun equals(other: Any?): Boolean = other is NotPaired && detail == other.detail
        override fun hashCode(): Int = 31 * code.hashCode() + (detail?.hashCode() ?: 0)
        override fun toString(): String = "NotPaired(detail=$detail)"
    }

    class ServerStartFailed(detail: String? = null) :
        SessionError(ErrorCode.SERVER_START_FAILED, "error_server_start_failed", detail) {
        override fun equals(other: Any?): Boolean =
            other is ServerStartFailed && detail == other.detail
        override fun hashCode(): Int = 31 * code.hashCode() + (detail?.hashCode() ?: 0)
        override fun toString(): String = "ServerStartFailed(detail=$detail)"
    }

    class InjectionDenied(detail: String? = null) :
        SessionError(ErrorCode.INJECTION_DENIED, "error_injection_denied", detail) {
        override fun equals(other: Any?): Boolean =
            other is InjectionDenied && detail == other.detail
        override fun hashCode(): Int = 31 * code.hashCode() + (detail?.hashCode() ?: 0)
        override fun toString(): String = "InjectionDenied(detail=$detail)"
    }

    class ScreenOff(detail: String? = null) :
        SessionError(ErrorCode.SCREEN_OFF, "error_screen_off", detail) {
        override fun equals(other: Any?): Boolean = other is ScreenOff && detail == other.detail
        override fun hashCode(): Int = 31 * code.hashCode() + (detail?.hashCode() ?: 0)
        override fun toString(): String = "ScreenOff(detail=$detail)"
    }

    class Locked(detail: String? = null) :
        SessionError(ErrorCode.LOCKED, "error_locked", detail) {
        override fun equals(other: Any?): Boolean = other is Locked && detail == other.detail
        override fun hashCode(): Int = 31 * code.hashCode() + (detail?.hashCode() ?: 0)
        override fun toString(): String = "Locked(detail=$detail)"
    }

    class VersionMismatch(detail: String? = null) :
        SessionError(ErrorCode.VERSION_MISMATCH, "error_version_mismatch", detail) {
        override fun equals(other: Any?): Boolean =
            other is VersionMismatch && detail == other.detail
        override fun hashCode(): Int = 31 * code.hashCode() + (detail?.hashCode() ?: 0)
        override fun toString(): String = "VersionMismatch(detail=$detail)"
    }

    class LinkLost(detail: String? = null) :
        SessionError(ErrorCode.LINK_LOST, "error_link_lost", detail) {
        override fun equals(other: Any?): Boolean = other is LinkLost && detail == other.detail
        override fun hashCode(): Int = 31 * code.hashCode() + (detail?.hashCode() ?: 0)
        override fun toString(): String = "LinkLost(detail=$detail)"
    }

    class ProtocolViolation(code: ErrorCode, detail: String? = null) :
        SessionError(code, "error_protocol_${code.name.lowercase()}", detail) {
        init {
            require(code.isProtocolViolation) {
                "ProtocolViolation requires a protocol error code, got $code"
            }
        }

        override fun equals(other: Any?): Boolean =
            other is ProtocolViolation && code == other.code && detail == other.detail

        override fun hashCode(): Int = 31 * code.hashCode() + (detail?.hashCode() ?: 0)
        override fun toString(): String = "ProtocolViolation(code=$code, detail=$detail)"
    }

    companion object {
        fun fromErrorCode(code: ErrorCode, detail: String? = null): SessionError {
            val normalized = detail?.takeIf { it.isNotBlank() }
            return when (code) {
                ErrorCode.DEBUGGING_OFF -> DebuggingOff(normalized)
                ErrorCode.WIRELESS_OFF_AFTER_REBOOT -> WirelessOffAfterReboot(normalized)
                ErrorCode.DIFFERENT_NETWORK -> DifferentNetwork(normalized)
                ErrorCode.AUTH_EXPIRED -> AuthExpired(normalized)
                ErrorCode.NOT_PAIRED -> NotPaired(normalized)
                ErrorCode.SERVER_START_FAILED -> ServerStartFailed(normalized)
                ErrorCode.INJECTION_DENIED -> InjectionDenied(normalized)
                ErrorCode.SCREEN_OFF -> ScreenOff(normalized)
                ErrorCode.LOCKED -> Locked(normalized)
                ErrorCode.VERSION_MISMATCH -> VersionMismatch(normalized)
                ErrorCode.LINK_LOST -> LinkLost(normalized)
                else -> ProtocolViolation(code, normalized)
            }
        }

        fun fromErrorMessage(message: ErrorMessage): SessionError =
            fromErrorCode(message.code, message.detail)
    }
}

/**
 * Thrown by [ServerLauncher] or handshake/session steps when a typed [SessionError] occurs.
 */
class SessionErrorException(val error: SessionError, cause: Throwable? = null) :
    Exception(error.detail ?: error.code.name, cause)
