package com.mastersoft.velocast

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Binary wire protocol matching VeloCast Daemon (:server).
 */
object Protocol {
    const val DEFAULT_HOST = ""
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

    fun encodeControlPacket(action: Short, param: Int, normX: Float, normY: Float): ByteArray =
        ByteBuffer.allocate(CONTROL_PACKET_SIZE)
            .order(ByteOrder.BIG_ENDIAN)
            .putShort(action)
            .putInt(param)
            .putFloat(normX)
            .putFloat(normY)
            .array()
}
