package com.mastersoft.velocast.launcher.adb

import com.mastersoft.velocast.client.SessionError
import com.mastersoft.velocast.protocol.ErrorCode
import com.mastersoft.velocast.protocol.SecretRedactor
import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.GeneralSecurityException
import java.util.Locale
import javax.crypto.AEADBadTagException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException

/**
 * Typed failure domain for `:launcher-adb` pairing, discovery, and connection operations (`S07A`).
 *
 * Maps transport/TLS/SPAKE2/mDNS failures cleanly to the user-facing error catalogue (`DEBUGGING_OFF`,
 * `DIFFERENT_NETWORK`, `AUTH_EXPIRED`, `NOT_PAIRED`, plus pairing `WrongPairingCode`) and converts
 * directly to `:client-core` [SessionError] via [toSessionError].
 */
sealed class AdbFailure(val errorCode: ErrorCode, val causeKey: String, detail: String? = null) {
    val detail: String? =
        detail?.let { AdbSecretScrubber.defaultInstance.redact(it).takeIf(String::isNotBlank) }

    class DebuggingOff(detail: String? = null) :
        AdbFailure(ErrorCode.DEBUGGING_OFF, "error_debugging_off", detail) {
        override fun equals(other: Any?): Boolean = other is DebuggingOff && detail == other.detail
        override fun hashCode(): Int = HASH_PRIME * errorCode.hashCode() + (detail?.hashCode() ?: 0)
        override fun toString(): String = "DebuggingOff(detail=$detail)"
    }

    class DifferentNetwork(detail: String? = null) :
        AdbFailure(ErrorCode.DIFFERENT_NETWORK, "error_different_network", detail) {
        override fun equals(other: Any?): Boolean =
            other is DifferentNetwork && detail == other.detail
        override fun hashCode(): Int = HASH_PRIME * errorCode.hashCode() + (detail?.hashCode() ?: 0)
        override fun toString(): String = "DifferentNetwork(detail=$detail)"
    }

    class AuthExpired(detail: String? = null) :
        AdbFailure(ErrorCode.AUTH_EXPIRED, "error_auth_expired", detail) {
        override fun equals(other: Any?): Boolean = other is AuthExpired && detail == other.detail
        override fun hashCode(): Int = HASH_PRIME * errorCode.hashCode() + (detail?.hashCode() ?: 0)
        override fun toString(): String = "AuthExpired(detail=$detail)"
    }

    class NotPaired(detail: String? = null) :
        AdbFailure(ErrorCode.NOT_PAIRED, "error_not_paired", detail) {
        override fun equals(other: Any?): Boolean = other is NotPaired && detail == other.detail
        override fun hashCode(): Int = HASH_PRIME * errorCode.hashCode() + (detail?.hashCode() ?: 0)
        override fun toString(): String = "NotPaired(detail=$detail)"
    }

    class WrongPairingCode(detail: String? = null) :
        AdbFailure(ErrorCode.AUTH_FAILED, "error_wrong_pairing_code", detail) {
        override fun equals(other: Any?): Boolean =
            other is WrongPairingCode && detail == other.detail
        override fun hashCode(): Int = HASH_PRIME * causeKey.hashCode() + (detail?.hashCode() ?: 0)
        override fun toString(): String = "WrongPairingCode(detail=$detail)"
    }

    class TransportError(detail: String? = null, code: ErrorCode = ErrorCode.LINK_LOST) :
        AdbFailure(code, "error_adb_transport", detail) {
        override fun equals(other: Any?): Boolean =
            other is TransportError && errorCode == other.errorCode && detail == other.detail
        override fun hashCode(): Int = HASH_PRIME * errorCode.hashCode() + (detail?.hashCode() ?: 0)
        override fun toString(): String = "TransportError(code=$errorCode, detail=$detail)"
    }

    fun toSessionError(): SessionError = SessionError.fromErrorCode(errorCode, detail)

    private companion object {
        const val HASH_PRIME = 31
    }
}

/** Exception carrying a typed [AdbFailure] with automatic secret redaction. */
class AdbClientException(val failure: AdbFailure, cause: Throwable? = null) :
    IOException(failure.detail ?: failure.causeKey, cause)

