package com.mastersoft.velocast.client

import com.mastersoft.velocast.protocol.ProtocolV2

/** Coded video dimensions parsed from an H.264 SPS NAL unit. */
data class StreamDimensions(val width: Int, val height: Int)

private object AnnexBNalScanner {
    private const val NAL_TYPE_MASK = 0x1F
    private const val NAL_SPS = 7
    private const val SHORT_START_CODE = 3
    private const val LONG_START_CODE = 4
    private const val EMULATION_BYTE = 0x03

    fun extractSpsRbsp(data: ByteArray, start: Int, end: Int): ByteArray? {
        val slice = findSpsNalSlice(data, start, end) ?: return null
        val rbsp = unescapeRbsp(data, slice.first + 1, slice.second)
        return rbsp.takeIf { it.isNotEmpty() }
    }

    private fun findSpsNalSlice(data: ByteArray, start: Int, end: Int): Pair<Int, Int>? {
        var pos = findStartCode(data, start, end)
        var match: Pair<Int, Int>? = null
        while (pos >= 0 && pos < end && match == null) {
            val scLen = startCodeLen(data, pos, end)
            val nalStart = pos + scLen
            val next = findStartCode(data, nalStart, end)
            val nalEnd = if (next >= 0) next else end
            if (nalStart < nalEnd && (data[nalStart].toInt() and NAL_TYPE_MASK) == NAL_SPS) {
                match = nalStart to nalEnd
            }
            pos = next
        }
        return match
    }

    private fun findStartCode(data: ByteArray, from: Int, end: Int): Int {
        var i = from
        var found = -1
        while (i + SHORT_START_CODE <= end && found < 0) {
            val zeroPair = data[i] == 0.toByte() && data[i + 1] == 0.toByte()
            if (zeroPair && isStartCodeAt(data, i, end)) {
                found = i
            }
            i++
        }
        return found
    }

    private fun isStartCodeAt(data: ByteArray, index: Int, end: Int): Boolean {
        val isShort = data[index + 2] == 1.toByte()
        val isLong = index + LONG_START_CODE <= end &&
            data[index + 2] == 0.toByte() &&
            data[index + SHORT_START_CODE] == 1.toByte()
        return isShort || isLong
    }

    private fun startCodeLen(data: ByteArray, index: Int, end: Int): Int {
        val isLong = index + LONG_START_CODE <= end &&
            data[index + 2] == 0.toByte() &&
            data[index + SHORT_START_CODE] == 1.toByte()
        return if (isLong) LONG_START_CODE else SHORT_START_CODE
    }

    private fun unescapeRbsp(data: ByteArray, start: Int, end: Int): ByteArray {
        val out = ByteArray((end - start).coerceAtLeast(0))
        var outLen = 0
        var zeros = 0
        for (i in start until end) {
            val b = data[i].toInt() and 0xFF
            if (zeros == 2 && b == EMULATION_BYTE) {
                zeros = 0
            } else {
                out[outLen++] = b.toByte()
                zeros = if (b == 0) zeros + 1 else 0
            }
        }
        return out.copyOf(outLen)
    }
}

/**
 * Pure-JVM Annex-B H.264 Sequence Parameter Set (SPS) parser used to detect stream dimension
 * changes in `CONFIG` payloads without relying on Android framework internals (FUN-04).
 *
 * Returns `null` on truncated, corrupted, or non-SPS payloads without throwing.
 */
object H264SpsParser {
    private const val MACROBLOCK_PX = 16
    private const val DEFAULT_CHROMA_FORMAT = 1
    private const val CHROMA_420 = 1
    private const val CHROMA_422 = 2
    private const val CHROMA_444 = 3
    private const val SCALING_COUNT_444 = 12
    private const val SCALING_COUNT_DEFAULT = 8
    private const val SCALING_LIST_4X4_COUNT = 6
    private const val SCALING_SIZE_4X4 = 16
    private const val SCALING_SIZE_8X8 = 64
    private const val BITS_PER_BYTE = 8
    private const val MAX_EXP_GOLOMB_ZEROS = 30
    private const val MAX_POC_CYCLE = 256
    private const val INITIAL_SCALE = 8
    private const val SCALE_MOD = 256
    private const val CROP_COUNT = 4

