package com.mastersoft.velocast.protocol

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/** Decoding, describing and re-encoding of single wire messages, shared by the vector tests. */
object VectorSupport {
    const val C2S = "c2s"
    const val S2C = "s2c"

    fun hexToBytes(text: String): ByteArray {
        val clean = text.filter { !it.isWhitespace() }
        require(clean.length % 2 == 0) { "odd number of hex digits" }
        return ByteArray(clean.length / 2) {
            clean.substring(2 * it, 2 * it + 2).toInt(16).toByte()
        }
    }

    fun toHex(bytes: ByteArray, length: Int = bytes.size): String =
        (0 until length).joinToString(" ") { "%02X".format(bytes[it]) }

    fun phaseOf(name: String): Phase = Phase.valueOf(name.uppercase())

    fun lowerHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    /** Decodes exactly one message and renders it in the notation used by the vector files. */
    fun describe(direction: String, phase: Phase, bytes: ByteArray): String {
        val input = ByteArrayInputStream(bytes)
        return if (direction == C2S) {
            val reader = ControlReader(input).also { it.phase = phase }
            val p = ControlPacket()
            assertTrue("no message in input", reader.read(p))
            assertEquals("trailing bytes after the message", 0, input.available())
            describeControl(p, reader.payload)
        } else {
            val reader = FrameReader(input).also { it.phase = phase }
            val h = FrameHeader()
            assertTrue("no message in input", reader.readHeader(h))
            reader.readPayload(h)
            assertEquals("trailing bytes after the message", 0, input.available())
            describeFrame(h, reader.payload)
        }
    }

    /** Decodes one message and writes it again with the library's writers. */
    fun reencode(direction: String, phase: Phase, bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        val input = ByteArrayInputStream(bytes)
        if (direction == C2S) {
            val reader = ControlReader(input).also { it.phase = phase }
            val p = ControlPacket()
            check(reader.read(p))
            val writer = ControlWriter(out).also {
                it.phase = phase
                it.nextSeq = p.seq
            }
            if (p.type.clientToServer!!.isEmpty) {
                writer.write(p)
            } else {
                writer.writePayload(decodeClientPayload(p.type, reader.payload, p.payloadLength))
            }
        } else {
            val reader = FrameReader(input).also { it.phase = phase }
            val h = FrameHeader()
            check(reader.readHeader(h))
            reader.readPayload(h)
            val writer = FrameWriter(out).also {
                it.phase = phase
                it.nextSeq = h.seq
            }
            if (h.type == MessageType.VIDEO) {
                writer.write(h, reader.payload, 0, h.length)
            } else {
                writer.writeMessage(decodeServerPayload(h.type, reader.payload, h.length), h.flags)
            }
        }
        return out.toByteArray()
    }

    fun decodeClientPayload(type: MessageType, buf: ByteArray, length: Int): Payload = when (type) {
        MessageType.HELLO -> Hello.decode(buf, 0, length)
        MessageType.AUTH_PROOF -> AuthProof.decode(buf, 0, length)
        MessageType.TEXT -> TextInput.decode(buf, 0, length)
        MessageType.CLIPBOARD -> ClipboardMessage.decode(buf, 0, length)
        else -> error("$type has no client payload")
    }

    fun decodeServerPayload(type: MessageType, buf: ByteArray, length: Int): Payload = when (type) {
        MessageType.SERVER_HELLO -> ServerHello.decode(buf, 0, length)
        MessageType.AUTH_PROOF -> AuthProof.decode(buf, 0, length)
        MessageType.BYE -> Bye.decode(buf, 0, length)
        MessageType.PONG -> Pong.decode(buf, 0, length)
        MessageType.CONFIG -> CodecConfig.decode(buf, 0, length)
        MessageType.VIDEO_SIZE -> VideoSize.decode(buf, 0, length)
        MessageType.STATUS -> Status.decode(buf, 0, length)
        MessageType.ERROR -> ErrorMessage.decode(buf, 0, length)
        MessageType.CLIPBOARD -> ClipboardMessage.decode(buf, 0, length)
        else -> error("$type has no typed server payload")
    }

