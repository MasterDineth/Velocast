package com.mastersoft.velocast.protocol

/**
 * Server to client (session phase): static facts about the old phone, sent only after
 * authentication succeeds so unauthenticated peers learn nothing about the device (R10).
 */
data class DeviceInfo(
    val sdkInt: Int,
    val launchMode: LaunchMode,
    val display: DisplayGeometry,
    val manufacturer: String,
    val model: String
) : Payload {
    init {
        Validators.u16(sdkInt, "sdkInt")
        TextRules.require(manufacturer, ProtocolV2.MAX_NAME_BYTES, "manufacturer")
        TextRules.require(model, ProtocolV2.MAX_NAME_BYTES, "model")
    }

    override val type: MessageType get() = MessageType.DEVICE_INFO
    override val encodedSize: Int
        get() = ProtocolV2.DEVICE_INFO_MIN_SIZE +
            manufacturer.toByteArray(Charsets.UTF_8).size +
            model.toByteArray(Charsets.UTF_8).size

    override fun encodeTo(dest: ByteArray, offset: Int): Int {
        val w = PayloadWriter(dest, offset)
        w.u16(sdkInt)
        w.u8(launchMode.id)
        display.writeTo(w)
        w.string8(manufacturer)
        w.string8(model)
        return w.written
    }

    companion object {
        fun decode(buf: ByteArray, offset: Int, length: Int): DeviceInfo {
            val r = PayloadReader(buf, offset, length)
            val sdkInt = r.u16()
            val launchMode = LaunchMode.fromId(r.u8())
                ?: fail(ErrorCode.VALUE_OUT_OF_RANGE, "unknown launch mode")
            val display = DisplayGeometry.read(r)
            val manufacturer = r.string8(ProtocolV2.MAX_NAME_BYTES)
            val model = r.string8(ProtocolV2.MAX_NAME_BYTES)
            r.expectEnd()
            return DeviceInfo(sdkInt, launchMode, display, manufacturer, model)
        }
    }
}

/** Server to client: a typed state change. See [StatusKind] for the meaning of [value]. */
data class Status(val kind: StatusKind, val value: Int, val detail: String = "") : Payload {
    init {
        if (!kind.accepts(
                value
            )
        ) {
            fail(ErrorCode.VALUE_OUT_OF_RANGE, "value $value invalid for $kind")
        }
        TextRules.require(detail, ProtocolV2.MAX_STATUS_DETAIL_BYTES, "status detail")
    }

    override val type: MessageType get() = MessageType.STATUS
    override val encodedSize: Int get() = ProtocolV2.STATUS_FIXED_SIZE +
        detail.toByteArray(Charsets.UTF_8).size

    override fun encodeTo(dest: ByteArray, offset: Int): Int {
        val w = PayloadWriter(dest, offset)
        w.u8(kind.id)
        w.i32(value)
        w.string8(detail)
        return w.written
    }

    companion object {
        fun decode(buf: ByteArray, offset: Int, length: Int): Status {
            val r = PayloadReader(buf, offset, length)
            val kind =
                StatusKind.fromId(r.u8())
                    ?: fail(ErrorCode.VALUE_OUT_OF_RANGE, "unknown status kind")
            val value = r.i32()
            val detail = r.string8(ProtocolV2.MAX_STATUS_DETAIL_BYTES)
            r.expectEnd()
            return Status(kind, value, detail)
        }
    }
}

/** Server to client: a failure report. A [fatal] error ends the session. */
data class ErrorMessage(val code: ErrorCode, val fatal: Boolean, val detail: String = "") :
    Payload {
    init {
        TextRules.require(detail, ProtocolV2.MAX_ERROR_DETAIL_BYTES, "error detail")
    }

    override val type: MessageType get() = MessageType.ERROR
    override val encodedSize: Int get() = ProtocolV2.ERROR_FIXED_SIZE +
        detail.toByteArray(Charsets.UTF_8).size

    override fun encodeTo(dest: ByteArray, offset: Int): Int {
        val w = PayloadWriter(dest, offset)
        w.u16(code.id)
        w.u8(if (fatal) 1 else 0)
        w.string8(detail)
        return w.written
    }

    companion object {
        fun decode(buf: ByteArray, offset: Int, length: Int): ErrorMessage {
            val r = PayloadReader(buf, offset, length)
            val code =
                ErrorCode.fromId(r.u16())
                    ?: fail(ErrorCode.VALUE_OUT_OF_RANGE, "unknown error code")
            val fatal = when (r.u8()) {
                0 -> false
                1 -> true
                else -> fail(ErrorCode.MALFORMED_MESSAGE, "fatal flag must be 0 or 1")
            }
            val detail = r.string8(ProtocolV2.MAX_ERROR_DETAIL_BYTES)
            r.expectEnd()
            return ErrorMessage(code, fatal, detail)
        }
    }
}