    private val HIGH_PROFILES = setOf(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135)
    private val VALID_PROFILES = HIGH_PROFILES + setOf(66, 77, 88)

    fun parseDimensions(
        annexB: ByteArray,
        offset: Int = 0,
        length: Int = annexB.size - offset
    ): StreamDimensions? {
        val validRange = offset >= 0 && length > 0 && offset + length <= annexB.size
        val rbsp = if (validRange) {
            AnnexBNalScanner.extractSpsRbsp(annexB, offset, offset + length)
        } else {
            null
        }
        return if (rbsp != null) parseSpsRbsp(rbsp) else null
    }

    private fun parseSpsRbsp(rbsp: ByteArray): StreamDimensions? {
        val reader = BitReader(rbsp)
        val profileIdc = reader.readBits(BITS_PER_BYTE)
        if (profileIdc !in VALID_PROFILES) reader.markFailed()
        reader.readBits(BITS_PER_BYTE) // constraint flags + reserved
        reader.readBits(BITS_PER_BYTE) // level_idc
        reader.readUe() // seq_parameter_set_id

        val chromaFormatIdc = parseHighProfileChroma(reader, profileIdc)
        reader.readUe() // log2_max_frame_num_minus4
        skipPicOrderCnt(reader, reader.readUe())
        reader.readUe() // max_num_ref_frames
        reader.readBit() // gaps_in_frame_num_value_allowed_flag

        val picWidthInMbsMinus1 = reader.readUe()
        val picHeightInMapUnitsMinus1 = reader.readUe()
        val frameMbsOnlyFlag = reader.readBit()
        if (frameMbsOnlyFlag == 0) {
            reader.readBit() // mb_adaptive_frame_field_flag
        }
        reader.readBit() // direct_8x8_inference_flag
        val crops = if (reader.readBit() == 1) readCrops(reader) else IntArray(CROP_COUNT)

        return if (reader.failed) {
            null
        } else {
            computeDimensions(
                picWidthInMbsMinus1 = picWidthInMbsMinus1,
                picHeightInMapUnitsMinus1 = picHeightInMapUnitsMinus1,
                frameMbsOnlyFlag = frameMbsOnlyFlag,
                chromaFormatIdc = chromaFormatIdc,
                crops = crops
            )
        }
    }

    private fun parseHighProfileChroma(reader: BitReader, profileIdc: Int): Int {
        var chromaFormatIdc = DEFAULT_CHROMA_FORMAT
        if (profileIdc in HIGH_PROFILES) {
            chromaFormatIdc = reader.readUe()
            if (chromaFormatIdc > CHROMA_444) {
                reader.markFailed()
                chromaFormatIdc = DEFAULT_CHROMA_FORMAT
            } else {
                if (chromaFormatIdc == CHROMA_444) {
                    reader.readBit()
                }
                reader.readUe() // bit_depth_luma_minus8
                reader.readUe() // bit_depth_chroma_minus8
                reader.readBit() // qpprime_y_zero_transform_bypass_flag
                if (reader.readBit() == 1) {
                    skipScalingMatrices(reader, chromaFormatIdc)
                }
            }
        }
        return chromaFormatIdc
    }

    private fun skipScalingMatrices(reader: BitReader, chromaFormatIdc: Int) {
        val count = if (chromaFormatIdc != CHROMA_444) SCALING_COUNT_DEFAULT else SCALING_COUNT_444
        for (i in 0 until count) {
            if (reader.failed) break
            if (reader.readBit() == 1) {
                val size = if (i < SCALING_LIST_4X4_COUNT) SCALING_SIZE_4X4 else SCALING_SIZE_8X8
                skipScalingList(reader, size)
            }
        }
    }

