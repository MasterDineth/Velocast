package com.mastersoft.velocast.protocol

import java.io.InputStream
import java.io.OutputStream

private const val OFF_TYPE = 0
private const val OFF_POINTER = 1
private const val OFF_SEQ = 2
private const val OFF_PARAM = 4
private const val OFF_X = 8
private const val OFF_Y = 12
private const val CLIPBOARD_FLAGS_MAX = 1

/**
 * Reads client to server control packets (16 bytes, plus a payload for HELLO, AUTH_PROOF, TEXT
 * and CLIPBOARD). Not thread-safe. Reading into a reused [ControlPacket] does not allocate.
 * A [ProtocolException] means the stream is desynchronised: close the connection.
 */
class ControlReader(
    private val input: InputStream,
    private val maxPayload: Int = ProtocolV2.MAX_CONTROL_PAYLOAD
) {
    /** Starts in [Phase.HANDSHAKE]; switch to [Phase.SESSION] once authentication succeeded. */
    var phase: Phase = Phase.HANDSHAKE

    private val headerBytes = ByteArray(ProtocolV2.CONTROL_PACKET_SIZE)
    private val buffer = GrowingBuffer()

    init {
        require(maxPayload in 1..ProtocolV2.MAX_CONTROL_PAYLOAD) {
            "maxPayload must be in 1..MAX_CONTROL_PAYLOAD"
        }
    }

    /**
     * Payload of the last packet (first `packet.payloadLength` bytes); valid until the next read.
     */
    val payload: ByteArray get() = buffer.array

    /**
     * Reads and validates the next packet into [packet]. Returns false on a clean end of stream.
     */
    fun read(packet: ControlPacket): Boolean {
        val got = StreamIo.readFully(
            input,
            headerBytes,
            0,
            ProtocolV2.CONTROL_PACKET_SIZE,
            atBoundary = true
        )
        if (got) {
            val spec = decode(packet)
            if (!spec.isEmpty) readPayload(packet, spec)
        }
        return got
    }

    private fun decode(p: ControlPacket): PayloadSpec {
        val typeId = BigEndian.getU8(headerBytes, OFF_TYPE)
        val type =
            MessageType.fromId(typeId)
                ?: fail(ErrorCode.UNKNOWN_MESSAGE_TYPE, "unknown message type $typeId")
        val spec =
            type.clientToServer
                ?: fail(ErrorCode.UNEXPECTED_MESSAGE, "$type is not a client message")
        ControlRules.checkPhase(type, phase)
        p.type = type
        p.pointer = BigEndian.getU8(headerBytes, OFF_POINTER)
        p.seq = BigEndian.getU16(headerBytes, OFF_SEQ)
        p.param = BigEndian.getI32(headerBytes, OFF_PARAM)
        p.x = BigEndian.getF32(headerBytes, OFF_X)
        p.y = BigEndian.getF32(headerBytes, OFF_Y)
        p.payloadLength = 0
        ControlRules.validate(p)
        return spec
    }

    private fun readPayload(p: ControlPacket, spec: PayloadSpec) {
        val length = p.param
        if (length < spec.min) fail(ErrorCode.MALFORMED_MESSAGE, "${p.type} payload too short")
        if (length > minOf(spec.max, ControlRules.limit(phase, maxPayload))) {
            fail(ErrorCode.MESSAGE_TOO_LARGE, "${p.type} payload length $length exceeds the limit")
        }
        buffer.fill(input, length)
        p.payloadLength = length
        checkContent(p)
    }

    private fun checkContent(p: ControlPacket) {
        val data = buffer.array
        when (p.type) {
            MessageType.TEXT -> requireUtf8(Utf8.isValid(data, 0, p.payloadLength))

            MessageType.CLIPBOARD -> {
                if (BigEndian.getU8(data, 0) > CLIPBOARD_FLAGS_MAX) {
                    fail(ErrorCode.MALFORMED_MESSAGE, "clipboard flags must be 0 or 1")
                }
                requireUtf8(Utf8.isValid(data, 1, p.payloadLength - 1))
            }

            else -> Unit
        }
    }

    private fun requireUtf8(valid: Boolean) {
        if (!valid) fail(ErrorCode.INVALID_TEXT, "text is not valid UTF-8 or contains NUL")
    }
}

/**
 * Writes client to server control packets. Not thread-safe: use one writer per connection and
 * serialise calls (or funnel all input through one sender thread). Every packet is validated
 * before a byte is written. The convenience methods do not allocate.
 */
class ControlWriter(private val out: OutputStream) {
    /** Starts in [Phase.HANDSHAKE]; only HELLO, AUTH_PROOF and BYE can be written. */
    var phase: Phase = Phase.HANDSHAKE

