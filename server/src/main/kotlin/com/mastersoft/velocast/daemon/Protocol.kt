package com.mastersoft.velocast.daemon

import java.io.DataInputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Binary wire protocol shared between VeloCast Daemon (:server) and VeloCast (:client).
 *
 * Video / telemetry stream (Server -> Client):
 *   12-byte header (big-endian):
 *     - Bytes 0..7  : Long  (presentationTimeUs; PTS_CODEC_CONFIG = -1L, PTS_PONG = -2L)
 *     - Bytes 8..11 : Int   (payloadLength in bytes)
 *   Followed immediately by `payloadLength` payload bytes.
 *
 * Control stream (Client -> Server):
 *   Fixed 14-byte packet (big-endian):
 *     - Bytes 0..1  : Short (action code)
 *     - Bytes 2..5  : Int   (pointerId, keyCode, bitrateBps, or pingToken)
 *     - Bytes 6..9  : Float (normalized X in [0.0, 1.0], or target FPS for config)
 *     - Bytes 10..13: Float (normalized Y in [0.0, 1.0], or resolution scale in (0.0, 1.0] for config)
 */
object Protocol {
    const val DEFAULT_PORT = 27183
    const val VIDEO_HEADER_SIZE = 12
    const val CONTROL_PACKET_SIZE = 14

    const val PTS_CODEC_CONFIG = -1L
    const val PTS_PONG = -2L

    const val ACTION_DOWN: Short = 0
    const val ACTION_UP: Short = 1
    const val ACTION_MOVE: Short = 2
    const val ACTION_CANCEL: Short = 3
    const val ACTION_POINTER_DOWN: Short = 5
    const val ACTION_POINTER_UP: Short = 6

    const val ACTION_KEY_PRESS: Short = 100
    const val ACTION_CONFIG_STREAM: Short = 101
    const val ACTION_EXPAND_NOTIFICATIONS: Short = 102
    const val ACTION_ROTATE_SCREEN: Short = 103
    const val ACTION_PING: Short = 104

    data class ControlPacket(
        val action: Short,
        val param: Int,
        val normX: Float,
        val normY: Float
    )

    /**
     * Writes the 12-byte big-endian header into bytes 0..11 of [frameBuffer] (which already
     * holds the NAL payload starting at index 12) and sends the entire frame in a single write.
     */
    fun writeCoalescedFrame(
        out: OutputStream,
        ptsUs: Long,
        frameBuffer: ByteArray,
        payloadLength: Int
    ) {
        putLongBE(frameBuffer, 0, ptsUs)
        putIntBE(frameBuffer, 8, payloadLength)
        out.write(frameBuffer, 0, VIDEO_HEADER_SIZE + payloadLength)
    }

    fun writePongPacket(out: OutputStream, pingToken: Int, pongBuf: ByteArray) {
        putLongBE(pongBuf, 0, PTS_PONG)
        putIntBE(pongBuf, 8, 4)
        putIntBE(pongBuf, 12, pingToken)
        out.write(pongBuf, 0, 16)
    }

    fun readControlPacket(input: DataInputStream, scratch: ByteArray): ControlPacket {
        input.readFully(scratch, 0, CONTROL_PACKET_SIZE)
        val buf = ByteBuffer.wrap(scratch, 0, CONTROL_PACKET_SIZE).order(ByteOrder.BIG_ENDIAN)
        return ControlPacket(
            action = buf.short,
            param = buf.int,
            normX = buf.float,
            normY = buf.float
        )
    }

    private fun putLongBE(buf: ByteArray, offset: Int, value: Long) {
        buf[offset] = (value ushr 56).toByte()
        buf[offset + 1] = (value ushr 48).toByte()
        buf[offset + 2] = (value ushr 40).toByte()
        buf[offset + 3] = (value ushr 32).toByte()
        buf[offset + 4] = (value ushr 24).toByte()
        buf[offset + 5] = (value ushr 16).toByte()
        buf[offset + 6] = (value ushr 8).toByte()
        buf[offset + 7] = value.toByte()
    }

    private fun putIntBE(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = (value ushr 24).toByte()
        buf[offset + 1] = (value ushr 16).toByte()
        buf[offset + 2] = (value ushr 8).toByte()
        buf[offset + 3] = value.toByte()
    }
}