/** Stage in which an ADB failure occurred so [AdbErrorMapper] applies accurate context. */
enum class AdbOperationStage {
    DISCOVERY,
    PAIRING,
    CONNECT,
    USB_CONNECT,
    STREAM
}

/** IPv4 subnet descriptor used to detect cross-network mismatches (`DIFFERENT_NETWORK`). */
data class Ipv4Subnet(val networkAddressBits: Int, val prefixLength: Int) {
    init {
        require(prefixLength in 0..IPV4_BITS) { "Invalid IPv4 prefixLength: $prefixLength" }
    }

    fun contains(addressBytes: ByteArray): Boolean {
        if (addressBytes.size != IPV4_BYTES) return false
        val mask = if (prefixLength == 0) 0 else (-1 shl (IPV4_BITS - prefixLength))
        val candidateBits = packIpv4(addressBytes)
        return (candidateBits and mask) == (networkAddressBits and mask)
    }

    companion object {
        const val IPV4_BYTES = 4
        const val IPV4_BITS = 32
        private const val BYTE_MASK = 0xFF
        private const val SHIFT_OCTET_0 = 24
        private const val SHIFT_OCTET_1 = 16
        private const val SHIFT_OCTET_2 = 8
        private const val LAST_OCTET_INDEX = 3

        fun fromAddress(addressBytes: ByteArray, prefixLength: Int): Ipv4Subnet {
            require(addressBytes.size == IPV4_BYTES) { "Expected 4-byte IPv4 address" }
            val clamped = prefixLength.coerceIn(0, IPV4_BITS)
            val mask = if (clamped == 0) 0 else (-1 shl (IPV4_BITS - clamped))
            return Ipv4Subnet(packIpv4(addressBytes) and mask, clamped)
        }

        private fun packIpv4(bytes: ByteArray): Int =
            ((bytes[0].toInt() and BYTE_MASK) shl SHIFT_OCTET_0) or
                ((bytes[1].toInt() and BYTE_MASK) shl SHIFT_OCTET_1) or
                ((bytes[2].toInt() and BYTE_MASK) shl SHIFT_OCTET_2) or
                (bytes[LAST_OCTET_INDEX].toInt() and BYTE_MASK)
    }
}

/** Snapshot of the viewer's local LAN connectivity for `DIFFERENT_NETWORK` vs `DEBUGGING_OFF`. */
data class LocalNetworkSnapshot(
    val isLanAvailable: Boolean,
    val ipv4Subnets: List<Ipv4Subnet> = emptyList()
) {
    fun isTargetOnDifferentIpv4Subnet(targetHost: String?): Boolean {
        val trimmed = targetHost?.trim().orEmpty()
        val parsed = parseLiteralIpv4(trimmed)
        val checkApplicable = parsed != null && !isLoopbackIpv4(parsed) && ipv4Subnets.isNotEmpty()
        return checkApplicable && ipv4Subnets.none { it.contains(parsed) }
    }

    private fun isLoopbackIpv4(bytes: ByteArray): Boolean = bytes.size == Ipv4Subnet.IPV4_BYTES &&
        (bytes[0].toInt() and BYTE_MASK) == LOOPBACK_FIRST_OCTET

    private fun parseLiteralIpv4(host: String): ByteArray? {
        val parts = host.split('.')
        val octets = if (parts.size == Ipv4Subnet.IPV4_BYTES) {
            parts.mapNotNull { part -> part.toIntOrNull()?.takeIf { it in 0..MAX_OCTET_VALUE } }
        } else {
            emptyList()
        }
        return if (octets.size == Ipv4Subnet.IPV4_BYTES) {
            ByteArray(Ipv4Subnet.IPV4_BYTES) { idx -> octets[idx].toByte() }
        } else {
            null
        }
    }

    private companion object {
        const val BYTE_MASK = 0xFF
        const val LOOPBACK_FIRST_OCTET = 127
        const val MAX_OCTET_VALUE = 255
    }
}

/** Abstraction over local network interface inspection so error mapping is deterministic in tests. */
fun interface NetworkEnvironmentProbe {
    fun snapshot(): LocalNetworkSnapshot
}

