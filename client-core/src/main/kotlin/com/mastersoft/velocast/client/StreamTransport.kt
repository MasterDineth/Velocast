package com.mastersoft.velocast.client

import com.mastersoft.velocast.protocol.AuthProof
import com.mastersoft.velocast.protocol.ClipboardMessage
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

/** One decoded server-to-client frame delivered by [StreamTransport.readFrame]. */
data class ReceivedFrame(
    val type: MessageType,
    val flags: Int,
    val seq: Int,
    val ptsUs: Long,
    val captureUs: Long,
    val payload: ByteArray
) {
    val isKeyframe: Boolean
        get() = (flags and FrameFlags.KEYFRAME) != 0

    val isReset: Boolean
        get() = (flags and FrameFlags.RESET) != 0

    override fun equals(other: Any?): Boolean = other is ReceivedFrame &&
        type == other.type &&
        flags == other.flags &&
        seq == other.seq &&
        ptsUs == other.ptsUs &&
        captureUs == other.captureUs &&
        payload.contentEquals(other.payload)

    override fun hashCode(): Int {
        var result = type.hashCode()
        result = 31 * result + flags
        result = 31 * result + seq
        result = 31 * result + ptsUs.hashCode()
        result = 31 * result + captureUs.hashCode()
        result = 31 * result + payload.contentHashCode()
        return result
    }
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

    suspend fun close()
}
