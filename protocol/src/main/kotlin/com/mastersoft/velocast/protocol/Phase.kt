package com.mastersoft.velocast.protocol

/**
 * Connection phase. Readers and writers start in [HANDSHAKE], where only handshake messages,
 * ERROR and BYE are accepted and payloads are capped at [ProtocolV2.MAX_HANDSHAKE_PAYLOAD].
 * The owner switches them to [SESSION] once the handshake is established.
 */
enum class Phase {
    HANDSHAKE,
    SESSION
}
