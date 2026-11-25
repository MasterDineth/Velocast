package com.mastersoft.velocast.protocol

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.random.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoundTripTest {
    private val rnd = Random(42)

    @Test
    fun payloadLessControlPacketsRoundTrip() {
        repeat(20_000) {
            val p = Gen.controlPacket(rnd)
            val out = ByteArrayOutputStream()
            ControlWriter(out).also { it.phase = Phase.SESSION }.write(p)
            assertEquals(ProtocolV2.CONTROL_PACKET_SIZE, out.size())
            val back = ControlPacket()
            val reader = ControlReader(ByteArrayInputStream(out.toByteArray())).also {
                it.phase =
                    Phase.SESSION
            }
            assertTrue(reader.read(back))
            assertEquals(p.type, back.type)
            assertEquals(p.pointer, back.pointer)
            assertEquals(p.seq, back.seq)
            assertEquals(p.param, back.param)
            assertEquals(p.x.toRawBits(), back.x.toRawBits())
            assertEquals(p.y.toRawBits(), back.y.toRawBits())
            assertEquals(0, back.payloadLength)
            assertFalse(reader.read(back))
        }
    }

    @Test
    fun clientPayloadMessagesRoundTrip() {
        repeat(5_000) {
            val msg = Gen.clientPayload(rnd)
            val out = ByteArrayOutputStream()
            val phase = if (msg.type.phases ==
                PhaseRule.HANDSHAKE_ONLY
            ) {
                Phase.HANDSHAKE
            } else {
                Phase.SESSION
            }
            ControlWriter(out).also { it.phase = phase }.writePayload(msg)
            val p = ControlPacket()
            val reader = ControlReader(ByteArrayInputStream(out.toByteArray())).also {
                it.phase =
                    phase
            }
            assertTrue(reader.read(p))
            assertEquals(msg.type, p.type)
            assertEquals(msg.encodedSize, p.payloadLength)
            assertArrayEquals(msg.encode(), reader.payload.copyOf(p.payloadLength))
            assertEquals(
                msg,
                VectorSupport.decodeClientPayload(p.type, reader.payload, p.payloadLength)
            )
        }
    }

    @Test
    fun serverMessagesRoundTrip() {
        repeat(5_000) {
            val msg = Gen.serverPayload(rnd)
            val out = ByteArrayOutputStream()
            val phase = if (msg.type.phases ==
                PhaseRule.HANDSHAKE_ONLY
            ) {
                Phase.HANDSHAKE
            } else {
                Phase.SESSION
            }
            val writer = FrameWriter(out).also {
                it.phase = phase
                it.nextSeq = 65_535
            }
            writer.writeMessage(msg)
            assertEquals(0, writer.nextSeq)
            val h = FrameHeader()
            val reader = FrameReader(ByteArrayInputStream(out.toByteArray())).also {
                it.phase =
                    phase
            }
            assertTrue(reader.readHeader(h))
            assertEquals(65_535, h.seq)
            assertEquals(msg.type, h.type)
            assertEquals(FrameRules.defaultFlags(msg.type), h.flags)
            reader.readPayload(h)
            assertEquals(msg, VectorSupport.decodeServerPayload(h.type, reader.payload, h.length))
        }
    }

    @Test
    fun videoFramesRoundTripOnEveryWritePath() {
        val sizes = intArrayOf(1, 100, 232, 233, 4_096, 70_000, 1_000_000)
        for (size in sizes) {
            val payload = rnd.nextBytes(size)
            val h = FrameHeader().also {
                it.type = MessageType.VIDEO
                it.flags = FrameFlags.KEYFRAME
                it.seq = 9
                it.ptsUs = 123_456_789L
                it.captureUs = 123_000_000L
            }
            val viaCopy = ByteArrayOutputStream()
            FrameWriter(viaCopy).also { it.phase = Phase.SESSION }.write(h, payload, 0, size)
            val inPlace = ByteArrayOutputStream()
            val frame = ByteArray(ProtocolV2.FRAME_HEADER_SIZE + size + 10)
            payload.copyInto(frame, ProtocolV2.FRAME_HEADER_SIZE)
            FrameWriter(inPlace).also { it.phase = Phase.SESSION }.writeInPlace(h, frame, size)
            assertArrayEquals("size $size", viaCopy.toByteArray(), inPlace.toByteArray())

            val reader = FrameReader(ByteArrayInputStream(viaCopy.toByteArray())).also {
                it.phase =
                    Phase.SESSION
            }
            val back = FrameHeader()
            assertTrue(reader.readHeader(back))
            val dest = ByteArray(size + 7)
            reader.readPayload(back, dest, 7)
            assertArrayEquals(payload, dest.copyOfRange(7, 7 + size))
            assertEquals(123_456_789L, back.ptsUs)
            assertEquals(123_000_000L, back.captureUs)
            assertTrue(back.isKeyframe)
            assertFalse(reader.readHeader(back))
        }
    }

    @Test
    fun maximumSizedFrameRoundTrips() {
        val payload = ByteArray(ProtocolV2.MAX_PAYLOAD) { it.toByte() }
        val h = FrameHeader().also { it.type = MessageType.VIDEO }
        val out = ByteArrayOutputStream()
        FrameWriter(out).also { it.phase = Phase.SESSION }.write(h, payload, 0, payload.size)
        val reader = FrameReader(ByteArrayInputStream(out.toByteArray())).also {
            it.phase =
                Phase.SESSION
        }
        val back = FrameHeader()
        assertTrue(reader.readHeader(back))
        assertEquals(ProtocolV2.MAX_PAYLOAD, reader.readPayload(back))
        assertTrue(reader.payload.size <= ProtocolV2.MAX_PAYLOAD)
        assertArrayEquals(payload, reader.payload.copyOf(back.length))
    }

    @Test
    fun largeClientMessagesRoundTrip() {
        val text = "é".repeat(ProtocolV2.MAX_TEXT_BYTES / 2)
        val clip = ClipboardMessage(false, "x".repeat(ProtocolV2.MAX_CLIPBOARD_BYTES - 1))
        for (msg in listOf<Payload>(TextInput(text), clip)) {
            val out = ByteArrayOutputStream()
            ControlWriter(out).also { it.phase = Phase.SESSION }.writePayload(msg)
            val p = ControlPacket()
            val reader = ControlReader(ByteArrayInputStream(out.toByteArray())).also {
                it.phase =
                    Phase.SESSION
            }
            assertTrue(reader.read(p))
            assertEquals(
                msg,
                VectorSupport.decodeClientPayload(p.type, reader.payload, p.payloadLength)
            )
        }
    }

    @Test
    fun sequenceNumbersWrapAt16Bits() {
        val out = ByteArrayOutputStream()
        val writer = ControlWriter(out).also {
            it.phase = Phase.SESSION
            it.nextSeq = 65_534
        }
        repeat(3) { writer.ping(it) }
        val reader = ControlReader(ByteArrayInputStream(out.toByteArray())).also {
            it.phase =
                Phase.SESSION
        }
        val p = ControlPacket()
        val seen = mutableListOf<Int>()
        while (reader.read(p)) seen += p.seq
        assertEquals(listOf(65_534, 65_535, 0), seen)
    }

    @Test
    fun writersRefuseInvalidMessagesBeforeWritingAnything() {
        val out = ByteArrayOutputStream()
        val writer = ControlWriter(out).also { it.phase = Phase.SESSION }
        for (block in listOf<() -> Unit>(
            { writer.touch(32, TouchAction.DOWN, 0.5f, 0.5f) },
            { writer.touch(0, TouchAction.DOWN, Float.NaN, 0.5f) },
            { writer.key(5, KeyAction.DOWN) },
            { writer.configStream(500, 60f, 1f) },
            { writer.request(MessageType.PING) }
        )) {
            try {
                block()
                org.junit.Assert.fail("expected ProtocolException")
            } catch (_: ProtocolException) {
                // expected
            }
        }
        assertEquals(0, out.size())
    }
}
