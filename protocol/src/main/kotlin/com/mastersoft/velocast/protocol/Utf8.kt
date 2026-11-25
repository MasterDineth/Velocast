package com.mastersoft.velocast.protocol

private const val BYTE_MASK = 0xFF
private const val ASCII_LIMIT = 0x80
private const val LEAD_2_MIN = 0xC2
private const val LEAD_2_MAX = 0xDF
private const val LEAD_3_MAX = 0xEF
private const val LEAD_4_MAX = 0xF4
private const val LEAD_E0 = 0xE0
private const val LEAD_ED = 0xED
private const val LEAD_F0 = 0xF0
private const val LEAD_F4 = 0xF4
private const val CONT_MIN = 0x80
private const val CONT_MAX = 0xBF
private const val E0_SECOND_MIN = 0xA0
private const val ED_SECOND_MAX = 0x9F
private const val F0_SECOND_MIN = 0x90
private const val F4_SECOND_MAX = 0x8F
private const val INVALID = -1

/** Strict, allocation-free UTF-8 validation. U+0000 is rejected as well. */
internal object Utf8 {
    fun isValid(buf: ByteArray, offset: Int, length: Int): Boolean {
        var i = offset
        val end = offset + length
        var valid = true
        while (valid && i < end) {
            val n = sequenceLength(buf, i, end)
            valid = n > 0
            i += n
        }
        return valid
    }

    /** Returns the length of the well-formed sequence at [i], or 0 when there is none. */
    private fun sequenceLength(buf: ByteArray, i: Int, end: Int): Int {
        val lead = buf[i].toInt() and BYTE_MASK
        val continuation = when {
            lead == 0 -> INVALID
            lead < ASCII_LIMIT -> 0
            lead < LEAD_2_MIN -> INVALID
            lead <= LEAD_2_MAX -> 1
            lead <= LEAD_3_MAX -> 2
            lead <= LEAD_4_MAX -> 3
            else -> INVALID
        }
        val ok = continuation >= 0 && i + continuation < end &&
            continuationsValid(buf, i, lead, continuation)
        return if (ok) continuation + 1 else 0
    }

    private fun continuationsValid(buf: ByteArray, i: Int, lead: Int, count: Int): Boolean {
        var ok = true
        for (k in 1..count) {
            val b = buf[i + k].toInt() and BYTE_MASK
            val min = if (k == 1) secondMin(lead) else CONT_MIN
            val max = if (k == 1) secondMax(lead) else CONT_MAX
            ok = ok && b in min..max
        }
        return ok
    }

    private fun secondMin(lead: Int): Int = when (lead) {
        LEAD_E0 -> E0_SECOND_MIN
        LEAD_F0 -> F0_SECOND_MIN
        else -> CONT_MIN
    }

    private fun secondMax(lead: Int): Int = when (lead) {
        LEAD_ED -> ED_SECOND_MAX
        LEAD_F4 -> F4_SECOND_MAX
        else -> CONT_MAX
    }
}
