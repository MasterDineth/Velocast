package com.mastersoft.velocast.protocol

/** Size and orientation of a display in pixels. [rotation] is in quarter turns (0..3). */
data class DisplayGeometry(val width: Int, val height: Int, val rotation: Int) {
    init {
        Validators.dimension(width)
        Validators.dimension(height)
        Validators.rotation(rotation)
    }

    internal fun writeTo(w: PayloadWriter) {
        w.u16(width)
        w.u16(height)
        w.u8(rotation)
    }

    companion object {
        internal fun read(r: PayloadReader): DisplayGeometry {
            val width = r.u16()
            val height = r.u16()
            val rotation = r.u8()
            return DisplayGeometry(width, height, rotation)
        }
    }
}

/**
 * Server to client: the encoded stream size and the geometry of the physical display. Touch
 * coordinates are normalised, and the server maps them with [display]. Sent after CONFIG and
 * again whenever either changes (FUN-01); it carries the RESET flag.
 */
data class VideoSize(val streamWidth: Int, val streamHeight: Int, val display: DisplayGeometry) :
    Payload {
    init {
        Validators.dimension(streamWidth)
        Validators.dimension(streamHeight)
    }

    override val type: MessageType get() = MessageType.VIDEO_SIZE
    override val encodedSize: Int get() = ProtocolV2.VIDEO_SIZE_PAYLOAD_SIZE

    override fun encodeTo(dest: ByteArray, offset: Int): Int {
        val w = PayloadWriter(dest, offset)
        w.u16(streamWidth)
        w.u16(streamHeight)
        display.writeTo(w)
        return w.written
    }

    companion object {
        fun decode(buf: ByteArray, offset: Int, length: Int): VideoSize {
            val r = PayloadReader(buf, offset, length)
            val streamWidth = r.u16()
            val streamHeight = r.u16()
            val display = DisplayGeometry.read(r)
            r.expectEnd()
            return VideoSize(streamWidth, streamHeight, display)
        }
    }
}

/** Server to client: codec and codec-specific data (SPS/PPS, VPS/SPS/PPS). */
class CodecConfig(val codec: Codec, csd: ByteArray) : Payload {
    val csd: ByteArray = csd.copyOf()

    init {
        if (csd.isEmpty() || csd.size > ProtocolV2.MAX_PAYLOAD - 1) {
            fail(
                ErrorCode.MESSAGE_TOO_LARGE,
                "codec config size outside 1..${ProtocolV2.MAX_PAYLOAD - 1}"
            )
        }
    }

    override val type: MessageType get() = MessageType.CONFIG
    override val encodedSize: Int get() = 1 + csd.size

    override fun encodeTo(dest: ByteArray, offset: Int): Int {
        val w = PayloadWriter(dest, offset)
        w.u8(codec.id)
        w.bytes(csd)
        return w.written
    }

    override fun equals(other: Any?): Boolean = other is CodecConfig && codec == other.codec &&
        csd.contentEquals(other.csd)

    override fun hashCode(): Int = 31 * codec.hashCode() + csd.contentHashCode()

    override fun toString(): String = "CodecConfig(codec=$codec, csd=${csd.size} bytes)"

    companion object {
        fun decode(buf: ByteArray, offset: Int, length: Int): CodecConfig {
            val r = PayloadReader(buf, offset, length)
            val codec = Codec.fromId(r.u8()) ?: fail(ErrorCode.VALUE_OUT_OF_RANGE, "unknown codec")
            return CodecConfig(codec, r.bytes(r.remaining))
        }
    }
}
