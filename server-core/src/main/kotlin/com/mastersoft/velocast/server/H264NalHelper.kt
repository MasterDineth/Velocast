package com.mastersoft.velocast.server

import android.media.MediaFormat
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * Annex-B H.264 NAL scanner for verifying and ensuring SPS/PPS + IDR framing.
 */
object H264NalHelper {
    private const val NAL_IDR = 5
    private const val NAL_SPS = 7
    private const val NAL_PPS = 8
    private const val NAL_TYPE_MASK = 0x1F
    private const val SHORT_START_CODE_LEN = 3
    private const val LONG_START_CODE_LEN = 4

    fun containsSpsAndPps(data: ByteArray, offset: Int, length: Int): Boolean {
        var hasSps = false
        var hasPps = false
        scanNalUnits(data, offset, length) { nalStart, _ ->
            val nalType = data[nalStart].toInt() and NAL_TYPE_MASK
            if (nalType == NAL_SPS) hasSps = true
            if (nalType == NAL_PPS) hasPps = true
        }
        return hasSps && hasPps
    }

    fun containsIdr(data: ByteArray, offset: Int, length: Int): Boolean {
        var found = false
        scanNalUnits(data, offset, length) { nalStart, _ ->
            val nalType = data[nalStart].toInt() and NAL_TYPE_MASK
            if (nalType == NAL_IDR) found = true
        }
        return found
    }

    fun extractSpsPps(data: ByteArray, offset: Int, length: Int): ByteArray? {
        val out = ByteArrayOutputStream()
        scanNalUnits(data, offset, length) { nalStart, nalEnd ->
            val nalType = data[nalStart].toInt() and NAL_TYPE_MASK
            if (nalType == NAL_SPS || nalType == NAL_PPS) {
                out.write(0)
                out.write(0)
                out.write(0)
                out.write(1)
                out.write(data, nalStart, nalEnd - nalStart)
            }
        }
        val result = out.toByteArray()
        return if (result.isNotEmpty()) result else null
    }

    fun extractCsdFromFormat(format: MediaFormat): ByteArray? {
        val csd0 = readByteBuffer(format, "csd-0")
        val csd1 = readByteBuffer(format, "csd-1")
        if (csd0 == null && csd1 == null) return null
        val out = ByteArrayOutputStream()
        csd0?.let { out.write(it) }
        csd1?.let { out.write(it) }
        val result = out.toByteArray()
        return if (result.isNotEmpty()) result else null
    }

    private fun readByteBuffer(format: MediaFormat, key: String): ByteArray? {
        var bytes: ByteArray? = null
        if (format.containsKey(key)) {
            val buf: ByteBuffer? = format.getByteBuffer(key)
            if (buf != null) {
                val dup = buf.duplicate()
                bytes = ByteArray(dup.remaining())
                dup.get(bytes)
            }
        }
        return bytes
    }

    private inline fun scanNalUnits(
        data: ByteArray,
        offset: Int,
        length: Int,
        onNal: (nalStart: Int, nalEnd: Int) -> Unit
    ) {
        val end = offset + length
        var pos = findStartCode(data, offset, end)
        while (pos >= 0 && pos < end) {
            val scLen = startCodeLengthAt(data, pos, end)
            val nalStart = pos + scLen
            val nextStart = findStartCode(data, nalStart, end)
            val nalEnd = if (nextStart >= 0) nextStart else end
            if (nalStart < nalEnd) {
                onNal(nalStart, nalEnd)
            }
            pos = nextStart
        }
    }

    private fun findStartCode(data: ByteArray, from: Int, end: Int): Int {
        var found = -1
        var i = from
        while (i + SHORT_START_CODE_LEN <= end && found < 0) {
            val isPrefixZero = data[i] == 0.toByte() && data[i + 1] == 0.toByte()
            if (isPrefixZero) {
                val isShort = data[i + 2] == 1.toByte()
                val isLong = i + LONG_START_CODE_LEN <= end &&
                    data[i + 2] == 0.toByte() &&
                    data[i + SHORT_START_CODE_LEN] == 1.toByte()
                if (isShort || isLong) {
                    found = i
                }
            }
            i++
        }
        return found
    }

    private fun startCodeLengthAt(data: ByteArray, index: Int, end: Int): Int =
        if (index + LONG_START_CODE_LEN <= end &&
            data[index + 2] == 0.toByte() &&
            data[index + SHORT_START_CODE_LEN] == 1.toByte()
        ) {
            LONG_START_CODE_LEN
        } else {
            SHORT_START_CODE_LEN
        }
}
