package com.mastersoft.velocast.protocol

import com.mastersoft.velocast.protocol.VectorSupport.control
import com.mastersoft.velocast.protocol.VectorSupport.frame
import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Test

/** Hostile and malformed inputs must be rejected with the right error code. */
class StrictnessTest {
    private fun expect(code: ErrorCode, block: () -> Unit) {
        try {
            block()
        } catch (e: ProtocolException) {
            assertEquals(code, e.code)
            return
        }
        fail("expected $code")
    }

    private fun readControl(bytes: ByteArray, phase: Phase = Phase.SESSION): ControlPacket {
        val reader = ControlReader(ByteArrayInputStream(bytes)).also { it.phase = phase }
        return ControlPacket().also { reader.read(it) }
    }

    private fun readFrame(
        bytes: ByteArray,
        phase: Phase = Phase.SESSION,
        maxPayload: Int = ProtocolV2.MAX_PAYLOAD
    ) {
        val reader = FrameReader(ByteArrayInputStream(bytes), maxPayload).also { it.phase = phase }
        val h = FrameHeader()
        if (reader.readHeader(h)) reader.readPayload(h)
    }

    private fun c(code: ErrorCode, bytes: ByteArray, phase: Phase = Phase.SESSION) = expect(code) {
        readControl(bytes, phase)
    }

    private fun f(code: ErrorCode, bytes: ByteArray, phase: Phase = Phase.SESSION) = expect(code) {
        readFrame(bytes, phase)
    }

    private val touch = 0x20
    private val key = 0x21
    private val text = 0x22
    private val config = 0x23
    private val pause = 0x25
    private val action = 0x27
    private val ping = 0x05
    private val bye = 0x04
    private val clipboard = 0x15
    private val hello = 0x01

    @Test
    fun endOfStreamHandling() {
        assertFalse(ControlReader(ByteArrayInputStream(ByteArray(0))).read(ControlPacket()))
        assertFalse(FrameReader(ByteArrayInputStream(ByteArray(0))).readHeader(FrameHeader()))
        c(ErrorCode.TRUNCATED, ByteArray(5))
        f(ErrorCode.TRUNCATED, ByteArray(23))
        f(ErrorCode.TRUNCATED, frame(0x10, length = 10, payload = ByteArray(9)))
        c(
            ErrorCode.TRUNCATED,
            control(
                text,
                param = 5,
                payload = ByteArray(4) {
                    'a'.code.toByte()
                }
            )
        )
    }

    @Test
    fun controlTypeAndPhaseRules() {
        c(ErrorCode.UNKNOWN_MESSAGE_TYPE, control(0x99))
        c(ErrorCode.UNKNOWN_MESSAGE_TYPE, control(0))
        c(ErrorCode.UNEXPECTED_MESSAGE, control(0x10)) // VIDEO is server to client
        c(
            ErrorCode.UNEXPECTED_MESSAGE,
            control(hello, param = ProtocolV2.HELLO_SIZE, payload = ByteArray(26))
        )
        c(ErrorCode.UNEXPECTED_MESSAGE, control(touch, param = 0), Phase.HANDSHAKE)
        c(ErrorCode.UNEXPECTED_MESSAGE, control(ping), Phase.HANDSHAKE)
    }

    @Test
    fun touchRules() {
        c(ErrorCode.INVALID_POINTER, control(touch, pointer = 32))
        c(ErrorCode.INVALID_POINTER, control(touch, pointer = 255))
        c(ErrorCode.VALUE_OUT_OF_RANGE, control(touch, param = 4))
        c(ErrorCode.VALUE_OUT_OF_RANGE, control(touch, param = -1))
        c(ErrorCode.INVALID_COORDINATE, control(touch, x = Float.NaN))
        c(ErrorCode.INVALID_COORDINATE, control(touch, y = Float.POSITIVE_INFINITY))
        c(ErrorCode.INVALID_COORDINATE, control(touch, x = 1.0001f))
        c(ErrorCode.INVALID_COORDINATE, control(touch, y = -0.0001f))
        readControl(control(touch, pointer = 31, param = 3, x = 1f, y = 0f))
    }

