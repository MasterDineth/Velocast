package com.mastersoft.velocast.protocol

/**
 * Constants of VeloCast wire protocol v2. The authoritative description is `docs/protocol.md`;
 * the tests keep the two in sync.
 */
object ProtocolV2 {
    const val DEFAULT_PORT = 27183

    /** Highest protocol version this library speaks. */
    const val VERSION = 2

    /** Lowest protocol version this library still accepts. */
    const val MIN_VERSION = 2

    /** ASCII "VLC2" as a big-endian integer. */
    const val MAGIC = 0x564C4332

    const val NONCE_SIZE = 16
    const val PROOF_SIZE = 32

    /** Server to client frame header size. */
    const val FRAME_HEADER_SIZE = 24

    /** Client to server control packet size. */
    const val CONTROL_PACKET_SIZE = 16

    /** Maximum payload of any server to client frame (4 MiB). */
    const val MAX_PAYLOAD = 4 * 1024 * 1024

    /** Maximum payload that follows a client to server control packet (256 KiB). */
    const val MAX_CONTROL_PAYLOAD = 256 * 1024

    /** Payload cap in effect until the handshake has completed (nothing big before auth). */
    const val MAX_HANDSHAKE_PAYLOAD = 256

    const val MAX_TEXT_BYTES = 4096
    const val MAX_CLIPBOARD_BYTES = MAX_CONTROL_PAYLOAD
    const val MAX_NAME_BYTES = 48
    const val MAX_STATUS_DETAIL_BYTES = 96
    const val MAX_ERROR_DETAIL_BYTES = 160

    const val MAX_POINTER_ID = 31
    const val MIN_BITRATE_BPS = 1_000_000
    const val MAX_BITRATE_BPS = 40_000_000
    const val MIN_FPS = 15
    const val MAX_FPS = 120
    const val MIN_SCALE = 0.5f
    const val MAX_SCALE = 1.0f
    const val MAX_DIMENSION = 16_384
    const val MAX_ROTATION = 3

    const val DISPLAY_GEOMETRY_SIZE = 5
    const val HELLO_SIZE = 4 + 2 + 4 + NONCE_SIZE
    const val SERVER_HELLO_SIZE = 2 + 4 + NONCE_SIZE
    const val DEVICE_INFO_FIXED_SIZE = 2 + 1 + DISPLAY_GEOMETRY_SIZE
    const val DEVICE_INFO_MIN_SIZE = DEVICE_INFO_FIXED_SIZE + 2
    const val DEVICE_INFO_MAX_SIZE = DEVICE_INFO_FIXED_SIZE + 2 * (1 + MAX_NAME_BYTES)
    const val VIDEO_SIZE_PAYLOAD_SIZE = 4 + DISPLAY_GEOMETRY_SIZE
    const val PONG_SIZE = 4 + 8
    const val BYE_SIZE = 2
    const val STATUS_FIXED_SIZE = 1 + 4 + 1
    const val STATUS_MAX_SIZE = STATUS_FIXED_SIZE + MAX_STATUS_DETAIL_BYTES
    const val ERROR_FIXED_SIZE = 2 + 1 + 1
    const val ERROR_MAX_SIZE = ERROR_FIXED_SIZE + MAX_ERROR_DETAIL_BYTES
    const val CONFIG_MIN_SIZE = 2
    const val CLIPBOARD_MIN_SIZE = 1
}
