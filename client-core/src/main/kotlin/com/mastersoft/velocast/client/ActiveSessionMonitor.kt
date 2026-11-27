package com.mastersoft.velocast.client

import com.mastersoft.velocast.protocol.Bye
import com.mastersoft.velocast.protocol.ClipboardMessage
import com.mastersoft.velocast.protocol.CodecConfig
import com.mastersoft.velocast.protocol.DeviceInfo
import com.mastersoft.velocast.protocol.ErrorCode
import com.mastersoft.velocast.protocol.ErrorMessage
import com.mastersoft.velocast.protocol.MessageType
import com.mastersoft.velocast.protocol.Pong
import com.mastersoft.velocast.protocol.ProtocolException
import com.mastersoft.velocast.protocol.Status
import com.mastersoft.velocast.protocol.StatusKind
import com.mastersoft.velocast.protocol.VideoSize
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal sealed interface SessionEndReason {
    data object CleanBye : SessionEndReason
    data class TerminalFailure(val error: SessionError) : SessionEndReason
    data class RecoverableLoss(val error: SessionError.LinkLost) : SessionEndReason
}

private class LinkLostException(message: String) : IOException(message)

/**
 * Runs the active session loops (2s heartbeats, 6s silence watchdog, inbound frame dispatch)
 * for a single authenticated connection.
 */
