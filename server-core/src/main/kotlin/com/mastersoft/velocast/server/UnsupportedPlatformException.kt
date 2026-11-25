package com.mastersoft.velocast.server

/**
 * Thrown when a required Android platform API or hidden shim is unavailable on the host device.
 */
class UnsupportedPlatformException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)