/** Default JVM/Android [NetworkEnvironmentProbe] inspecting non-loopback `NetworkInterface`s. */
object SystemNetworkEnvironmentProbe : NetworkEnvironmentProbe {
    override fun snapshot(): LocalNetworkSnapshot = try {
        val subnets = ArrayList<Ipv4Subnet>()
        var hasActiveLan = false
        val interfaces = NetworkInterface.getNetworkInterfaces()
        while (interfaces != null && interfaces.hasMoreElements()) {
            val nif = interfaces.nextElement()
            if (isUsableLanInterface(nif) && collectSubnets(nif, subnets)) {
                hasActiveLan = true
            }
        }
        LocalNetworkSnapshot(isLanAvailable = hasActiveLan, ipv4Subnets = subnets)
    } catch (_: SocketException) {
        LocalNetworkSnapshot(isLanAvailable = true)
    }

    private fun isUsableLanInterface(nif: NetworkInterface): Boolean =
        nif.isUp && !nif.isLoopback && !nif.isPointToPoint

    private fun collectSubnets(
        nif: NetworkInterface,
        outSubnets: MutableList<Ipv4Subnet>
    ): Boolean {
        var foundNonLoopback = false
        for (ifaceAddr in nif.interfaceAddresses) {
            val addr = ifaceAddr.address
            if (addr != null && !addr.isLoopbackAddress) {
                foundNonLoopback = true
                val prefixLen = ifaceAddr.networkPrefixLength.toInt()
                if (addr is Inet4Address && prefixLen in 1..Ipv4Subnet.IPV4_BITS) {
                    outSubnets += Ipv4Subnet.fromAddress(addr.address, prefixLen)
                }
            }
        }
        return foundNonLoopback
    }
}

/**
 * Maps raw socket, TLS, SPAKE2, and mDNS failures into typed [AdbFailure] instances (`S07A` #4).
 */
class AdbErrorMapper(
    private val networkProbe: NetworkEnvironmentProbe = SystemNetworkEnvironmentProbe
) {
    fun map(
        throwable: Throwable,
        stage: AdbOperationStage,
        targetHost: String? = null,
        wasPreviouslyPaired: Boolean = false
    ): AdbFailure {
        if (throwable is AdbClientException) return throwable.failure
        val net = networkProbe.snapshot()
        val rawDetail = SecretRedactor.redact(throwable.message ?: throwable.javaClass.simpleName)
        return when (stage) {
            AdbOperationStage.DISCOVERY -> mapDiscoveryFailure(
                throwable,
                net,
                targetHost,
                rawDetail
            )

            AdbOperationStage.PAIRING -> mapPairingFailure(throwable, net, targetHost, rawDetail)

            AdbOperationStage.CONNECT ->
                mapConnectFailure(throwable, net, targetHost, wasPreviouslyPaired, rawDetail)

            AdbOperationStage.USB_CONNECT -> mapUsbConnectFailure(throwable, rawDetail)

            AdbOperationStage.STREAM -> AdbFailure.TransportError(rawDetail)
        }
    }

    private fun mapUsbConnectFailure(throwable: Throwable, detail: String): AdbFailure = when {
        throwable is AdbAuthorizationRequiredException -> AdbFailure.AuthExpired(detail)

        throwable is SocketTimeoutException -> AdbFailure.DebuggingOff(detail)

        ThrowableChainInspectors.isConnectAuthRejection(throwable) &&
            throwable !is EOFException -> AdbFailure.AuthExpired(detail)

        else -> AdbFailure.TransportError(detail)
    }

    fun mapDiscoveryTimeout(serviceType: AdbServiceType, targetHost: String? = null): AdbFailure {
        val net = networkProbe.snapshot()
        return if (!net.isLanAvailable || net.isTargetOnDifferentIpv4Subnet(targetHost)) {
            AdbFailure.DifferentNetwork(
                "No local Wi-Fi match while discovering ${serviceType.dnsType}"
            )
        } else {
            AdbFailure.DebuggingOff("Timed out discovering ${serviceType.dnsType}")
        }
    }

    private fun mapDiscoveryFailure(
        throwable: Throwable,
        net: LocalNetworkSnapshot,
        targetHost: String?,
        detail: String
    ): AdbFailure = if (!net.isLanAvailable ||
        ThrowableChainInspectors.isUnreachableNetworkError(throwable) ||
        net.isTargetOnDifferentIpv4Subnet(targetHost)
    ) {
        AdbFailure.DifferentNetwork(detail)
    } else {
        AdbFailure.DebuggingOff(detail)
    }

    private fun mapPairingFailure(
        throwable: Throwable,
        net: LocalNetworkSnapshot,
        targetHost: String?,
        detail: String
    ): AdbFailure = when {
        !net.isLanAvailable ||
            ThrowableChainInspectors.isUnreachableNetworkError(throwable) ||
            (
                throwable is SocketTimeoutException && net.isTargetOnDifferentIpv4Subnet(
                    targetHost
                )
                ) ->
            AdbFailure.DifferentNetwork(detail)

        ThrowableChainInspectors.isConnectionRefused(throwable) ||
            throwable is SocketTimeoutException -> AdbFailure.DebuggingOff(detail)

        ThrowableChainInspectors.isPairingAuthFailure(throwable) ->
            AdbFailure.WrongPairingCode(detail)

        else -> AdbFailure.WrongPairingCode(detail)
    }

    private fun mapConnectFailure(
        throwable: Throwable,
        net: LocalNetworkSnapshot,
        targetHost: String?,
        wasPreviouslyPaired: Boolean,
        detail: String
    ): AdbFailure = when {
        !net.isLanAvailable ||
            ThrowableChainInspectors.isUnreachableNetworkError(throwable) ||
            (
                throwable is SocketTimeoutException && net.isTargetOnDifferentIpv4Subnet(
                    targetHost
                )
                ) ->
            AdbFailure.DifferentNetwork(detail)

        ThrowableChainInspectors.isConnectionRefused(throwable) ||
            throwable is SocketTimeoutException -> AdbFailure.DebuggingOff(detail)

        ThrowableChainInspectors.isConnectAuthRejection(throwable) ->
            authFailureForConnect(wasPreviouslyPaired, detail)

        else -> AdbFailure.TransportError(detail)
    }

    private fun authFailureForConnect(wasPreviouslyPaired: Boolean, detail: String): AdbFailure =
        if (wasPreviouslyPaired) {
            AdbFailure.AuthExpired(detail)
        } else {
            AdbFailure.NotPaired(detail)
        }
}

