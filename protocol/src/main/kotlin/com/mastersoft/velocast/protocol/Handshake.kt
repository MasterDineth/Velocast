package com.mastersoft.velocast.protocol

/** Where a handshake stands. The client and the server walk different paths. */
enum class HandshakeState {
    /** Client: nothing sent yet. */
    SEND_HELLO,

    /** Client: HELLO sent, waiting for SERVER_HELLO. */
    AWAIT_SERVER_HELLO,

    /** Client: AUTH_PROOF sent, waiting for the server's proof. */
    AWAIT_SERVER_PROOF,

    /** Server: waiting for HELLO. */
    AWAIT_HELLO,

    /** Server: SERVER_HELLO sent, waiting for the client's proof. */
    AWAIT_CLIENT_PROOF,

    /** Both proofs verified. Switch readers and writers to [Phase.SESSION]. */
    ESTABLISHED,

    /** A step failed. The connection must be closed. */
    FAILED
}

/** Shared state handling for [ClientHandshake] and [ServerHandshake]. */
abstract class HandshakeBase internal constructor(initial: HandshakeState) {
    var state: HandshakeState = initial
        protected set

    /** Version both sides speak; 0 until negotiated. */
    var negotiatedVersion: Int = 0
        protected set

    /** Features both sides support (client wanted AND server offered); 0 until negotiated. */
    var negotiatedFeatures: Int = 0
        protected set

    /**
     * Runs [block] if the handshake is in the [expected] state. Any failure, including an
     * out-of-order message, moves the handshake to [HandshakeState.FAILED].
     */
    protected fun <T> step(expected: HandshakeState, block: () -> T): T {
        if (state != expected) {
            state = HandshakeState.FAILED
            fail(ErrorCode.UNEXPECTED_MESSAGE, "handshake message out of order")
        }
        try {
            return block()
        } catch (e: ProtocolException) {
            state = HandshakeState.FAILED
            throw e
        }
    }
}

/**
 * Client side. Call [hello], send it, then feed the server's answers to [onServerHello] and
 * [onServerProof]. All payload arguments are the raw payload bytes of the received message.
 */
class ClientHandshake(
    private val authenticator: Authenticator,
    private val features: Int,
    clientNonce: ByteArray
) : HandshakeBase(HandshakeState.SEND_HELLO) {
    private val helloMessage = Hello(ProtocolV2.VERSION, features, clientNonce)
    private var serverHelloPayload: ByteArray = ByteArray(0)

    /** The server's SERVER_HELLO once received. */
    var serverHello: ServerHello? = null
        private set

    /** Produces the HELLO to send. */
    fun hello(): Hello = step(HandshakeState.SEND_HELLO) {
        state = HandshakeState.AWAIT_SERVER_HELLO
        helloMessage
    }

    /** Checks the version, remembers the transcript and returns the client's AUTH_PROOF. */
    fun onServerHello(payload: ByteArray, offset: Int, length: Int): AuthProof =
        step(HandshakeState.AWAIT_SERVER_HELLO) {
            val reply = ServerHello.decode(payload, offset, length)
            if (reply.version < ProtocolV2.MIN_VERSION || reply.version > helloMessage.version) {
                fail(
                    ErrorCode.VERSION_MISMATCH,
                    "server chose unsupported version ${reply.version}"
                )
            }
            serverHelloPayload = payload.copyOfRange(offset, offset + length)
            serverHello = reply
            negotiatedVersion = reply.version
            negotiatedFeatures = features and reply.capabilities
            val transcript = Transcript.build(
                AuthRole.CLIENT,
                helloMessage.encode(),
                serverHelloPayload
            )
            state = HandshakeState.AWAIT_SERVER_PROOF
            AuthProof(authenticator.computeProof(transcript))
        }

    /** Verifies the server's proof; on success the handshake is [HandshakeState.ESTABLISHED]. */
    fun onServerProof(payload: ByteArray, offset: Int, length: Int) {
        step(HandshakeState.AWAIT_SERVER_PROOF) {
            val proof = AuthProof.decode(payload, offset, length)
            val transcript = Transcript.build(
                AuthRole.SERVER,
                helloMessage.encode(),
                serverHelloPayload
            )
            if (!authenticator.verifyProof(transcript, proof.proof)) {
                fail(ErrorCode.AUTH_FAILED, "server proof rejected")
            }
            state = HandshakeState.ESTABLISHED
        }
    }
}

/**
 * Server side. Feed the client's HELLO to [onHello] and send the returned SERVER_HELLO, then feed
 * the client's AUTH_PROOF to [onClientProof] and send the returned proof. Nothing but
 * SERVER_HELLO, ERROR or BYE may be sent before [onClientProof] succeeded.
 */
class ServerHandshake(
    private val authenticator: Authenticator,
    private val capabilities: Int,
    serverNonce: ByteArray
) : HandshakeBase(HandshakeState.AWAIT_HELLO) {
    private val nonce: ByteArray = serverNonce.copyOf()
    private var helloPayload: ByteArray = ByteArray(0)
    private var serverHelloPayload: ByteArray = ByteArray(0)

    /** The client's HELLO once received. */
    var clientHello: Hello? = null
        private set

    /** Validates HELLO, negotiates version and features and returns the SERVER_HELLO to send. */
    fun onHello(payload: ByteArray, offset: Int, length: Int): ServerHello =
        step(HandshakeState.AWAIT_HELLO) {
            val hello = Hello.decode(payload, offset, length)
            if (hello.version < ProtocolV2.MIN_VERSION) {
                fail(ErrorCode.VERSION_MISMATCH, "client version ${hello.version} is too old")
            }
            val version = minOf(hello.version, ProtocolV2.VERSION)
            val reply = ServerHello(version, capabilities, nonce)
            helloPayload = payload.copyOfRange(offset, offset + length)
            serverHelloPayload = reply.encode()
            clientHello = hello
            negotiatedVersion = version
            negotiatedFeatures = hello.features and capabilities
            state = HandshakeState.AWAIT_CLIENT_PROOF
            reply
        }

    /**
     * Verifies the client's proof ([ErrorCode.AUTH_FAILED] if it is wrong) and returns the
     * server's own proof to send back.
     */
    fun onClientProof(payload: ByteArray, offset: Int, length: Int): AuthProof =
        step(HandshakeState.AWAIT_CLIENT_PROOF) {
            val proof = AuthProof.decode(payload, offset, length)
            val clientTranscript = Transcript.build(
                AuthRole.CLIENT,
                helloPayload,
                serverHelloPayload
            )
            if (!authenticator.verifyProof(clientTranscript, proof.proof)) {
                fail(ErrorCode.AUTH_FAILED, "client proof rejected")
            }
            val serverTranscript = Transcript.build(
                AuthRole.SERVER,
                helloPayload,
                serverHelloPayload
            )
            state = HandshakeState.ESTABLISHED
            AuthProof(authenticator.computeProof(serverTranscript))
        }
}
