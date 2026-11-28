package com.mastersoft.velocast.protocol

import java.io.InputStream

private const val OFF_TYPE = 0
private const val OFF_FLAGS = 1
private const val OFF_SEQ = 2
private const val OFF_LENGTH = 4
private const val OFF_PTS = 8
private const val OFF_CAPTURE = 16

/**
 * Reads server to client frames (24-byte header plus payload) from a stream. Not thread-safe.
 *
 * Usage: [readHeader] into a reused [FrameHeader], then read the payload either into your own
 * buffer ([readPayload] with a destination, no allocation) or into the internal buffer.
 * A [ProtocolException] means the stream is desynchronised: close the connection.
 */
class FrameReader(
    private val input: InputStream,
    private val maxPayload: Int = ProtocolV2.MAX_PAYLOAD
) {
    /** Starts in [Phase.HANDSHAKE]; switch to [Phase.SESSION] once authentication succeeded. */
    var phase: Phase = Phase.HANDSHAKE

    /** Optional AEAD cipher enabled upon entering [Phase.SESSION] (`Features.SECURE_CHANNEL`). */
    var cipher: SessionCipher? = null
        set(value) {
            field = value
            aeadReader = value?.let {
                AeadRecordReader(input, it, ProtocolV2.FRAME_HEADER_SIZE + maxPayload)
            }
        }

    private var aeadReader: AeadRecordReader? = null
    private var decryptedPayloadReady = false
    private val headerBytes = ByteArray(ProtocolV2.FRAME_HEADER_SIZE)
    private val buffer = GrowingBuffer()

    init {
        require(maxPayload in 1..ProtocolV2.MAX_PAYLOAD) { "maxPayload must be in 1..MAX_PAYLOAD" }
    }

    /** The internal payload buffer; valid until the next read. See [readPayload]. */
    val payload: ByteArray get() = buffer.array

    /** Reads and validates the next header into [h]. Returns false on a clean end of stream. */
    fun readHeader(h: FrameHeader): Boolean {
        val recordReader = aeadReader
        if (recordReader != null && phase == Phase.SESSION) {
            return readEncryptedFrame(recordReader, h)
        }
        decryptedPayloadReady = false
        val got = StreamIo.readFully(
            input,
            headerBytes,
            0,
            ProtocolV2.FRAME_HEADER_SIZE,
            atBoundary = true
        )
        if (got) decode(h)
        return got
    }

    /** Reads the payload described by [h] into [dest] at [destOffset] without allocating. */
    fun readPayload(h: FrameHeader, dest: ByteArray, destOffset: Int) {
        require(destOffset >= 0 && dest.size - destOffset >= h.length) { "destination too small" }
        val recordReader = aeadReader
        if (decryptedPayloadReady && recordReader != null) {
            decryptedPayloadReady = false
            recordReader.plaintext.copyInto(
                destination = dest,
                destinationOffset = destOffset,
                startIndex = ProtocolV2.FRAME_HEADER_SIZE,
                endIndex = ProtocolV2.FRAME_HEADER_SIZE + h.length
            )
        } else {
            StreamIo.readFully(input, dest, destOffset, h.length, atBoundary = false)
        }
    }

    /** Reads the payload described by [h] into [payload] and returns its length. */
    fun readPayload(h: FrameHeader): Int {
        val recordReader = aeadReader
        if (decryptedPayloadReady && recordReader != null) {
            decryptedPayloadReady = false
            buffer.copyFrom(recordReader.plaintext, ProtocolV2.FRAME_HEADER_SIZE, h.length)
        } else {
            buffer.fill(input, h.length)
        }
        return h.length
    }

    private fun readEncryptedFrame(recordReader: AeadRecordReader, h: FrameHeader): Boolean {
        val recordLen = recordReader.readDataRecord()
        if (recordLen < 0) {
            decryptedPayloadReady = false
            return false
        }
        if (recordLen < ProtocolV2.FRAME_HEADER_SIZE) {
            fail(ErrorCode.MALFORMED_MESSAGE, "Encrypted S2C record shorter than frame header")
        }
        recordReader.plaintext.copyInto(headerBytes, 0, 0, ProtocolV2.FRAME_HEADER_SIZE)
        decode(h)
        if (ProtocolV2.FRAME_HEADER_SIZE + h.length != recordLen) {
            fail(
                ErrorCode.MALFORMED_MESSAGE,
                "Encrypted S2C record length $recordLen != header + ${h.length}"
            )
        }
        decryptedPayloadReady = true
        return true
    }

    private fun decode(h: FrameHeader) {
        val typeId = BigEndian.getU8(headerBytes, OFF_TYPE)
        h.type =
            MessageType.fromId(typeId)
                ?: fail(ErrorCode.UNKNOWN_MESSAGE_TYPE, "unknown message type $typeId")
        h.flags = BigEndian.getU8(headerBytes, OFF_FLAGS)
        h.seq = BigEndian.getU16(headerBytes, OFF_SEQ)
        h.length = BigEndian.getI32(headerBytes, OFF_LENGTH)
        h.ptsUs = BigEndian.getI64(headerBytes, OFF_PTS)
        h.captureUs = BigEndian.getI64(headerBytes, OFF_CAPTURE)
        FrameRules.checkPhase(h.type, phase)
        FrameRules.validate(h, FrameRules.limit(phase, maxPayload))
    }
}

