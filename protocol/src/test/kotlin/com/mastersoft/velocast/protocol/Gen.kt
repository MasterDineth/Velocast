package com.mastersoft.velocast.protocol

import java.io.ByteArrayOutputStream
import kotlin.random.Random

/** Random generators of valid messages, used by the round-trip and fuzz tests. */
object Gen {
    private val kinds = StatusKind.entries
    private const val SMALL = 300

    fun codePoint(rnd: Random): Int = when (rnd.nextInt(5)) {
        0, 1 -> rnd.nextInt(0x20, 0x7F)
        2 -> rnd.nextInt(0xA0, 0x800)
        3 -> rnd.nextInt(0x800, 0xD800)
        else -> rnd.nextInt(0x10000, 0x110000)
    }

    private fun utf8Length(cp: Int): Int = when {
        cp < 0x80 -> 1
        cp < 0x800 -> 2
        cp < 0x10000 -> 3
        else -> 4
    }

    /** A random string (no NUL, no lone surrogates) of at most [maxBytes] UTF-8 bytes. */
    fun string(rnd: Random, maxBytes: Int): String {
        val target = rnd.nextInt(0, maxBytes + 1)
        val sb = StringBuilder()
        var bytes = 0
        while (true) {
            val cp = codePoint(rnd)
            val n = utf8Length(cp)
            if (bytes + n > target) break
            sb.appendCodePoint(cp)
            bytes += n
        }
        return sb.toString()
    }

    fun nonEmptyString(rnd: Random, maxBytes: Int): String {
        val first = ('a' + rnd.nextInt(26)).toString()
        return first + string(rnd, maxBytes - 1)
    }

    fun geometry(rnd: Random) = DisplayGeometry(
        rnd.nextInt(1, ProtocolV2.MAX_DIMENSION + 1),
        rnd.nextInt(1, ProtocolV2.MAX_DIMENSION + 1),
        rnd.nextInt(0, ProtocolV2.MAX_ROTATION + 1)
    )

    fun hello(rnd: Random): Hello {
        val nonce = rnd.nextBytes(ProtocolV2.NONCE_SIZE)
        return Hello(rnd.nextInt(0, 0x10000), rnd.nextInt(), nonce)
    }

    fun serverHello(rnd: Random) = ServerHello(
        rnd.nextInt(0, 0x10000),
        rnd.nextInt(),
        rnd.nextBytes(ProtocolV2.NONCE_SIZE)
    )

    fun deviceInfo(rnd: Random) = DeviceInfo(
        rnd.nextInt(0, 0x10000),
        LaunchMode.entries.random(rnd),
        geometry(rnd),
        string(rnd, ProtocolV2.MAX_NAME_BYTES),
        string(rnd, ProtocolV2.MAX_NAME_BYTES)
    )

    fun status(rnd: Random): Status {
        val kind = kinds.random(rnd)
        val value = when (kind) {
            StatusKind.DISPLAY_POWER -> rnd.nextInt(0, 2)
            StatusKind.SCREEN_LOCKED -> rnd.nextInt(0, 2)
            StatusKind.PAUSED -> rnd.nextInt(0, 2)
            StatusKind.ROTATION -> rnd.nextInt(0, 4)
            else -> rnd.nextInt(0, Int.MAX_VALUE)
        }
        return Status(kind, value, string(rnd, ProtocolV2.MAX_STATUS_DETAIL_BYTES))
    }

    fun clientPayload(rnd: Random): Payload = when (rnd.nextInt(4)) {
        0 -> hello(rnd)

        1 -> AuthProof(rnd.nextBytes(ProtocolV2.PROOF_SIZE))

        2 -> {
            val max = if (rnd.nextInt(10) == 0) ProtocolV2.MAX_TEXT_BYTES else SMALL
            TextInput(nonEmptyString(rnd, max))
        }

        else -> ClipboardMessage(rnd.nextBoolean(), string(rnd, SMALL))
    }

    fun serverPayload(rnd: Random): Payload = when (rnd.nextInt(10)) {
        0 -> serverHello(rnd)

        1 -> AuthProof(rnd.nextBytes(ProtocolV2.PROOF_SIZE))

        2 -> Bye(if (rnd.nextBoolean()) null else ErrorCode.entries.random(rnd))

        3 -> Pong(rnd.nextInt(), rnd.nextLong(0, Long.MAX_VALUE))

        4 -> CodecConfig(Codec.entries.random(rnd), rnd.nextBytes(rnd.nextInt(1, SMALL)))

        5 -> {
            val width = rnd.nextInt(1, ProtocolV2.MAX_DIMENSION + 1)
            val height = rnd.nextInt(1, ProtocolV2.MAX_DIMENSION + 1)
            VideoSize(width, height, geometry(rnd))
        }

        6 -> status(rnd)

        7 -> {
            val detail = string(rnd, ProtocolV2.MAX_ERROR_DETAIL_BYTES)
            ErrorMessage(ErrorCode.entries.random(rnd), rnd.nextBoolean(), detail)
        }

        8 -> deviceInfo(rnd)

        else -> ClipboardMessage(rnd.nextBoolean(), string(rnd, SMALL))
    }

    private fun unit(rnd: Random): Float = when (rnd.nextInt(8)) {
        0 -> 0f
        1 -> 1f
        else -> rnd.nextFloat()
    }