/**
 * Server to client: answers PING. [serverUs] is the server's monotonic clock in microseconds, the
 * same clock as `captureUs` in video frames; the client estimates the clock offset from it
 * (PERF-08).
 */
data class Pong(val token: Int, val serverUs: Long) : Payload {
    init {
        if (serverUs < 0) fail(ErrorCode.VALUE_OUT_OF_RANGE, "negative server time")
    }

    override val type: MessageType get() = MessageType.PONG
    override val encodedSize: Int get() = ProtocolV2.PONG_SIZE

    override fun encodeTo(dest: ByteArray, offset: Int): Int {
        val w = PayloadWriter(dest, offset)
        w.i32(token)
        w.i64(serverUs)
        return w.written
    }

    companion object {
        fun decode(buf: ByteArray, offset: Int, length: Int): Pong {
            val r = PayloadReader(buf, offset, length)
            val token = r.i32()
            val serverUs = r.i64()
            r.expectEnd()
            return Pong(token, serverUs)
        }
    }
}

/** Server to client: orderly end of the session. A null [code] means a normal close. */
data class Bye(val code: ErrorCode?) : Payload {
    override val type: MessageType get() = MessageType.BYE
    override val encodedSize: Int get() = ProtocolV2.BYE_SIZE

    override fun encodeTo(dest: ByteArray, offset: Int): Int {
        val w = PayloadWriter(dest, offset)
        w.u16(code?.id ?: 0)
        return w.written
    }

    companion object {
        fun decode(buf: ByteArray, offset: Int, length: Int): Bye {
            val r = PayloadReader(buf, offset, length)
            val id = r.u16()
            r.expectEnd()
            val code = if (id == 0) null else ErrorCode.fromId(id)
            if (id != 0 && code == null) fail(ErrorCode.VALUE_OUT_OF_RANGE, "unknown BYE code")
            return Bye(code)
        }
    }
}

/**
 * Either direction: clipboard text. From the client, [paste] asks the server to paste after
 * setting the clipboard; the server ignores it in the other direction.
 */
data class ClipboardMessage(val paste: Boolean, val text: String) : Payload {
    init {
        TextRules.require(text, ProtocolV2.MAX_CLIPBOARD_BYTES - 1, "clipboard text")
    }

    override val type: MessageType get() = MessageType.CLIPBOARD
    override val encodedSize: Int get() = 1 + text.toByteArray(Charsets.UTF_8).size

    override fun encodeTo(dest: ByteArray, offset: Int): Int {
        val w = PayloadWriter(dest, offset)
        w.u8(if (paste) 1 else 0)
        w.bytes(text.toByteArray(Charsets.UTF_8))
        return w.written
    }

    companion object {
        fun decode(buf: ByteArray, offset: Int, length: Int): ClipboardMessage {
            val r = PayloadReader(buf, offset, length)
            val paste = when (r.u8()) {
                0 -> false
                1 -> true
                else -> fail(ErrorCode.MALFORMED_MESSAGE, "clipboard flags must be 0 or 1")
            }
            return ClipboardMessage(paste, r.restUtf8())
        }
    }
}

/** Client to server: text to type on the old phone (1..4096 UTF-8 bytes, no NUL). */
data class TextInput(val text: String) : Payload {
    init {
        if (text.isEmpty()) fail(ErrorCode.MALFORMED_MESSAGE, "text must not be empty")
        TextRules.require(text, ProtocolV2.MAX_TEXT_BYTES, "text")
    }

    override val type: MessageType get() = MessageType.TEXT
    override val encodedSize: Int get() = text.toByteArray(Charsets.UTF_8).size

    override fun encodeTo(dest: ByteArray, offset: Int): Int {
        val w = PayloadWriter(dest, offset)
        w.bytes(text.toByteArray(Charsets.UTF_8))
        return w.written
    }

    companion object {
        fun decode(buf: ByteArray, offset: Int, length: Int): TextInput =
            TextInput(PayloadReader(buf, offset, length).restUtf8())
    }
}
