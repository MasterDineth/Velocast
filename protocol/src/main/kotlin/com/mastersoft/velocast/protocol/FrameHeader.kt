package com.mastersoft.velocast.protocol

/** Flag bits of the server to client frame header. Bits 4..7 are reserved and must be zero. */
object FrameFlags {
    /** The VIDEO frame is a sync (IDR) frame. */
    const val KEYFRAME = 0x01

    /** The frame carries codec configuration (always together with type CONFIG). */
    const val CONFIG = 0x02

    /** The decoder must be flushed and restarted at this frame. */
    const val RESET = 0x04

    /** The frame is an ERROR message (always together with type ERROR). */
    const val ERROR = 0x08

    const val ALL = KEYFRAME or CONFIG or RESET or ERROR
}

/**
 * Header of a server to client frame (24 bytes). One instance is reused for every frame, so the
 * hot path does not allocate. [length] is the payload length in bytes.
 */
class FrameHeader {
    var type: MessageType = MessageType.VIDEO
    var flags: Int = 0
    var seq: Int = 0
    var length: Int = 0
    var ptsUs: Long = 0
    var captureUs: Long = 0

    val isKeyframe: Boolean get() = (flags and FrameFlags.KEYFRAME) != 0
    val isReset: Boolean get() = (flags and FrameFlags.RESET) != 0

    fun copyFrom(other: FrameHeader) {
        type = other.type
        flags = other.flags
        seq = other.seq
        length = other.length
        ptsUs = other.ptsUs
        captureUs = other.captureUs
    }

    override fun toString(): String =
        "FrameHeader(type=$type, flags=$flags, seq=$seq, length=$length, " +
            "ptsUs=$ptsUs, captureUs=$captureUs)"
}

/** Header-level rules shared by [FrameReader] and [FrameWriter]. */
internal object FrameRules {
    /** Validates everything except the connection phase. [limit] caps the payload length. */
    fun validate(h: FrameHeader, limit: Int) {
        val spec =
            h.type.serverToClient
                ?: fail(ErrorCode.UNEXPECTED_MESSAGE, "${h.type} is not a server message")
        checkFlags(h)
        checkTimestamps(h)
        if (h.length < 0 || h.length > minOf(spec.max, limit)) {
            fail(
                ErrorCode.MESSAGE_TOO_LARGE,
                "${h.type} payload length ${h.length} exceeds the limit"
            )
        }
        if (h.length < spec.min) fail(ErrorCode.MALFORMED_MESSAGE, "${h.type} payload too short")
    }

    fun checkPhase(type: MessageType, phase: Phase) {
        if (!type.phases.allows(
                phase
            )
        ) {
            fail(ErrorCode.UNEXPECTED_MESSAGE, "$type not allowed in $phase")
        }
    }

    /** Payload cap for a connection phase. */
    fun limit(phase: Phase, configured: Int): Int = if (phase ==
        Phase.HANDSHAKE
    ) {
        minOf(configured, ProtocolV2.MAX_HANDSHAKE_PAYLOAD)
    } else {
        configured
    }

    fun defaultFlags(type: MessageType): Int = when (type) {
        MessageType.CONFIG -> FrameFlags.CONFIG
        MessageType.ERROR -> FrameFlags.ERROR
        MessageType.VIDEO_SIZE -> FrameFlags.RESET
        else -> 0
    }

    private fun allowedFlags(type: MessageType): Int = when (type) {
        MessageType.VIDEO -> FrameFlags.KEYFRAME or FrameFlags.RESET
        MessageType.CONFIG -> FrameFlags.CONFIG or FrameFlags.RESET
        MessageType.VIDEO_SIZE -> FrameFlags.RESET
        MessageType.ERROR -> FrameFlags.ERROR
        else -> 0
    }

    private fun requiredFlags(type: MessageType): Int = when (type) {
        MessageType.CONFIG -> FrameFlags.CONFIG
        MessageType.ERROR -> FrameFlags.ERROR
        else -> 0
    }

    private fun checkFlags(h: FrameHeader) {
        val flags = h.flags
        if ((flags and FrameFlags.ALL.inv()) !=
            0
        ) {
            fail(ErrorCode.MALFORMED_MESSAGE, "reserved flag bits set")
        }
        if ((flags and allowedFlags(h.type).inv()) != 0) {
            fail(ErrorCode.MALFORMED_MESSAGE, "flags $flags not allowed on ${h.type}")
        }
        val required = requiredFlags(h.type)
        if ((flags and required) !=
            required
        ) {
            fail(ErrorCode.MALFORMED_MESSAGE, "${h.type} lacks its flag")
        }
        val resetWithoutKey = (flags and FrameFlags.RESET) != 0 && h.type == MessageType.VIDEO &&
            (flags and FrameFlags.KEYFRAME) == 0
        if (resetWithoutKey) {
            fail(
                ErrorCode.MALFORMED_MESSAGE,
                "RESET video frame must be a keyframe"
            )
        }
    }

    private fun checkTimestamps(h: FrameHeader) {
        if (h.type == MessageType.VIDEO) {
            if (h.ptsUs < 0 ||
                h.captureUs < 0
            ) {
                fail(ErrorCode.VALUE_OUT_OF_RANGE, "negative timestamp")
            }
        } else if (h.ptsUs != 0L || h.captureUs != 0L) {
            fail(ErrorCode.MALFORMED_MESSAGE, "${h.type} must have zero timestamps")
        }
    }
}