private object ThrowableChainInspectors {
    private const val CAUSE_INITIAL_CAPACITY = 4
    private const val MAX_CAUSE_DEPTH = 8

    fun isConnectionRefused(throwable: Throwable): Boolean = causalChain(throwable).any { cause ->
        cause is ConnectException ||
            cause.message?.lowercase(Locale.ROOT)?.let { msg ->
                "econnrefused" in msg || "connection refused" in msg
            } == true
    }

    fun isUnreachableNetworkError(throwable: Throwable): Boolean =
        causalChain(throwable).any { cause ->
            cause is NoRouteToHostException ||
                cause is UnknownHostException ||
                cause.message?.lowercase(Locale.ROOT)?.let { msg ->
                    "enetunreach" in msg ||
                        "ehostunreach" in msg ||
                        "network is unreachable" in msg ||
                        "no route to host" in msg
                } == true
        }

    fun isPairingAuthFailure(throwable: Throwable): Boolean = causalChain(throwable).any { cause ->
        cause is AEADBadTagException ||
            cause is GeneralSecurityException ||
            cause is EOFException ||
            cause is SSLException ||
            cause is SocketException
    }

    fun isConnectAuthRejection(throwable: Throwable): Boolean =
        causalChain(throwable).any { cause ->
            cause is SSLHandshakeException ||
                cause is SSLException ||
                cause is GeneralSecurityException ||
                cause is EOFException ||
                cause.message?.lowercase(Locale.ROOT)?.let { msg ->
                    "unauthorised" in msg ||
                        "unauthorized" in msg ||
                        "certificate" in msg ||
                        "auth" in msg ||
                        "connection reset" in msg
                } == true
        }

    private fun causalChain(root: Throwable): List<Throwable> {
        val list = ArrayList<Throwable>(CAUSE_INITIAL_CAPACITY)
        var current: Throwable? = root
        while (current != null && list.size < MAX_CAUSE_DEPTH && current !in list) {
            list += current
            current = current.cause
        }
        return list
    }
}
