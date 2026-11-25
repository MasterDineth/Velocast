package com.mastersoft.velocast.protocol

/** Touch actions carried in `param` of a TOUCH packet. Ids are the Android MotionEvent values. */
enum class TouchAction(val id: Int) {
    DOWN(TOUCH_DOWN),
    UP(TOUCH_UP),
    MOVE(TOUCH_MOVE),
    CANCEL(TOUCH_CANCEL)
    ;

    companion object {
        fun fromId(id: Int): TouchAction? = entries.getOrNull(id)
    }
}

/** Key actions carried in the `pointer` byte of a KEY packet. */
enum class KeyAction(val id: Int) {
    DOWN(KEY_DOWN),
    UP(KEY_UP)
    ;

    companion object {
        fun fromId(id: Int): KeyAction? = entries.getOrNull(id)
    }
}

/** Device-level actions carried in `param` of an ACTION packet. Ids start at 1. */
enum class DeviceAction(val id: Int) {
    EXPAND_SHADE(ACTION_EXPAND_SHADE),
    COLLAPSE_SHADE(ACTION_COLLAPSE_SHADE),
    ROTATE(ACTION_ROTATE),
    DISPLAY_POWER_ON(ACTION_DISPLAY_POWER_ON),
    DISPLAY_POWER_OFF(ACTION_DISPLAY_POWER_OFF),
    WAKE(ACTION_WAKE),
    BACK(ACTION_BACK),
    HOME(ACTION_HOME),
    RECENTS(ACTION_RECENTS)
    ;

    companion object {
        fun fromId(id: Int): DeviceAction? = entries.getOrNull(id - 1)
    }
}

/** Video codecs announced in CONFIG. */
enum class Codec(val id: Int) {
    H264(CODEC_H264),
    H265(CODEC_H265)
    ;

    companion object {
        fun fromId(id: Int): Codec? = entries.getOrNull(id - 1)
    }
}

/** Kinds of STATUS message. See `docs/protocol.md` for the value of each kind. */
enum class StatusKind(val id: Int) {
    DISPLAY_POWER(STATUS_DISPLAY_POWER),
    SCREEN_LOCKED(STATUS_SCREEN_LOCKED),
    ROTATION(STATUS_ROTATION),
    PAUSED(STATUS_PAUSED),
    BITRATE(STATUS_BITRATE),
    FPS(STATUS_FPS),
    DROPPED_FRAMES(STATUS_DROPPED_FRAMES),
    DEGRADED(STATUS_DEGRADED)
    ;

    /** True when [value] is legal for this kind. */
    fun accepts(value: Int): Boolean = when (this) {
        DISPLAY_POWER, SCREEN_LOCKED, PAUSED -> value == 0 || value == 1
        ROTATION -> value in 0..ProtocolV2.MAX_ROTATION
        BITRATE, FPS, DROPPED_FRAMES, DEGRADED -> value >= 0
    }

    companion object {
        fun fromId(id: Int): StatusKind? = entries.getOrNull(id - 1)
    }
}

/** How the server was started. Part of the device info in SERVER_HELLO. */
enum class LaunchMode(val id: Int) {
    UNKNOWN(LAUNCH_UNKNOWN),
    SHELL(LAUNCH_SHELL),
    ROOT(LAUNCH_ROOT)
    ;

    companion object {
        fun fromId(id: Int): LaunchMode? = entries.getOrNull(id)
    }
}

private const val TOUCH_DOWN = 0
private const val TOUCH_UP = 1
private const val TOUCH_MOVE = 2
private const val TOUCH_CANCEL = 3
private const val KEY_DOWN = 0
private const val KEY_UP = 1
private const val ACTION_EXPAND_SHADE = 1
private const val ACTION_COLLAPSE_SHADE = 2
private const val ACTION_ROTATE = 3
private const val ACTION_DISPLAY_POWER_ON = 4
private const val ACTION_DISPLAY_POWER_OFF = 5
private const val ACTION_WAKE = 6
private const val ACTION_BACK = 7
private const val ACTION_HOME = 8
private const val ACTION_RECENTS = 9
private const val CODEC_H264 = 1
private const val CODEC_H265 = 2
private const val STATUS_DISPLAY_POWER = 1
private const val STATUS_SCREEN_LOCKED = 2
private const val STATUS_ROTATION = 3
private const val STATUS_PAUSED = 4
private const val STATUS_BITRATE = 5
private const val STATUS_FPS = 6
private const val STATUS_DROPPED_FRAMES = 7
private const val STATUS_DEGRADED = 8
private const val LAUNCH_UNKNOWN = 0
private const val LAUNCH_SHELL = 1
private const val LAUNCH_ROOT = 2