    /** Sequence number the next packet gets. */
    var nextSeq: Int = 0

    private val buffer = ByteArray(SCRATCH_SIZE)
    private val packet = ControlPacket()

    fun touch(pointer: Int, action: TouchAction, x: Float, y: Float) {
        writePacket(MessageType.TOUCH, pointer, action.id, x, y)
    }

    fun key(code: Int, action: KeyAction) {
        writePacket(MessageType.KEY, action.id, code, 0f, 0f)
    }

    fun configStream(bitrateBps: Int, fps: Float, scale: Float) {
        writePacket(MessageType.CONFIG_STREAM, 0, bitrateBps, fps, scale)
    }

    fun action(action: DeviceAction) {
        writePacket(MessageType.ACTION, 0, action.id, 0f, 0f)
    }

    fun ping(token: Int) {
        writePacket(MessageType.PING, 0, token, 0f, 0f)
    }

    /** Sends one of the parameterless requests KEYFRAME_REQ, PAUSE or RESUME. */
    fun request(type: MessageType) {
        val allowed =
            type == MessageType.KEYFRAME_REQ || type == MessageType.PAUSE ||
                type == MessageType.RESUME
        if (!allowed) fail(ErrorCode.UNEXPECTED_MESSAGE, "$type is not a parameterless request")
        writePacket(type, 0, 0, 0f, 0f)
    }

    fun bye(code: ErrorCode? = null) {
        writePacket(MessageType.BYE, 0, code?.id ?: 0, 0f, 0f)
    }

    /** Validates and writes a packet that has no payload, using the packet's own `seq`. */
    fun write(p: ControlPacket) {
        ControlRules.checkPhase(p.type, phase)
        val spec =
            p.type.clientToServer
                ?: fail(ErrorCode.UNEXPECTED_MESSAGE, "${p.type} is not a client message")
        if (!spec.isEmpty) {
            fail(
                ErrorCode.MALFORMED_MESSAGE,
                "${p.type} needs a payload: use writePayload"
            )
        }
        ControlRules.validate(p)
        encodeHeader(p.type.id, p.pointer, p.seq, p.param, p.x, p.y, buffer)
        out.write(buffer, 0, ProtocolV2.CONTROL_PACKET_SIZE)
    }

    /** Writes HELLO, AUTH_PROOF, TEXT or CLIPBOARD: header and payload in a single write. */
    fun writePayload(msg: Payload) {
        ControlRules.checkPhase(msg.type, phase)
        val spec =
            msg.type.clientToServer
                ?: fail(ErrorCode.UNEXPECTED_MESSAGE, "${msg.type} is not a client message")
        val size = msg.encodedSize
        val limit = minOf(spec.max, ControlRules.limit(phase, ProtocolV2.MAX_CONTROL_PAYLOAD))
        if (spec.isEmpty || size < spec.min || size > limit) {
            fail(ErrorCode.MALFORMED_MESSAGE, "${msg.type} payload size $size not allowed")
        }
        val frame = if (ProtocolV2.CONTROL_PACKET_SIZE + size <= buffer.size) {
            buffer
        } else {
            ByteArray(ProtocolV2.CONTROL_PACKET_SIZE + size)
        }
        msg.encodeTo(frame, ProtocolV2.CONTROL_PACKET_SIZE)
        encodeHeader(msg.type.id, 0, nextSeq, size, 0f, 0f, frame)
        out.write(frame, 0, ProtocolV2.CONTROL_PACKET_SIZE + size)
        nextSeq = (nextSeq + 1) and SEQ_MASK
    }

    private fun writePacket(type: MessageType, pointer: Int, param: Int, x: Float, y: Float) {
        packet.type = type
        packet.pointer = pointer
        packet.seq = nextSeq
        packet.param = param
        packet.x = x
        packet.y = y
        write(packet)
        nextSeq = (nextSeq + 1) and SEQ_MASK
    }

    private fun encodeHeader(
        typeId: Int,
        pointer: Int,
        seq: Int,
        param: Int,
        x: Float,
        y: Float,
        dest: ByteArray
    ) {
        BigEndian.putU8(dest, OFF_TYPE, typeId)
        BigEndian.putU8(dest, OFF_POINTER, pointer)
        BigEndian.putU16(dest, OFF_SEQ, seq)
        BigEndian.putI32(dest, OFF_PARAM, param)
        BigEndian.putF32(dest, OFF_X, x)
        BigEndian.putF32(dest, OFF_Y, y)
    }

    private companion object {
        const val SCRATCH_SIZE = 272
        const val SEQ_MASK = 0xFFFF
    }
}
