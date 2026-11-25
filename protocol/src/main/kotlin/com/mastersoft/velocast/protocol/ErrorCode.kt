package com.mastersoft.velocast.protocol

private const val ID_DEBUGGING_OFF = 1
private const val ID_WIRELESS_OFF_AFTER_REBOOT = 2
private const val ID_DIFFERENT_NETWORK = 3
private const val ID_AUTH_EXPIRED = 4
private const val ID_NOT_PAIRED = 5
private const val ID_SERVER_START_FAILED = 6
private const val ID_INJECTION_DENIED = 7
private const val ID_SCREEN_OFF = 8
private const val ID_LOCKED = 9
private const val ID_VERSION_MISMATCH = 10
private const val ID_LINK_LOST = 11
private const val ID_MALFORMED_MESSAGE = 100
private const val ID_UNKNOWN_MESSAGE_TYPE = 101
private const val ID_UNEXPECTED_MESSAGE = 102
private const val ID_MESSAGE_TOO_LARGE = 103
private const val ID_TRUNCATED = 104
private const val ID_BAD_MAGIC = 105
private const val ID_AUTH_FAILED = 106
private const val ID_INVALID_POINTER = 107
private const val ID_INVALID_COORDINATE = 108
private const val ID_KEY_NOT_ALLOWED = 109
private const val ID_VALUE_OUT_OF_RANGE = 110
private const val ID_INVALID_POINTER_STATE = 111
private const val ID_INVALID_TEXT = 112
private const val FIRST_PROTOCOL_ID = 100

/**
 * Error codes. Ids 1..11 are the user-facing error catalogue of `docs/product-requirements.md`
 * (same names, same order). Ids from 100 are protocol violations raised by this library.
 */
enum class ErrorCode(val id: Int) {
    DEBUGGING_OFF(ID_DEBUGGING_OFF),
    WIRELESS_OFF_AFTER_REBOOT(ID_WIRELESS_OFF_AFTER_REBOOT),
    DIFFERENT_NETWORK(ID_DIFFERENT_NETWORK),
    AUTH_EXPIRED(ID_AUTH_EXPIRED),
    NOT_PAIRED(ID_NOT_PAIRED),
    SERVER_START_FAILED(ID_SERVER_START_FAILED),
    INJECTION_DENIED(ID_INJECTION_DENIED),
    SCREEN_OFF(ID_SCREEN_OFF),
    LOCKED(ID_LOCKED),
    VERSION_MISMATCH(ID_VERSION_MISMATCH),
    LINK_LOST(ID_LINK_LOST),

    MALFORMED_MESSAGE(ID_MALFORMED_MESSAGE),
    UNKNOWN_MESSAGE_TYPE(ID_UNKNOWN_MESSAGE_TYPE),
    UNEXPECTED_MESSAGE(ID_UNEXPECTED_MESSAGE),
    MESSAGE_TOO_LARGE(ID_MESSAGE_TOO_LARGE),
    TRUNCATED(ID_TRUNCATED),
    BAD_MAGIC(ID_BAD_MAGIC),
    AUTH_FAILED(ID_AUTH_FAILED),
    INVALID_POINTER(ID_INVALID_POINTER),
    INVALID_COORDINATE(ID_INVALID_COORDINATE),
    KEY_NOT_ALLOWED(ID_KEY_NOT_ALLOWED),
    VALUE_OUT_OF_RANGE(ID_VALUE_OUT_OF_RANGE),
    INVALID_POINTER_STATE(ID_INVALID_POINTER_STATE),
    INVALID_TEXT(ID_INVALID_TEXT)
    ;

    /** True for codes that describe a malformed or forbidden message (ids from 100). */
    val isProtocolViolation: Boolean get() = id >= FIRST_PROTOCOL_ID

    companion object {
        fun fromId(id: Int): ErrorCode? = entries.firstOrNull { it.id == id }
    }
}