internal class ActiveSessionMonitor(
    private val stateHolder: SessionStateHolder,
    private val transport: StreamTransport,
    private val config: SessionConfig,
    private val clock: SessionClock,
    private val frameConsumer: SessionFrameConsumer
) {
    private val pingSeq = AtomicInteger(1)
    private val pendingPings = ConcurrentHashMap<Int, Long>()

    suspend fun run(): SessionEndReason {
        val lastInboundMs = AtomicLong(clock.monotonicMillis())
        var endReason: SessionEndReason =
            SessionEndReason.RecoverableLoss(SessionError.LinkLost("Connection ended"))
        try {
            coroutineScope {
                val heartbeatJob = launch { sendHeartbeatsLoop() }
                val watchdogJob = launch { silenceWatchdogLoop(lastInboundMs) }
                endReason = readFramesLoop(lastInboundMs)
                heartbeatJob.cancel()
                watchdogJob.cancel()
            }
        } catch (e: LinkLostException) {
            endReason = SessionEndReason.RecoverableLoss(SessionError.LinkLost(e.message))
        } catch (e: ProtocolException) {
            endReason = SessionEndReason.TerminalFailure(
                SessionError.fromErrorCode(e.code, e.message)
            )
        } catch (e: IOException) {
            endReason = SessionEndReason.RecoverableLoss(SessionError.LinkLost(e.message))
        }
        return endReason
    }

    private suspend fun sendHeartbeatsLoop() {
        while (true) {
            delay(config.heartbeatIntervalMs)
            val token = pingSeq.getAndIncrement()
            if (pendingPings.size >= MAX_PENDING_PINGS) {
                pendingPings.clear()
            }
            pendingPings[token] = clock.monotonicMillis()
            transport.writeControl(ControlCommand.Ping(token))
        }
    }

    private suspend fun silenceWatchdogLoop(lastInboundMs: AtomicLong) {
        while (true) {
            val elapsed = clock.monotonicMillis() - lastInboundMs.get()
            val remaining = config.silenceTimeoutMs - elapsed
            if (remaining <= 0L) {
                throw LinkLostException(
                    "No frame or PONG received for ${config.silenceTimeoutMs} ms"
                )
            }
            delay(remaining)
        }
    }

    private suspend fun readFramesLoop(lastInboundMs: AtomicLong): SessionEndReason {
        var result: SessionEndReason? = null
        while (result == null) {
            val frame = transport.readFrame()
            if (frame == null) {
                result = SessionEndReason.RecoverableLoss(SessionError.LinkLost("Stream closed"))
            } else {
                lastInboundMs.set(clock.monotonicMillis())
                result = handleInboundFrame(frame)
            }
        }
        return result
    }

    private fun handleInboundFrame(frame: ReceivedFrame): SessionEndReason? = when (frame.type) {
        MessageType.PONG -> {
            handlePong(Pong.decode(frame.payload, 0, frame.payload.size))
            null
        }

        MessageType.DEVICE_INFO -> {
            val info = DeviceInfo.decode(frame.payload, 0, frame.payload.size)
            frameConsumer.onDeviceInfo(info)
            stateHolder.updateStreaming { it.copy(deviceInfo = info) }
            stateHolder.updatePaused { it.copy(deviceInfo = info) }
            null
        }

        MessageType.VIDEO_SIZE -> {
            val size = VideoSize.decode(frame.payload, 0, frame.payload.size)
            frameConsumer.onVideoSize(size)
            stateHolder.updateStreaming { it.copy(videoSize = size) }
            stateHolder.updatePaused { it.copy(videoSize = size) }
            null
        }

        MessageType.CONFIG -> {
            val codecConfig = CodecConfig.decode(frame.payload, 0, frame.payload.size)
            frameConsumer.onCodecConfig(codecConfig, frame.flags)
            null
        }

        MessageType.VIDEO -> {
            frameConsumer.onVideoFrame(frame)
            null
        }

        MessageType.STATUS -> {
            handleStatus(Status.decode(frame.payload, 0, frame.payload.size))
            null
        }

        MessageType.CLIPBOARD -> {
            frameConsumer.onClipboard(
                ClipboardMessage.decode(frame.payload, 0, frame.payload.size)
            )
            null
        }

        MessageType.ERROR -> handleErrorFrame(
            ErrorMessage.decode(frame.payload, 0, frame.payload.size)
        )

        MessageType.BYE -> handleByeFrame(Bye.decode(frame.payload, 0, frame.payload.size))

        else -> throw ProtocolException(
            ErrorCode.UNEXPECTED_MESSAGE,
            "Unexpected frame type ${frame.type} in session phase"
        )
    }

    private fun handlePong(pong: Pong) {
        val sentAt = pendingPings.remove(pong.token)
        val receivedAt = clock.monotonicMillis()
        frameConsumer.onPong(pong, sentAt, receivedAt)
        if (sentAt != null) {
            val rtt = (receivedAt - sentAt).coerceAtLeast(0L)
            stateHolder.updateStreaming { it.copy(rttMs = rtt) }
        }
    }

    private fun handleStatus(status: Status) {
        frameConsumer.onStatus(status)
        when (status.kind) {
            StatusKind.DISPLAY_POWER -> {
                val powerOn = status.value == 1
                stateHolder.updateStreaming { it.copy(isDisplayPowerOn = powerOn) }
            }

            StatusKind.SCREEN_LOCKED -> {
                val locked = status.value == 1
                stateHolder.updateStreaming { it.copy(isScreenLocked = locked) }
            }

            StatusKind.BITRATE -> stateHolder.updateStreaming { it.copy(bitrateBps = status.value) }

            StatusKind.FPS -> stateHolder.updateStreaming { it.copy(fps = status.value) }

            StatusKind.ROTATION,
            StatusKind.PAUSED,
            StatusKind.DROPPED_FRAMES,
            StatusKind.DEGRADED -> Unit
        }
    }

    private fun handleErrorFrame(message: ErrorMessage): SessionEndReason? {
        val error = SessionError.fromErrorMessage(message)
        return if (!message.fatal) {
            frameConsumer.onNonFatalError(error)
            stateHolder.updateStreaming { it.copy(nonFatalError = error) }
            null
        } else if (error is SessionError.LinkLost) {
            SessionEndReason.RecoverableLoss(error)
        } else {
            SessionEndReason.TerminalFailure(error)
        }
    }

    private fun handleByeFrame(bye: Bye): SessionEndReason {
        val code = bye.code
        return when {
            code == null -> SessionEndReason.CleanBye
            code == ErrorCode.LINK_LOST -> SessionEndReason.RecoverableLoss(SessionError.LinkLost())
            else -> SessionEndReason.TerminalFailure(SessionError.fromErrorCode(code))
        }
    }

    private companion object {
        const val MAX_PENDING_PINGS = 16
    }
}
