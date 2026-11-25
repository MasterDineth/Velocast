package com.mastersoft.velocast.protocol

private const val ID_HELLO = 0x01
private const val ID_SERVER_HELLO = 0x02
private const val ID_AUTH_PROOF = 0x03
private const val ID_BYE = 0x04
private const val ID_PING = 0x05
private const val ID_PONG = 0x06
private const val ID_VIDEO = 0x10
private const val ID_CONFIG = 0x11
private const val ID_VIDEO_SIZE = 0x12
private const val ID_STATUS = 0x13
private const val ID_ERROR = 0x14
private const val ID_CLIPBOARD = 0x15
private const val ID_DEVICE_INFO = 0x16
private const val ID_TOUCH = 0x20
private const val ID_KEY = 0x21
private const val ID_TEXT = 0x22
private const val ID_CONFIG_STREAM = 0x23
private const val ID_KEYFRAME_REQ = 0x24
private const val ID_PAUSE = 0x25
private const val ID_RESUME = 0x26
private const val ID_ACTION = 0x27
private const val ID_TABLE_SIZE = 256

/** Size limits of the payload of one message in one direction. */
class PayloadSpec(val min: Int, val max: Int) {
    /** True when the message has no payload at all. */
    val isEmpty: Boolean get() = max == 0

    companion object {
        val NONE = PayloadSpec(0, 0)

        fun exactly(size: Int) = PayloadSpec(size, size)
    }
}

/** In which connection phase a message type may appear. */
enum class PhaseRule {
    HANDSHAKE_ONLY,
    ANY_PHASE,
    SESSION_ONLY
    ;

    fun allows(phase: Phase): Boolean = when (this) {
        HANDSHAKE_ONLY -> phase == Phase.HANDSHAKE
        ANY_PHASE -> true
        SESSION_ONLY -> phase == Phase.SESSION
    }
}

private val EMPTY_OR_CODE = PayloadSpec.NONE
private val BIG_FRAME = PayloadSpec(1, ProtocolV2.MAX_PAYLOAD)

/**
 * Every message of the protocol. A type with a [clientToServer] spec may be sent by the client
 * (a 16-byte control packet; when the spec allows a payload, the packet's `param` is the payload
 * length and the payload follows). A type with a [serverToClient] spec may be sent by the server
 * (a 24-byte frame header whose `length` is the payload length).
 */
enum class MessageType(
    val id: Int,
    val clientToServer: PayloadSpec?,
    val serverToClient: PayloadSpec?,
    val phases: PhaseRule
) {
    HELLO(ID_HELLO, PayloadSpec.exactly(ProtocolV2.HELLO_SIZE), null, PhaseRule.HANDSHAKE_ONLY),
    SERVER_HELLO(
        ID_SERVER_HELLO,
        null,
        PayloadSpec.exactly(ProtocolV2.SERVER_HELLO_SIZE),
        PhaseRule.HANDSHAKE_ONLY
    ),
    AUTH_PROOF(
        ID_AUTH_PROOF,
        PayloadSpec.exactly(ProtocolV2.PROOF_SIZE),
        PayloadSpec.exactly(ProtocolV2.PROOF_SIZE),
        PhaseRule.HANDSHAKE_ONLY
    ),
    BYE(ID_BYE, EMPTY_OR_CODE, PayloadSpec.exactly(ProtocolV2.BYE_SIZE), PhaseRule.ANY_PHASE),
    PING(ID_PING, PayloadSpec.NONE, null, PhaseRule.SESSION_ONLY),
    PONG(ID_PONG, null, PayloadSpec.exactly(ProtocolV2.PONG_SIZE), PhaseRule.SESSION_ONLY),
    VIDEO(ID_VIDEO, null, BIG_FRAME, PhaseRule.SESSION_ONLY),
    CONFIG(
        ID_CONFIG,
        null,
        PayloadSpec(ProtocolV2.CONFIG_MIN_SIZE, ProtocolV2.MAX_PAYLOAD),
        PhaseRule.SESSION_ONLY
    ),
    VIDEO_SIZE(
        ID_VIDEO_SIZE,
        null,
        PayloadSpec.exactly(ProtocolV2.VIDEO_SIZE_PAYLOAD_SIZE),
        PhaseRule.SESSION_ONLY
    ),
    STATUS(
        ID_STATUS,
        null,
        PayloadSpec(ProtocolV2.STATUS_FIXED_SIZE, ProtocolV2.STATUS_MAX_SIZE),
        PhaseRule.SESSION_ONLY
    ),
    ERROR(
        ID_ERROR,
        null,
        PayloadSpec(ProtocolV2.ERROR_FIXED_SIZE, ProtocolV2.ERROR_MAX_SIZE),
        PhaseRule.ANY_PHASE
    ),
    CLIPBOARD(
        ID_CLIPBOARD,
        PayloadSpec(ProtocolV2.CLIPBOARD_MIN_SIZE, ProtocolV2.MAX_CLIPBOARD_BYTES),
        PayloadSpec(ProtocolV2.CLIPBOARD_MIN_SIZE, ProtocolV2.MAX_CLIPBOARD_BYTES),
        PhaseRule.SESSION_ONLY
    ),
    DEVICE_INFO(
        ID_DEVICE_INFO,
        null,
        PayloadSpec(ProtocolV2.DEVICE_INFO_MIN_SIZE, ProtocolV2.DEVICE_INFO_MAX_SIZE),
        PhaseRule.SESSION_ONLY
    ),
    TOUCH(ID_TOUCH, PayloadSpec.NONE, null, PhaseRule.SESSION_ONLY),
    KEY(ID_KEY, PayloadSpec.NONE, null, PhaseRule.SESSION_ONLY),
    TEXT(ID_TEXT, PayloadSpec(1, ProtocolV2.MAX_TEXT_BYTES), null, PhaseRule.SESSION_ONLY),
    CONFIG_STREAM(ID_CONFIG_STREAM, PayloadSpec.NONE, null, PhaseRule.SESSION_ONLY),
    KEYFRAME_REQ(ID_KEYFRAME_REQ, PayloadSpec.NONE, null, PhaseRule.SESSION_ONLY),
    PAUSE(ID_PAUSE, PayloadSpec.NONE, null, PhaseRule.SESSION_ONLY),
    RESUME(ID_RESUME, PayloadSpec.NONE, null, PhaseRule.SESSION_ONLY),
    ACTION(ID_ACTION, PayloadSpec.NONE, null, PhaseRule.SESSION_ONLY)
    ;

    companion object {
        private val byId = arrayOfNulls<MessageType>(ID_TABLE_SIZE).also { table ->
            for (type in entries) table[type.id] = type
        }

        /** Looks a type up by its wire id without allocating. */
        fun fromId(id: Int): MessageType? = if (id in byId.indices) byId[id] else null
    }
}
