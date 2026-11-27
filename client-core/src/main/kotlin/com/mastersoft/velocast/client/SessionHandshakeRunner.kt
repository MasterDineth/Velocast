package com.mastersoft.velocast.client

import com.mastersoft.velocast.protocol.Bye
import com.mastersoft.velocast.protocol.ClientHandshake
import com.mastersoft.velocast.protocol.ErrorCode
import com.mastersoft.velocast.protocol.ErrorMessage
import com.mastersoft.velocast.protocol.HandshakeState
import com.mastersoft.velocast.protocol.MessageType
import com.mastersoft.velocast.protocol.Phase
import com.mastersoft.velocast.protocol.ProtocolException
import java.io.IOException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout

internal data class HandshakeResult(val negotiatedVersion: Int, val negotiatedFeatures: Int)

/**
 * Executes the client side of the Protocol v2 handshake over a connected [StreamTransport],
 * switching the transport to [Phase.SESSION] only after mutual authentication succeeds.
 */
internal object SessionHandshakeRunner {
    suspend fun execute(
        transport: StreamTransport,
        endpoint: ServerEndpoint,
        config: SessionConfig,
        nonceGenerator: () -> ByteArray
    ): HandshakeResult {
        try {
            return withTimeout(config.handshakeTimeoutMs) {
                runExchange(transport, endpoint, config, nonceGenerator())
            }
        } catch (e: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            throw IOException("Handshake timed out after ${config.handshakeTimeoutMs} ms", e)
        }
    }

    private suspend fun runExchange(
        transport: StreamTransport,
        endpoint: ServerEndpoint,
        config: SessionConfig,
        clientNonce: ByteArray
    ): HandshakeResult {
        val handshake = ClientHandshake(endpoint.authenticator, config.wantedFeatures, clientNonce)
        transport.writeControl(ControlCommand.HelloCommand(handshake.hello()))

        val serverHello = readExpectedFrame(transport, MessageType.SERVER_HELLO)
        val clientProof = handshake.onServerHello(
            payload = serverHello.payload,
            offset = 0,
            length = serverHello.payload.size
        )
        transport.writeControl(ControlCommand.AuthProofCommand(clientProof))

        val serverProof = readExpectedFrame(transport, MessageType.AUTH_PROOF)
        handshake.onServerProof(
            payload = serverProof.payload,
            offset = 0,
            length = serverProof.payload.size
        )
        if (handshake.state != HandshakeState.ESTABLISHED) {
            throw ProtocolException(ErrorCode.UNEXPECTED_MESSAGE, "Handshake not established")
        }

        transport.setPhase(Phase.SESSION)
        config.initialStreamConfig?.let { stream ->
            transport.writeControl(
                ControlCommand.ConfigStream(stream.bitrateBps, stream.fps.toFloat(), stream.scale)
            )
        }
        return HandshakeResult(
            negotiatedVersion = handshake.negotiatedVersion,
            negotiatedFeatures = handshake.negotiatedFeatures
        )
    }

    private suspend fun readExpectedFrame(
        transport: StreamTransport,
        expected: MessageType
    ): ReceivedFrame {
        val frame = transport.readFrame()
            ?: throw IOException("Connection closed before $expected")
        if (frame.type == expected) {
            return frame
        }
        throw unexpectedHandshakeException(frame, expected)
    }

    private fun unexpectedHandshakeException(
        frame: ReceivedFrame,
        expected: MessageType
    ): Exception = when (frame.type) {
        MessageType.ERROR -> {
            val msg = ErrorMessage.decode(frame.payload, 0, frame.payload.size)
            SessionErrorException(SessionError.fromErrorMessage(msg))
        }

        MessageType.BYE -> {
            val bye = Bye.decode(frame.payload, 0, frame.payload.size)
            val code = bye.code
            if (code != null) {
                SessionErrorException(SessionError.fromErrorCode(code))
            } else {
                IOException("Server closed connection with BYE during handshake")
            }
        }

        else -> ProtocolException(
            ErrorCode.UNEXPECTED_MESSAGE,
            "Expected $expected during handshake, got ${frame.type}"
        )
    }
}
