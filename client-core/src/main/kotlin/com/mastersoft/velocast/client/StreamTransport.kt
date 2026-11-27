package com.mastersoft.velocast.client

import com.mastersoft.velocast.protocol.AuthProof
import com.mastersoft.velocast.protocol.ClipboardMessage
import com.mastersoft.velocast.protocol.ControlPacket
import com.mastersoft.velocast.protocol.DeviceAction
import com.mastersoft.velocast.protocol.ErrorCode
import com.mastersoft.velocast.protocol.FrameFlags
import com.mastersoft.velocast.protocol.Hello
import com.mastersoft.velocast.protocol.KeyAction
import com.mastersoft.velocast.protocol.MessageType
import com.mastersoft.velocast.protocol.Phase
import com.mastersoft.velocast.protocol.TextInput
import com.mastersoft.velocast.protocol.TouchAction
import kotlinx.coroutines.CoroutineScope

/**
 * One decoded server-to-client frame delivered by [StreamTransport.readFrame].
 *
 * `VIDEO` frames may be backed by a pooled byte array from [FrameBufferPool]; consumers must invoke
 * [release] once finished reading [payload] (or [copyUnpooled] if retaining beyond the callback).
 */
class ReceivedFrame(
    type: MessageType,
    flags: Int,
    seq: Int,
    ptsUs: Long,
    captureUs: Long,
    payload: ByteArray,
    payloadLength: Int = payload.size,
    receivedAtUs: Long = 0L
) {
    var type: MessageType = type
        internal set

    var flags: Int = flags
        internal set

    var seq: Int = seq
        internal set

    var ptsUs: Long = ptsUs
        internal set

    var captureUs: Long = captureUs
        internal set

    var payload: ByteArray = payload
        internal set

    var payloadLength: Int = payloadLength
        internal set

    var receivedAtUs: Long = receivedAtUs
        internal set

    private var poolSlot: FrameBufferPool.Slot? = null
    private var poolEpoch: Int = 0

    val isKeyframe: Boolean
        get() = (flags and FrameFlags.KEYFRAME) != 0

    val isReset: Boolean
        get() = (flags and FrameFlags.RESET) != 0

    internal fun bindFromPool(
        slot: FrameBufferPool.Slot,
        epoch: Int,
        buffer: ByteArray,
        payloadLength: Int
    ) {
        this.poolSlot = slot
        this.poolEpoch = epoch
        this.payload = buffer
        this.payloadLength = payloadLength
        this.type = MessageType.VIDEO
        this.flags = 0
        this.seq = 0
        this.ptsUs = 0L
        this.captureUs = 0L
        this.receivedAtUs = 0L
    }

    internal fun updateHeader(
        type: MessageType,
        flags: Int,
        seq: Int,
        ptsUs: Long,
        captureUs: Long,
        receivedAtUs: Long
    ) {
        this.type = type
        this.flags = flags
        this.seq = seq
        this.ptsUs = ptsUs
        this.captureUs = captureUs
        this.receivedAtUs = receivedAtUs
    }

    /** Returns the underlying buffer to [FrameBufferPool] if pooled; idempotent. */
    fun release() {
        val slot = poolSlot ?: return
        val epoch = poolEpoch
        poolSlot = null
        slot.pool.releaseSlot(slot, epoch)
    }

    /** Creates an independent unpooled copy safe to retain after [release]. */
    fun copyUnpooled(): ReceivedFrame = ReceivedFrame(
        type = type,
        flags = flags,
        seq = seq,
        ptsUs = ptsUs,
        captureUs = captureUs,
        payload = payload.copyOf(payloadLength),
        payloadLength = payloadLength,
        receivedAtUs = receivedAtUs
    )

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ReceivedFrame) return false
        if (!headerEquals(other)) return false
        for (i in 0 until payloadLength) {
            if (payload[i] != other.payload[i]) return false
        }
        return true
    }

    private fun headerEquals(other: ReceivedFrame): Boolean {
        val metaMatch = type == other.type && flags == other.flags && seq == other.seq
        val timingMatch = ptsUs == other.ptsUs && captureUs == other.captureUs
        return metaMatch && timingMatch && payloadLength == other.payloadLength
    }

    override fun hashCode(): Int {
        var result = type.hashCode()
        result = 31 * result + flags
        result = 31 * result + seq
        result = 31 * result + ptsUs.hashCode()
        result = 31 * result + captureUs.hashCode()
        var payloadHash = 1
        for (i in 0 until payloadLength) {
            payloadHash = 31 * payloadHash + payload[i]
        }
        return 31 * result + payloadHash
    }

    override fun toString(): String =
        "ReceivedFrame(type=$type, flags=$flags, seq=$seq, ptsUs=$ptsUs, " +
            "captureUs=$captureUs, payloadLength=$payloadLength)"
}

/** Client-to-server control packets written through [StreamTransport.writeControl]. */
sealed interface ControlCommand {
    data class HelloCommand(val message: Hello) : ControlCommand

    data class AuthProofCommand(val message: AuthProof) : ControlCommand

    data class Touch(val pointer: Int, val action: TouchAction, val x: Float, val y: Float) :
        ControlCommand

    data class Key(val keyCode: Int, val action: KeyAction) : ControlCommand

    data class Text(val message: TextInput) : ControlCommand

    data class Clipboard(val message: ClipboardMessage) : ControlCommand

    data class ConfigStream(val bitrateBps: Int, val fps: Float, val scale: Float) : ControlCommand

    data object KeyframeRequest : ControlCommand

    data object Pause : ControlCommand

    data object Resume : ControlCommand

    data class Action(val action: DeviceAction) : ControlCommand

    data class Ping(val token: Int) : ControlCommand

    data class Bye(val code: ErrorCode? = null) : ControlCommand
}

/**
 * Bidirectional protocol v2 stream transport used by [SessionEngine].
 */
interface StreamTransport {
    suspend fun connect(endpoint: ServerEndpoint, readTimeoutMs: Long, scope: CoroutineScope)

    suspend fun setPhase(phase: Phase)

    suspend fun readFrame(): ReceivedFrame?

    suspend fun writeControl(command: ControlCommand)

    /**
     * Allocation-free control packet path for fixed-size session control packets (`TOUCH`, `KEY`,
     * `PING`, `KEYFRAME_REQ`, etc.). Defaults to delegating to [writeControl] for test doubles.
     */
    suspend fun writeControlPacket(packet: ControlPacket) {
        writeControl(packet.toControlCommand())
    }

    suspend fun close()
}

internal fun ControlPacket.toControlCommand(): ControlCommand = when (type) {
    MessageType.TOUCH -> ControlCommand.Touch(pointer, touchAction(), x, y)
    MessageType.KEY -> ControlCommand.Key(param, keyAction())
    MessageType.CONFIG_STREAM -> ControlCommand.ConfigStream(param, x, y)
    MessageType.KEYFRAME_REQ -> ControlCommand.KeyframeRequest
    MessageType.PAUSE -> ControlCommand.Pause
    MessageType.RESUME -> ControlCommand.Resume
    MessageType.ACTION -> ControlCommand.Action(deviceAction())
    MessageType.PING -> ControlCommand.Ping(param)
    MessageType.BYE -> ControlCommand.Bye(if (param == 0) null else ErrorCode.fromId(param))
    else -> error("ControlPacket type $type requires a payload and cannot use writeControlPacket")
}
