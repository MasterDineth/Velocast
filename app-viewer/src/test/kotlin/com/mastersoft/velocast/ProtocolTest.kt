package com.mastersoft.velocast

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Test

class ProtocolTest {
    @Test
    fun encodeControlPacketSerializesBigEndianFields() {
        val encoded = Protocol.encodeControlPacket(
            action = Protocol.ACTION_DOWN,
            param = 3,
            normX = 0.25f,
            normY = 0.75f
        )

        assertEquals(Protocol.CONTROL_PACKET_SIZE, encoded.size)
        val buf = ByteBuffer.wrap(encoded).order(ByteOrder.BIG_ENDIAN)
        assertEquals(Protocol.ACTION_DOWN, buf.short)
        assertEquals(3, buf.int)
        assertEquals(0.25f, buf.float, 0.0001f)
        assertEquals(0.75f, buf.float, 0.0001f)
    }
}
