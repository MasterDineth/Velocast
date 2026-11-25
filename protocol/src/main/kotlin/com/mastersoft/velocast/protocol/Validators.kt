package com.mastersoft.velocast.protocol

/** Numeric range validators. Each returns its argument or throws [ProtocolException]. */
object Validators {
    fun pointerId(id: Int): Int {
        if (id < 0 || id > ProtocolV2.MAX_POINTER_ID) {
            fail(
                ErrorCode.INVALID_POINTER,
                "pointer id $id outside 0..${ProtocolV2.MAX_POINTER_ID}"
            )
        }
        return id
    }

    /** A normalised coordinate: finite, not negative zero, and within [0, 1]. */
    fun coordinate(value: Float): Float {
        val inUnitRange = value.isFinite() && value >= 0f && value <= 1f
        if (!inUnitRange || value.toRawBits() == NEGATIVE_ZERO_BITS) {
            fail(ErrorCode.INVALID_COORDINATE, "coordinate outside [0, 1] or not finite")
        }
        return value
    }

    fun bitrateBps(bps: Int): Int {
        if (bps < ProtocolV2.MIN_BITRATE_BPS || bps > ProtocolV2.MAX_BITRATE_BPS) {
            fail(ErrorCode.VALUE_OUT_OF_RANGE, "bitrate $bps outside 1..40 Mbps")
        }
        return bps
    }

    /** A frame rate: whole number of frames per second within 15..120, sent as a float. */
    fun fps(value: Float): Float {
        val inRange = value.isFinite() && value >= ProtocolV2.MIN_FPS && value <= ProtocolV2.MAX_FPS
        if (!inRange || value != value.toInt().toFloat()) {
            fail(ErrorCode.VALUE_OUT_OF_RANGE, "fps outside 15..120 or not a whole number")
        }
        return value
    }

    fun scale(value: Float): Float {
        if (!value.isFinite() || value < ProtocolV2.MIN_SCALE || value > ProtocolV2.MAX_SCALE) {
            fail(ErrorCode.VALUE_OUT_OF_RANGE, "scale outside 0.5..1.0 or not finite")
        }
        return value
    }

    /** A pixel dimension within 1..[ProtocolV2.MAX_DIMENSION]. */
    fun dimension(value: Int): Int {
        if (value < 1 || value > ProtocolV2.MAX_DIMENSION) {
            fail(
                ErrorCode.VALUE_OUT_OF_RANGE,
                "dimension $value outside 1..${ProtocolV2.MAX_DIMENSION}"
            )
        }
        return value
    }

    /** A display rotation in quarter turns, 0..3. */
    fun rotation(value: Int): Int {
        if (value < 0 || value > ProtocolV2.MAX_ROTATION) {
            fail(
                ErrorCode.VALUE_OUT_OF_RANGE,
                "rotation $value outside 0..${ProtocolV2.MAX_ROTATION}"
            )
        }
        return value
    }

    /** An unsigned 16-bit field. */
    fun u16(value: Int, what: String): Int {
        if (value < 0 ||
            value > MAX_U16
        ) {
            fail(ErrorCode.VALUE_OUT_OF_RANGE, "$what outside 0..65535")
        }
        return value
    }

    private const val MAX_U16 = 0xFFFF
    private val NEGATIVE_ZERO_BITS = (-0.0f).toRawBits()
}

/** Checks for text carried in messages. */
internal object TextRules {
    /** Returns [text] when it has no NUL and encodes to at most [maxBytes] UTF-8 bytes. */
    fun require(text: String, maxBytes: Int, what: String): String {
        if (text.indexOf('\u0000') >= 0) fail(ErrorCode.INVALID_TEXT, "$what contains NUL")
        if (text.toByteArray(Charsets.UTF_8).size > maxBytes) {
            fail(ErrorCode.MESSAGE_TOO_LARGE, "$what longer than $maxBytes bytes")
        }
        return text
    }
}