    /** A random valid payload-less control packet with a random seq. */
    fun controlPacket(rnd: Random): ControlPacket {
        val p = ControlPacket()
        p.seq = rnd.nextInt(0, 0x10000)
        when (rnd.nextInt(8)) {
            0 -> {
                p.type = MessageType.TOUCH
                p.pointer = rnd.nextInt(0, ProtocolV2.MAX_POINTER_ID + 1)
                p.param = rnd.nextInt(0, 4)
                p.x = unit(rnd)
                p.y = unit(rnd)
            }

            1 -> {
                p.type = MessageType.KEY
                p.pointer = rnd.nextInt(0, 2)
                p.param = KeyCodes.allKeyCodes.random(rnd)
            }

            2 -> {
                p.type = MessageType.CONFIG_STREAM
                p.param = rnd.nextInt(ProtocolV2.MIN_BITRATE_BPS, ProtocolV2.MAX_BITRATE_BPS + 1)
                p.x = rnd.nextInt(ProtocolV2.MIN_FPS, ProtocolV2.MAX_FPS + 1).toFloat()
                p.y = 0.5f + rnd.nextFloat() / 2f
            }

            3 -> {
                p.type = MessageType.ACTION
                p.param = DeviceAction.entries.random(rnd).id
            }

            4 -> {
                p.type = MessageType.PING
                p.param = rnd.nextInt()
            }

            5 -> {
                val requests = listOf(
                    MessageType.KEYFRAME_REQ,
                    MessageType.PAUSE,
                    MessageType.RESUME
                )
                p.type = requests.random(rnd)
            }

            6 -> {
                p.type = MessageType.BYE
                p.param = if (rnd.nextBoolean()) 0 else ErrorCode.entries.random(rnd).id
            }

            else -> {
                p.type = MessageType.TOUCH
                p.pointer = 0
                p.param = 2
                p.x = unit(rnd)
                p.y = unit(rnd)
            }
        }
        return p
    }

    private fun phaseFor(type: MessageType): Phase {
        val handshake = type.phases == PhaseRule.HANDSHAKE_ONLY
        return if (handshake) Phase.HANDSHAKE else Phase.SESSION
    }

    /** Writes one valid client message (any type, in its proper phase) to [out]. */
    fun writeControl(rnd: Random, out: ByteArrayOutputStream) {
        val writer = ControlWriter(out)
        if (rnd.nextInt(3) == 0) {
            val msg = clientPayload(rnd)
            writer.phase = phaseFor(msg.type)
            writer.nextSeq = rnd.nextInt(0, 0x10000)
            writer.writePayload(msg)
        } else {
            val p = controlPacket(rnd)
            writer.phase = Phase.SESSION
            if (p.type == MessageType.BYE) writer.phase = Phase.HANDSHAKE
            writer.write(p)
        }
    }

    /** Writes one valid server frame (any type, in its proper phase) to [out]. */
    fun writeFrame(rnd: Random, out: ByteArrayOutputStream) {
        val writer = FrameWriter(out)
        writer.nextSeq = rnd.nextInt(0, 0x10000)
        if (rnd.nextInt(3) == 0) {
            val h = FrameHeader()
            h.type = MessageType.VIDEO
            val keyframe = FrameFlags.KEYFRAME
            h.flags = listOf(0, keyframe, keyframe or FrameFlags.RESET).random(rnd)
            h.seq = rnd.nextInt(0, 0x10000)
            h.ptsUs = rnd.nextLong(0, Long.MAX_VALUE)
            h.captureUs = rnd.nextLong(0, Long.MAX_VALUE)
            val size = if (rnd.nextInt(8) == 0) rnd.nextInt(1, 70_000) else rnd.nextInt(1, SMALL)
            val payload = rnd.nextBytes(size)
            writer.phase = Phase.SESSION
            writer.write(h, payload, 0, payload.size)
        } else {
            val msg = serverPayload(rnd)
            writer.phase = phaseFor(msg.type)
            writer.writeMessage(msg)
        }
    }

    fun controlBytes(rnd: Random): ByteArray = ByteArrayOutputStream().also { out ->
        repeat(rnd.nextInt(1, 4)) { writeControl(rnd, out) }
    }.toByteArray()

    fun frameBytes(rnd: Random): ByteArray = ByteArrayOutputStream().also { out ->
        repeat(rnd.nextInt(1, 4)) { writeFrame(rnd, out) }
    }.toByteArray()

    private val extremes = intArrayOf(
        0, -1, Int.MAX_VALUE, Int.MIN_VALUE, 4 * 1024 * 1024, 4 * 1024 * 1024 + 1,
        256, 257, 26, 27, 32, 33
    )

    /** Corrupts [src] in one of several ways. */
    fun mutate(rnd: Random, src: ByteArray): ByteArray {
        var b = src.copyOf()
        when (rnd.nextInt(7)) {
            0 -> repeat(rnd.nextInt(1, 4)) {
                if (b.isNotEmpty()) b[rnd.nextInt(b.size)] = rnd.nextInt(256).toByte()
            }

            1 -> b = b.copyOf(rnd.nextInt(b.size + 1))

            2 -> b += rnd.nextBytes(rnd.nextInt(1, 33))

            3 -> if (b.isNotEmpty()) {
                val i = rnd.nextInt(b.size)
                b[i] = (b[i].toInt() xor (1 shl rnd.nextInt(8))).toByte()
            }

            4 -> if (b.size >= 8) {
                val v = extremes.random(rnd)
                for (k in 0 until 4) b[4 + k] = (v ushr (24 - 8 * k)).toByte()
            }

            5 -> if (b.isNotEmpty()) b[0] = rnd.nextInt(256).toByte()

            else -> b += b
        }
        return b
    }
}
