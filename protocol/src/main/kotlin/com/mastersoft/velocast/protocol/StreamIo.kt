package com.mastersoft.velocast.protocol

import java.io.IOException
import java.io.InputStream

private const val INITIAL_CAPACITY = 256
private const val GROWTH_FACTOR = 2

internal object StreamIo {
    /**
     * Reads exactly [length] bytes. Returns false only when [atBoundary] is set and the stream
     * ended cleanly before the first byte. End of stream anywhere else is [ErrorCode.TRUNCATED].
     */
    fun readFully(
        input: InputStream,
        dest: ByteArray,
        offset: Int,
        length: Int,
        atBoundary: Boolean
    ): Boolean {
        var filled = 0
        var clean = true
        while (clean && filled < length) {
            val n = input.read(dest, offset + filled, length - filled)
            if (n < 0) {
                if (filled > 0 ||
                    !atBoundary
                ) {
                    fail(ErrorCode.TRUNCATED, "stream ended inside a message")
                }
                clean = false
            } else if (n == 0) {
                throw IOException("stream returned no data for a non-empty read")
            } else {
                filled += n
            }
        }
        return clean
    }
}

/**
 * A payload buffer that grows only as bytes really arrive, never beyond what was declared (and
 * the caller already capped the declaration), so a peer that announces a huge length and sends
 * nothing costs at most a few KiB.
 */
internal class GrowingBuffer {
    var array = ByteArray(INITIAL_CAPACITY)
        private set

    fun fill(input: InputStream, length: Int) {
        var filled = 0
        while (filled < length) {
            if (filled ==
                array.size
            ) {
                array = array.copyOf(minOf(length, array.size * GROWTH_FACTOR))
            }
            val chunk = minOf(array.size, length) - filled
            StreamIo.readFully(input, array, filled, chunk, atBoundary = false)
            filled += chunk
        }
    }

    fun copyFrom(source: ByteArray, offset: Int, length: Int) {
        if (array.size < length) {
            array = ByteArray(length)
        }
        source.copyInto(
            array,
            destinationOffset = 0,
            startIndex = offset,
            endIndex =
                offset + length
        )
    }
}