    @Test
    fun keyRules() {
        readControl(control(key, param = KeyCodes.POWER))
        c(ErrorCode.KEY_NOT_ALLOWED, control(key, param = 5)) // CALL
        c(ErrorCode.KEY_NOT_ALLOWED, control(key, param = 6)) // ENDCALL
        c(ErrorCode.KEY_NOT_ALLOWED, control(key, param = -1))
        c(ErrorCode.KEY_NOT_ALLOWED, control(key, param = 1000))
        c(ErrorCode.VALUE_OUT_OF_RANGE, control(key, pointer = 2, param = KeyCodes.BACK))
        c(ErrorCode.MALFORMED_MESSAGE, control(key, param = KeyCodes.BACK, x = 0.5f))
    }

    @Test
    fun configStreamRules() {
        readControl(control(config, param = 1_000_000, x = 15f, y = 0.5f))
        readControl(control(config, param = 40_000_000, x = 120f, y = 1f))
        c(ErrorCode.VALUE_OUT_OF_RANGE, control(config, param = 999_999, x = 30f, y = 1f))
        c(ErrorCode.VALUE_OUT_OF_RANGE, control(config, param = 40_000_001, x = 30f, y = 1f))
        c(ErrorCode.VALUE_OUT_OF_RANGE, control(config, param = 8_000_000, x = 14f, y = 1f))
        c(ErrorCode.VALUE_OUT_OF_RANGE, control(config, param = 8_000_000, x = 121f, y = 1f))
        c(ErrorCode.VALUE_OUT_OF_RANGE, control(config, param = 8_000_000, x = 30.5f, y = 1f))
        c(ErrorCode.VALUE_OUT_OF_RANGE, control(config, param = 8_000_000, x = Float.NaN, y = 1f))
        c(ErrorCode.VALUE_OUT_OF_RANGE, control(config, param = 8_000_000, x = 30f, y = 0.49f))
        c(ErrorCode.VALUE_OUT_OF_RANGE, control(config, param = 8_000_000, x = 30f, y = 1.01f))
        c(ErrorCode.VALUE_OUT_OF_RANGE, control(config, param = 8_000_000, x = 30f, y = Float.NaN))
        c(
            ErrorCode.MALFORMED_MESSAGE,
            control(config, pointer = 1, param = 8_000_000, x = 30f, y = 1f)
        )
    }

    @Test
    fun otherControlRules() {
        c(ErrorCode.VALUE_OUT_OF_RANGE, control(action, param = 0))
        c(ErrorCode.VALUE_OUT_OF_RANGE, control(action, param = 10))
        c(ErrorCode.MALFORMED_MESSAGE, control(pause, param = 1))
        c(ErrorCode.MALFORMED_MESSAGE, control(pause, pointer = 1))
        c(ErrorCode.MALFORMED_MESSAGE, control(ping, x = 0.1f))
        c(ErrorCode.MALFORMED_MESSAGE, control(ping, x = -0.0f)) // canonical zero only
        c(ErrorCode.VALUE_OUT_OF_RANGE, control(bye, param = 999))
        readControl(control(bye, param = ErrorCode.LINK_LOST.id))
    }

    @Test
    fun textAndClipboardRules() {
        val a = "a".toByteArray()
        c(ErrorCode.MALFORMED_MESSAGE, control(text, param = 0))
        c(ErrorCode.MALFORMED_MESSAGE, control(text, param = -5))
        c(ErrorCode.MESSAGE_TOO_LARGE, control(text, param = ProtocolV2.MAX_TEXT_BYTES + 1))
        c(ErrorCode.INVALID_TEXT, control(text, param = 2, payload = byteArrayOf(0x61, 0x00)))
        c(
            ErrorCode.INVALID_TEXT,
            control(text, param = 2, payload = byteArrayOf(0xC3.toByte(), 0x28))
        )
        c(
            ErrorCode.INVALID_TEXT,
            control(
                text,
                param = 3,
                payload = byteArrayOf(0xED.toByte(), 0xA0.toByte(), 0x80.toByte())
            )
        )
        c(
            ErrorCode.INVALID_TEXT,
            control(text, param = 2, payload = byteArrayOf(0xC0.toByte(), 0x80.toByte()))
        )
        readControl(control(text, param = 1, payload = a))
        c(ErrorCode.MALFORMED_MESSAGE, control(clipboard, param = 0))
        c(
            ErrorCode.MALFORMED_MESSAGE,
            control(clipboard, param = 2, payload = byteArrayOf(2, 0x61))
        )
        c(
            ErrorCode.MESSAGE_TOO_LARGE,
            control(clipboard, param = ProtocolV2.MAX_CLIPBOARD_BYTES + 1)
        )
        c(
            ErrorCode.INVALID_TEXT,
            control(clipboard, param = 2, payload = byteArrayOf(0, 0xFF.toByte()))
        )
        readControl(control(clipboard, param = 1, payload = byteArrayOf(0)))
    }

