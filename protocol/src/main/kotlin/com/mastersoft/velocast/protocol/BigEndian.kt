package com.mastersoft.velocast.protocol

private const val BYTE_MASK = 0xFF
private const val INT_MASK = 0xFFFFFFFFL
private const val OFFSET_1 = 1
private const val OFFSET_2 = 2
private const val OFFSET_3 = 3
private const val OFFSET_4 = 4
private const val SHIFT_8 = 8
private const val SHIFT_16 = 16
private const val SHIFT_24 = 24
private const val SHIFT_32 = 32

/** Allocation-free big-endian accessors (no ByteBuffer wrappers on the hot path). */
internal object BigEndian {
    fun getU8(b: ByteArray, o: Int): Int = b[o].toInt() and BYTE_MASK

    fun getU16(b: ByteArray, o: Int): Int = (getU8(b, o) shl SHIFT_8) or getU8(b, o + OFFSET_1)

    fun getI32(b: ByteArray, o: Int): Int = (getU8(b, o) shl SHIFT_24) or
        (getU8(b, o + OFFSET_1) shl SHIFT_16) or
        (getU8(b, o + OFFSET_2) shl SHIFT_8) or
        getU8(b, o + OFFSET_3)

    fun getI64(b: ByteArray, o: Int): Long =
        (getI32(b, o).toLong() shl SHIFT_32) or (getI32(b, o + OFFSET_4).toLong() and INT_MASK)

    fun getF32(b: ByteArray, o: Int): Float = Float.fromBits(getI32(b, o))

    fun putU8(b: ByteArray, o: Int, v: Int) {
        b[o] = v.toByte()
    }

    fun putU16(b: ByteArray, o: Int, v: Int) {
        b[o] = (v ushr SHIFT_8).toByte()
        b[o + OFFSET_1] = v.toByte()
    }

    fun putI32(b: ByteArray, o: Int, v: Int) {
        b[o] = (v ushr SHIFT_24).toByte()
        b[o + OFFSET_1] = (v ushr SHIFT_16).toByte()
        b[o + OFFSET_2] = (v ushr SHIFT_8).toByte()
        b[o + OFFSET_3] = v.toByte()
    }

    fun putI64(b: ByteArray, o: Int, v: Long) {
        putI32(b, o, (v ushr SHIFT_32).toInt())
        putI32(b, o + OFFSET_4, v.toInt())
    }

    fun putF32(b: ByteArray, o: Int, v: Float) = putI32(b, o, v.toRawBits())
}
