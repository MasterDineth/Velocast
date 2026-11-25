package com.mastersoft.velocast.protocol

private const val SEQ_MASK = 0xFFFF
private const val HALF_SEQ_RANGE = 0x8000

/**
 * Tracks a 16-bit sequence number. [onSeq] returns how many numbers were skipped since the last
 * one (0 when in order). A gap in VIDEO frames means the sender dropped frames (latest-wins
 * queue): discard until the next keyframe and send KEYFRAME_REQ (FUN-02).
 */
class SeqTracker {
    private var expected = 0
    private var started = false

    fun onSeq(seq: Int): Int {
        val gap = if (started) (seq - expected) and SEQ_MASK else 0
        if (gap >=
            HALF_SEQ_RANGE
        ) {
            fail(ErrorCode.MALFORMED_MESSAGE, "sequence number went backwards")
        }
        started = true
        expected = (seq + 1) and SEQ_MASK
        return gap
    }

    fun reset() {
        started = false
        expected = 0
    }
}

/**
 * The strict touch state machine of SEC-05 / FUN-05, for the receiving side: DOWN only for a
 * pointer that is up, MOVE and UP only for a pointer that is down, CANCEL ends every pointer.
 * Violations throw [ErrorCode.INVALID_POINTER_STATE]. The server decides whether that closes the
 * session; it must never inject an event for an unknown pointer.
 */
class PointerStateTracker {
    private var down = 0

    val activeCount: Int get() = Integer.bitCount(down)

    fun isDown(pointer: Int): Boolean = (down and bit(pointer)) != 0

    fun onTouch(pointer: Int, action: TouchAction) {
        val bit = bit(pointer)
        when (action) {
            TouchAction.DOWN -> {
                if ((down and bit) !=
                    0
                ) {
                    fail(ErrorCode.INVALID_POINTER_STATE, "DOWN for a pointer already down")
                }
                down = down or bit
            }

            TouchAction.MOVE -> {
                if ((down and bit) ==
                    0
                ) {
                    fail(ErrorCode.INVALID_POINTER_STATE, "MOVE for a pointer that is up")
                }
            }

            TouchAction.UP -> {
                if ((down and bit) ==
                    0
                ) {
                    fail(ErrorCode.INVALID_POINTER_STATE, "UP for a pointer that is up")
                }
                down = down and bit.inv()
            }

            TouchAction.CANCEL -> down = 0
        }
    }

    fun reset() {
        down = 0
    }

    private fun bit(pointer: Int): Int = 1 shl Validators.pointerId(pointer)
}
