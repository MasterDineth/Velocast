package com.mastersoft.velocast.protocol

/**
 * The payload of one message. Implementations validate their fields on construction, so every
 * instance (also one produced by `decode`) is valid and encodes to a legal payload.
 */
interface Payload {
    val type: MessageType

    /** Exact number of bytes [encodeTo] writes. */
    val encodedSize: Int

    /** Writes the payload at [offset] of [dest] and returns the number of bytes written. */
    fun encodeTo(dest: ByteArray, offset: Int): Int

    fun encode(): ByteArray = ByteArray(encodedSize).also { encodeTo(it, 0) }
}

private fun requireBytes(value: ByteArray, size: Int, what: String) {
    if (value.size != size) fail(ErrorCode.MALFORMED_MESSAGE, "$what must be $size bytes")
}

/** Client to server: opens the handshake. Magic "VLC2", version, feature bitmap, client nonce. */
class Hello(val version: Int, val features: Int, nonce: ByteArray) : Payload {
    val nonce: ByteArray = nonce.copyOf()

    init {
        Validators.u16(version, "version")
        requireBytes(nonce, ProtocolV2.NONCE_SIZE, "nonce")
    }

    override val type: MessageType get() = MessageType.HELLO
    override val encodedSize: Int get() = ProtocolV2.HELLO_SIZE

    override fun encodeTo(dest: ByteArray, offset: Int): Int {
        val w = PayloadWriter(dest, offset)
        w.i32(ProtocolV2.MAGIC)
        w.u16(version)
        w.i32(features)
        w.bytes(nonce)
        return w.written
    }

    override fun equals(other: Any?): Boolean = other is Hello && version == other.version &&
        features == other.features && nonce.contentEquals(other.nonce)

    override fun hashCode(): Int = 31 * (31 * version + features) + nonce.contentHashCode()

    override fun toString(): String =
        "Hello(version=$version, features=0x${features.toString(HEX)})"

    companion object {
        private const val HEX = 16

        fun decode(buf: ByteArray, offset: Int, length: Int): Hello {
            val r = PayloadReader(buf, offset, length)
            if (r.i32() != ProtocolV2.MAGIC) fail(ErrorCode.BAD_MAGIC, "HELLO magic is not VLC2")
            val version = r.u16()
            val features = r.i32()
            val nonce = r.bytes(ProtocolV2.NONCE_SIZE)
            r.expectEnd()
            return Hello(version, features, nonce)
        }
    }
}

/** Static facts about the old phone, sent in SERVER_HELLO. */
data class DeviceInfo(
    val sdkInt: Int,
    val launchMode: LaunchMode,
    val display: DisplayGeometry,
    val manufacturer: String,
    val model: String
) {
    init {
        Validators.u16(sdkInt, "sdkInt")
        TextRules.require(manufacturer, ProtocolV2.MAX_NAME_BYTES, "manufacturer")
        TextRules.require(model, ProtocolV2.MAX_NAME_BYTES, "model")
    }

    internal val encodedSize: Int
        get() = ProtocolV2.SERVER_HELLO_FIXED_SIZE - FIXED_BEFORE_DEVICE + 2 +
            manufacturer.toByteArray(Charsets.UTF_8).size + model.toByteArray(Charsets.UTF_8).size

    internal fun writeTo(w: PayloadWriter) {
        w.u16(sdkInt)
        w.u8(launchMode.id)
        display.writeTo(w)
        w.string8(manufacturer)
        w.string8(model)
    }

    companion object {
        private const val FIXED_BEFORE_DEVICE = 2 + 4 + ProtocolV2.NONCE_SIZE

        internal fun read(r: PayloadReader): DeviceInfo {
            val sdkInt = r.u16()
            val launchMode = LaunchMode.fromId(r.u8())
                ?: fail(ErrorCode.VALUE_OUT_OF_RANGE, "unknown launch mode")
            val display = DisplayGeometry.read(r)
            val manufacturer = r.string8(ProtocolV2.MAX_NAME_BYTES)
            val model = r.string8(ProtocolV2.MAX_NAME_BYTES)
            return DeviceInfo(sdkInt, launchMode, display, manufacturer, model)
        }
    }
}

/**
 * Server to client: answers HELLO with the negotiated [version], the server's [capabilities]
 * bitmap, the server nonce and the device info.
 */
class ServerHello(
    val version: Int,
    val capabilities: Int,
    nonce: ByteArray,
    val device: DeviceInfo
) : Payload {
    val nonce: ByteArray = nonce.copyOf()

    init {
        Validators.u16(version, "version")
        requireBytes(nonce, ProtocolV2.NONCE_SIZE, "nonce")
    }

    override val type: MessageType get() = MessageType.SERVER_HELLO
    override val encodedSize: Int get() = FIXED_BEFORE_DEVICE + device.encodedSize

    override fun encodeTo(dest: ByteArray, offset: Int): Int {
        val w = PayloadWriter(dest, offset)
        w.u16(version)
        w.i32(capabilities)
        w.bytes(nonce)
        device.writeTo(w)
        return w.written
    }

    override fun equals(other: Any?): Boolean = other is ServerHello && version == other.version &&
        capabilities == other.capabilities && nonce.contentEquals(other.nonce) &&
        device == other.device

    override fun hashCode(): Int = 31 * (31 * version + capabilities) + nonce.contentHashCode()

    override fun toString(): String =
        "ServerHello(version=$version, capabilities=$capabilities, device=$device)"

    companion object {
        private const val FIXED_BEFORE_DEVICE = 2 + 4 + ProtocolV2.NONCE_SIZE

        fun decode(buf: ByteArray, offset: Int, length: Int): ServerHello {
            val r = PayloadReader(buf, offset, length)
            val version = r.u16()
            val capabilities = r.i32()
            val nonce = r.bytes(ProtocolV2.NONCE_SIZE)
            val device = DeviceInfo.read(r)
            r.expectEnd()
            return ServerHello(version, capabilities, nonce, device)
        }
    }
}

/** Either side: HMAC-SHA256 proof over the handshake transcript. Never printed. */
class AuthProof(proof: ByteArray) : Payload {
    val proof: ByteArray = proof.copyOf()

    init {
        requireBytes(proof, ProtocolV2.PROOF_SIZE, "proof")
    }

    override val type: MessageType get() = MessageType.AUTH_PROOF
    override val encodedSize: Int get() = ProtocolV2.PROOF_SIZE

    override fun encodeTo(dest: ByteArray, offset: Int): Int {
        val w = PayloadWriter(dest, offset)
        w.bytes(proof)
        return w.written
    }

    override fun equals(other: Any?): Boolean =
        other is AuthProof && proof.contentEquals(other.proof)

    override fun hashCode(): Int = proof.contentHashCode()

    override fun toString(): String = "AuthProof(<redacted>)"

    companion object {
        fun decode(buf: ByteArray, offset: Int, length: Int): AuthProof {
            val r = PayloadReader(buf, offset, length)
            val proof = r.bytes(ProtocolV2.PROOF_SIZE)
            r.expectEnd()
            return AuthProof(proof)
        }
    }
}