    @Test
    fun handshakeControlLimits() {
        val sizeOk = control(hello, param = 26, payload = ByteArray(26))
        readControl(sizeOk, Phase.HANDSHAKE)
        c(
            ErrorCode.MESSAGE_TOO_LARGE,
            control(hello, param = 27, payload = ByteArray(27)),
            Phase.HANDSHAKE
        )
        c(
            ErrorCode.MALFORMED_MESSAGE,
            control(hello, param = 25, payload = ByteArray(25)),
            Phase.HANDSHAKE
        )
        c(ErrorCode.MESSAGE_TOO_LARGE, control(hello, param = Int.MAX_VALUE), Phase.HANDSHAKE)
        c(
            ErrorCode.MALFORMED_MESSAGE,
            control(hello, pointer = 1, param = 26, payload = ByteArray(26)),
            Phase.HANDSHAKE
        )
    }

    @Test
    fun frameTypeFlagAndLengthRules() {
        f(ErrorCode.UNKNOWN_MESSAGE_TYPE, frame(0x99, length = 0))
        f(ErrorCode.UNEXPECTED_MESSAGE, frame(touch, length = 0)) // client type
        f(
            ErrorCode.MALFORMED_MESSAGE,
            frame(0x10, flags = 0x10, length = 1, payload = ByteArray(1))
        )
        f(
            ErrorCode.MALFORMED_MESSAGE,
            frame(0x10, flags = FrameFlags.CONFIG, length = 1, payload = ByteArray(1))
        )
        f(
            ErrorCode.MALFORMED_MESSAGE,
            frame(0x10, flags = FrameFlags.RESET, length = 1, payload = ByteArray(1))
        )
        f(
            ErrorCode.MALFORMED_MESSAGE,
            frame(0x06, flags = FrameFlags.KEYFRAME, length = 12, payload = ByteArray(12))
        )
        f(ErrorCode.MALFORMED_MESSAGE, frame(0x11, flags = 0, length = 2, payload = ByteArray(2)))
        f(ErrorCode.MALFORMED_MESSAGE, frame(0x14, flags = 0, length = 4, payload = ByteArray(4)))
        f(ErrorCode.MALFORMED_MESSAGE, frame(0x10, length = 0))
        f(ErrorCode.MESSAGE_TOO_LARGE, frame(0x10, length = ProtocolV2.MAX_PAYLOAD + 1))
        f(ErrorCode.MESSAGE_TOO_LARGE, frame(0x10, length = -1))
        f(ErrorCode.MESSAGE_TOO_LARGE, frame(0x10, length = Int.MIN_VALUE))
        f(ErrorCode.VALUE_OUT_OF_RANGE, frame(0x10, length = 1, pts = -1, payload = ByteArray(1)))
        f(
            ErrorCode.VALUE_OUT_OF_RANGE,
            frame(0x10, length = 1, capture = -1, payload = ByteArray(1))
        )
        f(ErrorCode.MALFORMED_MESSAGE, frame(0x13, length = 6, pts = 5, payload = ByteArray(6)))
        f(ErrorCode.MALFORMED_MESSAGE, frame(0x12, length = 8, payload = ByteArray(8)))
        f(ErrorCode.MESSAGE_TOO_LARGE, frame(0x12, length = 10, payload = ByteArray(10)))
    }