/**
 * Writes server to client frames. Not thread-safe: use one writer per connection and serialise
 * calls. Every frame is validated before a byte is written, so an invalid frame can never leave
 * the process.
 */
class FrameWriter(
    private val out: java.io.OutputStream,
    private val maxPayload: Int = ProtocolV2.MAX_PAYLOAD
) {
    /** Starts in [Phase.HANDSHAKE]; only handshake messages, ERROR and BYE can be written. */
    var phase: Phase = Phase.HANDSHAKE

    /** Optional AEAD cipher enabled upon entering [Phase.SESSION] (`Features.SECURE_CHANNEL`). */
    var cipher: SessionCipher? = null
        set(value) {
            field = value
            aeadWriter = value?.let { AeadRecordWriter(out, it) }
        }

    /** Sequence number [writeMessage] puts into the next frame. */
    var nextSeq: Int = 0

    private var aeadWriter: AeadRecordWriter? = null
    private val scratch = ByteArray(SCRATCH_SIZE)
    private val header = FrameHeader()

    init {
        require(maxPayload in 1..ProtocolV2.MAX_PAYLOAD) { "maxPayload must be in 1..MAX_PAYLOAD" }
    }

    /** Writes [h] followed by [length] bytes of [payload]. Sets `h.length`. */
    fun write(h: FrameHeader, payload: ByteArray, offset: Int, length: Int) {
        require(offset >= 0 && length >= 0 && length <= payload.size - offset) {
            "payload range outside array"
        }
        h.length = length
        check(h)
        encodeHeader(h, scratch)
        val recordWriter = aeadWriter
        if (recordWriter != null && phase == Phase.SESSION) {
            recordWriter.writeSplitData(
                prefix = scratch,
                prefixLength = ProtocolV2.FRAME_HEADER_SIZE,
                body = payload,
                bodyOffset = offset,
                bodyLength = length
            )
        } else if (length <= scratch.size - ProtocolV2.FRAME_HEADER_SIZE) {
            payload.copyInto(scratch, ProtocolV2.FRAME_HEADER_SIZE, offset, offset + length)
            out.write(scratch, 0, ProtocolV2.FRAME_HEADER_SIZE + length)
        } else {
            out.write(scratch, 0, ProtocolV2.FRAME_HEADER_SIZE)
            out.write(payload, offset, length)
        }
    }

    /**
     * Zero-copy path for video: the payload is already in [frame] at index
     * [ProtocolV2.FRAME_HEADER_SIZE]; the header is written in front of it and the whole frame
     * leaves in a single write.
     */
    fun writeInPlace(h: FrameHeader, frame: ByteArray, payloadLength: Int) {
        require(payloadLength >= 0 && frame.size - ProtocolV2.FRAME_HEADER_SIZE >= payloadLength) {
            "frame buffer too small"
        }
        h.length = payloadLength
        check(h)
        encodeHeader(h, frame)
        val total = ProtocolV2.FRAME_HEADER_SIZE + payloadLength
        val recordWriter = aeadWriter
        if (recordWriter != null && phase == Phase.SESSION) {
            recordWriter.writeData(frame, 0, total)
        } else {
            out.write(frame, 0, total)
        }
    }

    /** Encodes [msg] and writes it with the next sequence number. */
    fun writeMessage(msg: Payload, flags: Int = FrameRules.defaultFlags(msg.type)) {
        val size = msg.encodedSize
        header.type = msg.type
        header.flags = flags
        header.seq = nextSeq
        header.length = size
        header.ptsUs = 0
        header.captureUs = 0
        check(header)
        val frame = if (ProtocolV2.FRAME_HEADER_SIZE + size <= scratch.size) {
            scratch
        } else {
            ByteArray(ProtocolV2.FRAME_HEADER_SIZE + size)
        }
        msg.encodeTo(frame, ProtocolV2.FRAME_HEADER_SIZE)
        writeInPlace(header, frame, size)
        nextSeq = (nextSeq + 1) and SEQ_MASK
    }

    private fun check(h: FrameHeader) {
        FrameRules.checkPhase(h.type, phase)
        FrameRules.validate(h, FrameRules.limit(phase, maxPayload))
    }

    private fun encodeHeader(h: FrameHeader, dest: ByteArray) {
        BigEndian.putU8(dest, OFF_TYPE, h.type.id)
        BigEndian.putU8(dest, OFF_FLAGS, h.flags)
        BigEndian.putU16(dest, OFF_SEQ, h.seq)
        BigEndian.putI32(dest, OFF_LENGTH, h.length)
        BigEndian.putI64(dest, OFF_PTS, h.ptsUs)
        BigEndian.putI64(dest, OFF_CAPTURE, h.captureUs)
    }

    private companion object {
        const val SCRATCH_SIZE = 256
        const val SEQ_MASK = 0xFFFF
    }
}
