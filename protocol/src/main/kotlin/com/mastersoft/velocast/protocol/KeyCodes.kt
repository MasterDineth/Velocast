package com.mastersoft.velocast.protocol

private const val KEY_0 = 7
private const val KEY_9 = 16
private const val KEY_STAR = 17
private const val KEY_POUND = 18
private const val KEY_DPAD_UP = 19
private const val KEY_DPAD_DOWN = 20
private const val KEY_DPAD_LEFT = 21
private const val KEY_DPAD_RIGHT = 22
private const val KEY_DPAD_CENTER = 23
private const val KEY_A = 29
private const val KEY_Z = 54
private const val KEY_COMMA = 55
private const val KEY_PERIOD = 56
private const val KEY_TAB = 61
private const val KEY_SPACE = 62
private const val KEY_GRAVE = 68
private const val KEY_MINUS = 69
private const val KEY_EQUALS = 70
private const val KEY_LEFT_BRACKET = 71
private const val KEY_RIGHT_BRACKET = 72
private const val KEY_BACKSLASH = 73
private const val KEY_SEMICOLON = 74
private const val KEY_APOSTROPHE = 75
private const val KEY_SLASH = 76
private const val KEY_AT = 77
private const val KEY_PLUS = 81
private const val KEY_PAGE_UP = 92
private const val KEY_PAGE_DOWN = 93
private const val KEY_ESCAPE = 111
private const val KEY_FORWARD_DEL = 112
private const val KEY_MOVE_HOME = 122
private const val KEY_MOVE_END = 123
private const val TABLE_SIZE = 256

/**
 * The key allow-list (SEC-05). Values equal `android.view.KeyEvent.KEYCODE_*`; this module is
 * pure JVM, so they are repeated here. Keys that the viewer never offers (CALL, ENDCALL, ...)
 * are rejected.
 */
object KeyCodes {
    const val HOME = 3
    const val BACK = 4
    const val VOLUME_UP = 24
    const val VOLUME_DOWN = 25
    const val POWER = 26
    const val ENTER = 66
    const val DEL = 67
    const val APP_SWITCH = 187
    const val WAKEUP = 224

    private val allowed = BooleanArray(TABLE_SIZE).also { table ->
        val singles = intArrayOf(
            HOME, BACK, VOLUME_UP, VOLUME_DOWN, POWER, ENTER, DEL, APP_SWITCH, WAKEUP,
            KEY_STAR, KEY_POUND, KEY_DPAD_UP, KEY_DPAD_DOWN, KEY_DPAD_LEFT, KEY_DPAD_RIGHT,
            KEY_DPAD_CENTER, KEY_COMMA, KEY_PERIOD, KEY_TAB, KEY_SPACE, KEY_GRAVE, KEY_MINUS,
            KEY_EQUALS, KEY_LEFT_BRACKET, KEY_RIGHT_BRACKET, KEY_BACKSLASH, KEY_SEMICOLON,
            KEY_APOSTROPHE, KEY_SLASH, KEY_AT, KEY_PLUS, KEY_PAGE_UP, KEY_PAGE_DOWN, KEY_ESCAPE,
            KEY_FORWARD_DEL, KEY_MOVE_HOME, KEY_MOVE_END
        )
        for (code in singles) table[code] = true
        for (code in KEY_0..KEY_9) table[code] = true
        for (code in KEY_A..KEY_Z) table[code] = true
    }

    /** All allowed key codes in ascending order (for documentation and tests). */
    val allKeyCodes: List<Int> = allowed.indices.filter { allowed[it] }

    fun isAllowed(code: Int): Boolean = code in allowed.indices && allowed[code]

    /** Returns [code] or throws [ErrorCode.KEY_NOT_ALLOWED]. */
    fun require(code: Int): Int {
        if (!isAllowed(code)) fail(ErrorCode.KEY_NOT_ALLOWED, "key code $code is not allowed")
        return code
    }
}