    private fun skipScalingList(reader: BitReader, size: Int) {
        var lastScale = INITIAL_SCALE
        var nextScale = INITIAL_SCALE
        for (j in 0 until size) {
            if (reader.failed) break
            if (nextScale != 0) {
                val deltaScale = reader.readSe()
                nextScale = (lastScale + deltaScale + SCALE_MOD) % SCALE_MOD
            }
            lastScale = if (nextScale == 0) lastScale else nextScale
        }
    }

    private fun skipPicOrderCnt(reader: BitReader, picOrderCntType: Int) {
        when (picOrderCntType) {
            0 -> reader.readUe()

            1 -> {
                reader.readBit()
                reader.readSe()
                reader.readSe()
                val cycle = reader.readUe()
                if (cycle > MAX_POC_CYCLE) {
                    reader.markFailed()
                } else {
                    repeat(cycle) { reader.readSe() }
                }
            }

            2 -> Unit

            else -> reader.markFailed()
        }
    }

    private fun readCrops(reader: BitReader): IntArray = intArrayOf(
        reader.readUe(),
        reader.readUe(),
        reader.readUe(),
        reader.readUe()
    )

    private fun computeDimensions(
        picWidthInMbsMinus1: Int,
        picHeightInMapUnitsMinus1: Int,
        frameMbsOnlyFlag: Int,
        chromaFormatIdc: Int,
        crops: IntArray
    ): StreamDimensions? {
        val subWidthC = if (chromaFormatIdc == CHROMA_420 || chromaFormatIdc == CHROMA_422) 2 else 1
        val subHeightC = if (chromaFormatIdc == CHROMA_420) 2 else 1
        val cropUnitX = if (chromaFormatIdc == 0) 1 else subWidthC
        val cropUnitY = (if (chromaFormatIdc == 0) 1 else subHeightC) * (2 - frameMbsOnlyFlag)

        val codedWidth = (picWidthInMbsMinus1 + 1).toLong() * MACROBLOCK_PX
        val codedHeight = (2 - frameMbsOnlyFlag).toLong() *
            (picHeightInMapUnitsMinus1 + 1).toLong() * MACROBLOCK_PX
        val width = codedWidth - (crops[0].toLong() + crops[1].toLong()) * cropUnitX
        val height = codedHeight - (crops[2].toLong() + crops[3].toLong()) * cropUnitY
        val valid = width in 1..ProtocolV2.MAX_DIMENSION && height in 1..ProtocolV2.MAX_DIMENSION
        return if (valid) StreamDimensions(width.toInt(), height.toInt()) else null
    }

    private class BitReader(private val data: ByteArray) {
        private var byteOffset = 0
        private var bitOffset = 0
        var failed = false
            private set

        fun markFailed() {
            failed = true
        }

        fun readBit(): Int {
            if (failed || byteOffset >= data.size) {
                failed = true
                return 0
            }
            val bit = (data[byteOffset].toInt() ushr (7 - bitOffset)) and 1
            bitOffset++
            if (bitOffset == BITS_PER_BYTE) {
                bitOffset = 0
                byteOffset++
            }
            return bit
        }

        fun readBits(count: Int): Int {
            var value = 0
            repeat(count) {
                value = (value shl 1) or readBit()
            }
            return value
        }

        fun readUe(): Int {
            var leadingZeros = 0
            while (!failed && readBit() == 0) {
                leadingZeros++
                if (leadingZeros > MAX_EXP_GOLOMB_ZEROS) {
                    failed = true
                }
            }
            val suffix = if (failed || leadingZeros == 0) 0 else readBits(leadingZeros)
            return if (failed || leadingZeros == 0) 0 else ((1 shl leadingZeros) - 1) + suffix
        }

        fun readSe(): Int {
            val codeNum = readUe()
            val signed = (codeNum + 1) ushr 1
            return if ((codeNum and 1) == 0) -signed else signed
        }
    }
}
