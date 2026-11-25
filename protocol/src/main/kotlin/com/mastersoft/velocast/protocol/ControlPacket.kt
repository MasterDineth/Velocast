package com.mastersoft.velocast.protocol

/**
 * A client to server control packet (16 bytes). One instance is reused for every read, so the
 * hot path does not allocate. [payloadLength] is the length of the payload that followed the
 * packet (0 for fixed-size messages); read it from [ControlReader.payload].
 */
class ControlPacket {
    var type: MessageType = MessageType.PING
    var pointer: Int = 0
    var seq: Int = 0
    var param: Int = 0
    var x: Float = 0f
    var y: Float = 0f
    var payloadLength: Int = 0

    /** The decoded TOUCH action. Only meaningful for a validated TOUCH packet. */
    fun touchAction(): TouchAction = TouchAction.entries[param]

    /** The decoded KEY action. Only meaningful for a validated KEY packet. */
    fun keyAction(): KeyAction = KeyAction.entries[pointer]

    /** The decoded ACTION. Only meaningful for a validated ACTION packet. */
    fun deviceAction(): DeviceAction = DeviceAction.entries[param - 1]

    override fun toString(): String =
        "ControlPacket(type=$type, pointer=$pointer, seq=$seq, param=$param, x=$x, y=$y, " +
            "payloadLength=$payloadLength)"
}

/** Per-type field rules for control packets, shared by [ControlReader] and [ControlWriter]. */
internal object ControlRules {
    fun validate(p: ControlPacket) {
        when (p.type) {
            MessageType.TOUCH -> touch(p)

            MessageType.KEY -> key(p)

            MessageType.CONFIG_STREAM -> configStream(p)

            MessageType.ACTION -> action(p)

            MessageType.BYE -> bye(p)

            MessageType.PING -> zeroUnused(p)

            MessageType.KEYFRAME_REQ, MessageType.PAUSE, MessageType.RESUME -> empty(p)

            MessageType.HELLO, MessageType.AUTH_PROOF, MessageType.TEXT, MessageType.CLIPBOARD ->
                zeroUnused(p)

            else -> fail(ErrorCode.UNEXPECTED_MESSAGE, "${p.type} is not a client message")
        }
    }

    fun checkPhase(type: MessageType, phase: Phase) = FrameRules.checkPhase(type, phase)

    fun limit(phase: Phase, configured: Int): Int = FrameRules.limit(phase, configured)

    private fun touch(p: ControlPacket) {
        Validators.pointerId(p.pointer)
        if (TouchAction.fromId(p.param) == null) {
            fail(ErrorCode.VALUE_OUT_OF_RANGE, "unknown touch action")
        }
        Validators.coordinate(p.x)
        Validators.coordinate(p.y)
    }

    private fun key(p: ControlPacket) {
        if (KeyAction.fromId(p.pointer) == null) {
            fail(ErrorCode.VALUE_OUT_OF_RANGE, "unknown key action")
        }
        KeyCodes.require(p.param)
        zeroUnused(p, pointer = false)
    }

    private fun configStream(p: ControlPacket) {
        zeroUnused(p, coordinates = false)
        Validators.bitrateBps(p.param)
        Validators.fps(p.x)
        Validators.scale(p.y)
    }

    private fun action(p: ControlPacket) {
        zeroUnused(p)
        if (DeviceAction.fromId(p.param) == null) {
            fail(ErrorCode.VALUE_OUT_OF_RANGE, "unknown action")
        }
    }

    private fun bye(p: ControlPacket) {
        zeroUnused(p)
        if (p.param != 0 && ErrorCode.fromId(p.param) == null) {
            fail(ErrorCode.VALUE_OUT_OF_RANGE, "unknown BYE code")
        }
    }

    private fun empty(p: ControlPacket) {
        zeroUnused(p)
        if (p.param != 0) fail(ErrorCode.MALFORMED_MESSAGE, "${p.type} param must be zero")
    }

    /** Enforces canonical zero encoding on unused pointer and/or coordinate fields. */
    private fun zeroUnused(p: ControlPacket, pointer: Boolean = true, coordinates: Boolean = true) {
        if (pointer && p.pointer != 0) {
            fail(ErrorCode.MALFORMED_MESSAGE, "${p.type} pointer byte must be zero")
        }
        if (coordinates && (p.x.toRawBits() != 0 || p.y.toRawBits() != 0)) {
            fail(ErrorCode.MALFORMED_MESSAGE, "${p.type} x and y must be zero")
        }
    }
}
