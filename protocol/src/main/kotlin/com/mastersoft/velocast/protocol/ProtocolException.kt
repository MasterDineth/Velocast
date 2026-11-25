package com.mastersoft.velocast.protocol

/**
 * The only exception the readers, writers, validators and handshake classes raise for bad
 * protocol data. After one is thrown the byte stream may be desynchronised, so the connection
 * must be closed. Messages contain numbers and fixed text only, never message content or secrets.
 *
 * I/O problems of the underlying stream (timeouts, resets) are not protocol violations and
 * propagate as [java.io.IOException].
 */
class ProtocolException(val code: ErrorCode, message: String, cause: Throwable? = null) :
    Exception(message, cause)

internal fun fail(code: ErrorCode, message: String): Nothing =
    throw ProtocolException(code, message)