    fun flagNames(flags: Int): String {
        val names = listOf(
            FrameFlags.KEYFRAME to "KEYFRAME",
            FrameFlags.CONFIG to "CONFIG",
            FrameFlags.RESET to "RESET",
            FrameFlags.ERROR to "ERROR"
        ).filter { (flags and it.first) != 0 }.map { it.second }
        return if (names.isEmpty()) "none" else names.joinToString("|")
    }

    private fun describeControl(p: ControlPacket, payload: ByteArray): String {
        val spec = p.type.clientToServer!!
        return if (spec.isEmpty) {
            "${p.type} seq=${p.seq} pointer=${p.pointer} param=${p.param} x=${p.x} y=${p.y}"
        } else {
            val decoded = decodeClientPayload(p.type, payload, p.payloadLength)
            "${p.type} seq=${p.seq} " + describePayload(decoded)
        }
    }

    private fun describeFrame(h: FrameHeader, payload: ByteArray): String {
        val head = "${h.type} flags=${flagNames(h.flags)} seq=${h.seq} " +
            "pts=${h.ptsUs} capture=${h.captureUs} "
        return head + if (h.type == MessageType.VIDEO) {
            "length=${h.length}"
        } else {
            describePayload(decodeServerPayload(h.type, payload, h.length))
        }
    }

    private fun describePayload(p: Payload): String = when (p) {
        is Hello ->
            "version=${p.version} " +
                "features=0x${p.features.toString(16)} nonce=${lowerHex(p.nonce)}"

        is ServerHello -> "version=${p.version} capabilities=0x${p.capabilities.toString(16)} " +
            "nonce=${lowerHex(p.nonce)} sdk=${p.device.sdkInt} launch=${p.device.launchMode} " +
            "display=${p.device.display.width}x${p.device.display.height} " +
            "rotation=${p.device.display.rotation} manufacturer=\"${p.device.manufacturer}\" " +
            "model=\"${p.device.model}\""

        is AuthProof -> "proof=${lowerHex(p.proof)}"

        is TextInput -> "text=\"${p.text}\""

        is ClipboardMessage -> "paste=${p.paste} text=\"${p.text}\""

        is CodecConfig -> "codec=${p.codec} csd=${lowerHex(p.csd)}"

        is VideoSize ->
            "stream=${p.streamWidth}x${p.streamHeight} " +
                "display=${p.display.width}x${p.display.height} " +
                "rotation=${p.display.rotation}"

        is Status -> "kind=${p.kind} value=${p.value} detail=\"${p.detail}\""

        is ErrorMessage ->
            "code=${p.code} fatal=${p.fatal} detail=\"${p.detail}\""

        is Pong -> "token=${p.token} serverUs=${p.serverUs}"

        is Bye -> "code=${p.code ?: "none"}"

        else -> error("no description for $p")
    }

    // Raw builders that do not use the library's writers, for hostile-input tests.

    fun control(
        type: Int,
        pointer: Int = 0,
        seq: Int = 0,
        param: Int = 0,
        x: Float = 0f,
        y: Float = 0f,
        payload: ByteArray = ByteArray(0)
    ): ByteArray = ByteBuffer.allocate(ProtocolV2.CONTROL_PACKET_SIZE + payload.size)
        .put(type.toByte()).put(pointer.toByte()).putShort(seq.toShort()).putInt(param)
        .putFloat(x).putFloat(y).put(payload).array()

    fun frame(
        type: Int,
        flags: Int = 0,
        seq: Int = 0,
        length: Int,
        pts: Long = 0,
        capture: Long = 0,
        payload: ByteArray = ByteArray(0)
    ): ByteArray = ByteBuffer.allocate(ProtocolV2.FRAME_HEADER_SIZE + payload.size)
        .put(type.toByte()).put(flags.toByte()).putShort(seq.toShort()).putInt(length)
        .putLong(pts).putLong(capture).put(payload).array()
}
