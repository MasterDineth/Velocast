package com.mastersoft.velocast.daemon

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class ProtocolTest {
    @Test
    fun writeCoalescedFrameWritesBigEndianHeaderAndPayload() {
        val payload = byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x67, 0x42)
        val frameBuf = ByteArray(Protocol.VIDEO_HEADER_SIZE + payload.size)
        System.arraycopy(payload, 0, frameBuf, Protocol.VIDEO_HEADER_SIZE, payload.size)

        val out = ByteArrayOutputStream()
        Protocol.writeCoalescedFrame(
            out = out,
            ptsUs = 123_456_789L,
            frameBuffer = frameBuf,
            payloadLength = payload.size
        )

        val bytes = out.toByteArray()
        assertEquals(Protocol.VIDEO_HEADER_SIZE + payload.size, bytes.size)

        val header = ByteBuffer.wrap(
            bytes,
            0,
            Protocol.VIDEO_HEADER_SIZE
        ).order(ByteOrder.BIG_ENDIAN)
        assertEquals(123_456_789L, header.long)
        assertEquals(payload.size, header.int)

        val actualPayload = bytes.copyOfRange(Protocol.VIDEO_HEADER_SIZE, bytes.size)
        assertArrayEquals(payload, actualPayload)
    }

    @Test
    fun writePongPacketWritesPongHeaderAndToken() {
        val out = ByteArrayOutputStream()
        val pongBuf = ByteArray(16)
        Protocol.writePongPacket(out = out, pingToken = 42, pongBuf = pongBuf)

        val buf = ByteBuffer.wrap(out.toByteArray()).order(ByteOrder.BIG_ENDIAN)
        assertEquals(Protocol.PTS_PONG, buf.long)
        assertEquals(4, buf.int)
        assertEquals(42, buf.int)
    }

    @Test
    fun readControlPacketParsesBigEndianFields() {
        val raw = ByteBuffer.allocate(Protocol.CONTROL_PACKET_SIZE)
            .order(ByteOrder.BIG_ENDIAN)
            .putShort(Protocol.ACTION_CONFIG_STREAM)
            .putInt(12_000_000)
            .putFloat(60f)
            .putFloat(0.75f)
            .array()

        val input = DataInputStream(ByteArrayInputStream(raw))
        val packet = Protocol.readControlPacket(input, ByteArray(Protocol.CONTROL_PACKET_SIZE))

        assertEquals(Protocol.ACTION_CONFIG_STREAM, packet.action)
        assertEquals(12_000_000, packet.param)
        assertEquals(60f, packet.normX, 0.0001f)
        assertEquals(0.75f, packet.normY, 0.0001f)
    }
}
