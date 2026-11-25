package com.mastersoft.velocast.protocol

private const val SIZE_U8 = 1
private const val SIZE_U16 = 2
private const val SIZE_I32 = 4
private const val SIZE_I64 = 8

/** Sequential, bounds-checked reader over a payload. Every failure is a [ProtocolException]. */
class PayloadReader(private val buf: ByteArray, offset: Int, length: Int) {
    private var pos: Int = offset
    private val end: Int = offset + length

    init {
        if (offset < 0 || length < 0 || length > buf.size - offset) {
            fail(ErrorCode.MALFORMED_MESSAGE, "payload bounds outside the buffer")
        }
    }

    val remaining: Int get() = end - pos

    fun u8(): Int {
        need(SIZE_U8)
        return BigEndian.getU8(buf, pos).also { pos += SIZE_U8 }
    }

    fun u16(): Int {
        need(SIZE_U16)
        return BigEndian.getU16(buf, pos).also { pos += SIZE_U16 }
    }

    fun i32(): Int {
        need(SIZE_I32)
        return BigEndian.getI32(buf, pos).also { pos += SIZE_I32 }
    }

    fun i64(): Long {
        need(SIZE_I64)
        return BigEndian.getI64(buf, pos).also { pos += SIZE_I64 }
    }

    fun bytes(count: Int): ByteArray {
        need(count)
        return buf.copyOfRange(pos, pos + count).also { pos += count }
    }

    /** Reads a string with a one-byte length prefix. */
    fun string8(maxBytes: Int): String {
        val length = u8()
        if (length > maxBytes) {
            fail(ErrorCode.MESSAGE_TOO_LARGE, "string longer than $maxBytes bytes")
        }
        return utf8(length)
    }

    /** Reads the rest of the payload as UTF-8 text. */
    fun restUtf8(): String = utf8(remaining)

    /** Fails unless every byte was consumed. */
    fun expectEnd() {
        if (remaining != 0) fail(ErrorCode.MALFORMED_MESSAGE, "$remaining trailing payload bytes")
    }

    private fun utf8(length: Int): String {
        need(length)
        if (!Utf8.isValid(buf, pos, length)) {
            fail(ErrorCode.INVALID_TEXT, "text is not valid UTF-8")
        }
        return String(buf, pos, length, Charsets.UTF_8).also { pos += length }
    }

    private fun need(count: Int) {
        if (count < 0 || count > remaining) fail(ErrorCode.MALFORMED_MESSAGE, "payload truncated")
    }
}

/** Sequential, bounds-checked writer into a caller-provided array. */
class PayloadWriter(private val dest: ByteArray, private val start: Int) {
    private var pos: Int = start

    /** Number of bytes written so far. */
    val written: Int get() = pos - start

    fun u8(value: Int) {
        need(SIZE_U8)
        BigEndian.putU8(dest, pos, value)
        pos += SIZE_U8
    }

    fun u16(value: Int) {
        need(SIZE_U16)
        BigEndian.putU16(dest, pos, value)
        pos += SIZE_U16
    }

    fun i32(value: Int) {
        need(SIZE_I32)
        BigEndian.putI32(dest, pos, value)
        pos += SIZE_I32
    }

    fun i64(value: Long) {
        need(SIZE_I64)
        BigEndian.putI64(dest, pos, value)
        pos += SIZE_I64
    }

    fun bytes(value: ByteArray) {
        need(value.size)
        value.copyInto(dest, pos)
        pos += value.size
    }

    /** Writes a string with a one-byte length prefix. The caller has checked its size. */
    fun string8(value: String) {
        val encoded = value.toByteArray(Charsets.UTF_8)
        u8(encoded.size)
        bytes(encoded)
    }

    private fun need(count: Int) {
        if (count > dest.size - pos) {
            fail(ErrorCode.MESSAGE_TOO_LARGE, "destination buffer too small")
        }
    }
}