    @Test
    fun frameHandshakeRules() {
        f(
            ErrorCode.UNEXPECTED_MESSAGE,
            frame(0x10, length = 1, payload = ByteArray(1)),
            Phase.HANDSHAKE
        )
        f(
            ErrorCode.UNEXPECTED_MESSAGE,
            frame(0x06, length = 12, payload = ByteArray(12)),
            Phase.HANDSHAKE
        )
        f(ErrorCode.UNEXPECTED_MESSAGE, frame(0x02, length = 40, payload = ByteArray(40)))
        f(
            ErrorCode.MESSAGE_TOO_LARGE,
            frame(0x03, length = 33, payload = ByteArray(33)),
            Phase.HANDSHAKE
        )
        f(
            ErrorCode.MALFORMED_MESSAGE,
            frame(0x03, length = 31, payload = ByteArray(31)),
            Phase.HANDSHAKE
        )
        f(ErrorCode.MESSAGE_TOO_LARGE, frame(0x02, length = 129), Phase.HANDSHAKE)
    }

    @Test
    fun readerPayloadCapIsEnforcedBeforeReadingThePayload() {
        val input = ByteArrayInputStream(frame(0x10, length = 4_000_000, payload = ByteArray(100)))
        val reader = FrameReader(input, maxPayload = 1024).also { it.phase = Phase.SESSION }
        expect(ErrorCode.MESSAGE_TOO_LARGE) { reader.readHeader(FrameHeader()) }
        assertEquals(100, input.available())
    }

    @Test
    fun framesCannotBeWrittenBeforeTheSessionStarts() {
        val writer = FrameWriter(java.io.ByteArrayOutputStream())
        val h = FrameHeader().also { it.type = MessageType.VIDEO }
        expect(ErrorCode.UNEXPECTED_MESSAGE) { writer.write(h, ByteArray(4), 0, 4) }
        expect(ErrorCode.UNEXPECTED_MESSAGE) { writer.writeMessage(Pong(1, 2)) }
        val control = ControlWriter(java.io.ByteArrayOutputStream())
        expect(ErrorCode.UNEXPECTED_MESSAGE) { control.touch(0, TouchAction.DOWN, 0f, 0f) }
    }

    @Test
    fun payloadDecodersRejectBadPayloads() {
        val hello = Hello(2, 0, ByteArray(16)).encode()
        val badMagic = hello.copyOf().also { it[0] = 'X'.code.toByte() }
        expect(ErrorCode.BAD_MAGIC) { Hello.decode(badMagic, 0, badMagic.size) }
        expect(ErrorCode.MALFORMED_MESSAGE) { Hello.decode(hello + 0, 0, hello.size + 1) }
        expect(ErrorCode.MALFORMED_MESSAGE) { Hello.decode(hello, 0, hello.size - 1) }
        expect(ErrorCode.MALFORMED_MESSAGE) { Hello.decode(hello, 5, hello.size) }
        expect(ErrorCode.MALFORMED_MESSAGE) { Hello.decode(hello, -1, 4) }
        val status = Status(StatusKind.PAUSED, 1).encode()
        status[4] = 5 // value low byte
        expect(ErrorCode.VALUE_OUT_OF_RANGE) { Status.decode(status, 0, status.size) }
        expect(ErrorCode.VALUE_OUT_OF_RANGE) { Status.decode(byteArrayOf(99, 0, 0, 0, 0, 0), 0, 6) }
        expect(ErrorCode.VALUE_OUT_OF_RANGE) { ErrorMessage.decode(byteArrayOf(0, 77, 0, 0), 0, 4) }
        expect(ErrorCode.MALFORMED_MESSAGE) { ErrorMessage.decode(byteArrayOf(0, 9, 2, 0), 0, 4) }
        expect(ErrorCode.MESSAGE_TOO_LARGE) { Status(StatusKind.DEGRADED, 1, "x".repeat(97)) }
        expect(ErrorCode.INVALID_TEXT) { Status(StatusKind.DEGRADED, 1, "a\u0000b") }
        expect(ErrorCode.VALUE_OUT_OF_RANGE) { VideoSize(0, 100, DisplayGeometry(1, 1, 0)) }
        expect(ErrorCode.VALUE_OUT_OF_RANGE) { DisplayGeometry(100, 100, 4) }
        expect(ErrorCode.VALUE_OUT_OF_RANGE) { CodecConfig.decode(byteArrayOf(9, 1), 0, 2) }
        expect(ErrorCode.VALUE_OUT_OF_RANGE) { Pong(1, -1) }
    }
}
