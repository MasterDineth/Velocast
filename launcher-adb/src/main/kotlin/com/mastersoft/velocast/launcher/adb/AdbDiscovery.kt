package com.mastersoft.velocast.launcher.adb

import java.io.Closeable
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** mDNS DNS-SD service types used by Android 11+ Wireless Debugging (`S07A` #3). */
enum class AdbServiceType(val dnsType: String) {
    TLS_PAIRING("_adb-tls-pairing._tcp"),
    TLS_CONNECT("_adb-tls-connect._tcp")
}

/** Validated `host:port` endpoint for ADB pairing or connection. */
data class AdbEndpoint(val host: String, val port: Int) {
    init {
        require(host.isNotBlank()) { "host must not be blank" }
        require(port in MIN_PORT..MAX_PORT) { "port must be in $MIN_PORT..$MAX_PORT, got $port" }
    }

    override fun toString(): String =
        if (':' in host && !host.startsWith('[')) "[$host]:$port" else "$host:$port"

    companion object {
        const val MIN_PORT = 1
        const val MAX_PORT = 65_535

        /**
         * Parses a manual `host:port` string (`192.168.1.42:37199` or `[fe80::1]:37199`).
         */
        fun parse(hostPort: String): AdbEndpoint {
            val trimmed = hostPort.trim()
            require(trimmed.isNotEmpty()) { "Manual host:port must not be empty" }
            val hostPart: String
            val portPart: String
            if (trimmed.startsWith('[')) {
                val closeIdx = trimmed.indexOf(']')
                require(
                    closeIdx > 1 && closeIdx + 2 <= trimmed.length && trimmed[closeIdx + 1] == ':'
                ) {
                    "Invalid bracketed IPv6 host:port '$trimmed'"
                }
                hostPart = trimmed.substring(1, closeIdx)
                portPart = trimmed.substring(closeIdx + 2)
            } else {
                val colonIdx = trimmed.lastIndexOf(':')
                require(colonIdx > 0 && trimmed.indexOf(':') == colonIdx) {
                    "Expected host:port format, got '$trimmed'"
                }
                hostPart = trimmed.substring(0, colonIdx).trim()
                portPart = trimmed.substring(colonIdx + 1).trim()
            }
            val port = requireNotNull(portPart.toIntOrNull()) {
                "Invalid TCP port in '$trimmed'"
            }
            return AdbEndpoint(hostPart, port)
        }
    }
}

/** Resolved Wireless Debugging service advertised over mDNS. */
data class DiscoveredAdbService(
    val serviceName: String,
    val serviceType: AdbServiceType,
    val host: String,
    val port: Int,
    val attributes: Map<String, String> = emptyMap(),
    val updatedAtSequence: Long = 0L
) {
    val endpoint: AdbEndpoint get() = AdbEndpoint(host, port)

    /**
     * Extracts the stable `adb-<serial>` identity prefix from AOSP mDNS instance names such as
     * `adb-6f8d1516-xY9kL2` so port changes across Wireless Debugging toggles correlate cleanly.
     */
    val deviceInstancePrefix: String
        get() {
            val parts = serviceName.split('-')
            return if (parts.size >= INSTANCE_PARTS_MIN && parts[0] == "adb") {
                "${parts[0]}-${parts[1]}"
            } else {
                serviceName
            }
        }

    private companion object {
        const val INSTANCE_PARTS_MIN = 3
    }
}

/** Listener callbacks emitted by an [NsdBackend] subscription. */
interface NsdDiscoveryListener {
    fun onServiceResolved(service: DiscoveredAdbService)

    fun onServiceLost(serviceName: String, serviceType: AdbServiceType)

    fun onDiscoveryFailed(cause: Throwable)
}

/** Platform-neutral mDNS backend implemented by [AndroidNsdBackend] and test fakes. */
interface NsdBackend {
    @Throws(IOException::class)
    fun startDiscovery(serviceType: AdbServiceType, listener: NsdDiscoveryListener): Closeable

    @Throws(IOException::class)
    fun registerService(serviceName: String, serviceType: AdbServiceType, port: Int): Closeable =
        Closeable {}
}

/** Observable state machine for `_adb-tls-pairing._tcp` and `_adb-tls-connect._tcp` discovery. */
sealed interface AdbDiscoveryState {
    data object Idle : AdbDiscoveryState

    data class Searching(val serviceType: AdbServiceType, val timeoutMs: Long) : AdbDiscoveryState

    data class Found(val serviceType: AdbServiceType, val services: List<DiscoveredAdbService>) :
        AdbDiscoveryState

    data class TimedOut(
        val serviceType: AdbServiceType,
        val failure: AdbFailure,
        val manualFallbackAvailable: Boolean = true
    ) : AdbDiscoveryState

    data class UsingManualEndpoint(val serviceType: AdbServiceType, val endpoint: AdbEndpoint) :
        AdbDiscoveryState

    data class Failed(
        val serviceType: AdbServiceType,
        val failure: AdbFailure,
        val manualFallbackAvailable: Boolean = true
    ) : AdbDiscoveryState
}

/**
 * Discovery coordinator contract for ADB pairing and connect services with timeout and manual
 * `host:port` fallback (`S07A` #3).
 */
interface AdbDiscovery : Closeable {
    val state: StateFlow<AdbDiscoveryState>

    fun startDiscovery(serviceType: AdbServiceType, timeoutMs: Long = DEFAULT_DISCOVERY_TIMEOUT_MS)

    fun stopDiscovery()

    fun useManualEndpoint(
        hostPort: String,
        serviceType: AdbServiceType = AdbServiceType.TLS_CONNECT
    ): AdbEndpoint

    suspend fun awaitService(
        serviceType: AdbServiceType,
        timeoutMs: Long = DEFAULT_DISCOVERY_TIMEOUT_MS,
        onDiscoveryStarted: () -> Unit = {},
        predicate: (DiscoveredAdbService) -> Boolean = { true }
    ): DiscoveredAdbService

    companion object {
        const val DEFAULT_DISCOVERY_TIMEOUT_MS = 10_000L
    }
}

/**
 * Coroutine-driven [AdbDiscovery] state machine handling dynamic port replacement whenever
 * Wireless Debugging is toggled off and back on (`S07A` #3).
 */
class AdbMdnsDiscovery(
    private val backend: NsdBackend,
    private val errorMapper: AdbErrorMapper = AdbErrorMapper(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) : AdbDiscovery {
    private val lock = Any()
    private val _state = MutableStateFlow<AdbDiscoveryState>(AdbDiscoveryState.Idle)
    override val state: StateFlow<AdbDiscoveryState> = _state.asStateFlow()

    private val activeServices = LinkedHashMap<String, DiscoveredAdbService>()
    private var activeHandle: Closeable? = null
    private var timeoutJob: Job? = null
    private var activeType: AdbServiceType? = null
    private var sequenceCounter = 0L

    override fun startDiscovery(serviceType: AdbServiceType, timeoutMs: Long) {
        require(timeoutMs > 0L) { "timeoutMs must be positive" }
        synchronized(lock) {
            stopUnlocked(resetState = false)
            activeType = serviceType
            _state.value = AdbDiscoveryState.Searching(serviceType, timeoutMs)
            activeHandle = try {
                backend.startDiscovery(serviceType, BackendCallback(serviceType))
            } catch (e: IOException) {
                val mapped = errorMapper.map(e, AdbOperationStage.DISCOVERY)
                _state.value = AdbDiscoveryState.Failed(serviceType, mapped)
                return
            } catch (e: IllegalArgumentException) {
                val mapped = errorMapper.map(e, AdbOperationStage.DISCOVERY)
                _state.value = AdbDiscoveryState.Failed(serviceType, mapped)
                return
            }
            armTimeoutUnlocked(serviceType, timeoutMs)
        }
    }

    override fun stopDiscovery() {
        synchronized(lock) { stopUnlocked(resetState = true) }
    }

    override fun useManualEndpoint(hostPort: String, serviceType: AdbServiceType): AdbEndpoint {
        val endpoint = AdbEndpoint.parse(hostPort)
        synchronized(lock) {
            stopUnlocked(resetState = false)
            _state.value = AdbDiscoveryState.UsingManualEndpoint(serviceType, endpoint)
        }
        return endpoint
    }

    override suspend fun awaitService(
        serviceType: AdbServiceType,
        timeoutMs: Long,
        onDiscoveryStarted: () -> Unit,
        predicate: (DiscoveredAdbService) -> Boolean
    ): DiscoveredAdbService {
        startDiscovery(serviceType, timeoutMs)
        onDiscoveryStarted()
        var terminalFailure: AdbFailure? = null
        val matched = withTimeoutOrNull(timeoutMs) {
            var found: DiscoveredAdbService? = null
            state.first { current ->
                when (current) {
                    is AdbDiscoveryState.Found -> {
                        found = current.services
                            .sortedByDescending { it.updatedAtSequence }
                            .firstOrNull(predicate)
                        found != null
                    }

                    is AdbDiscoveryState.UsingManualEndpoint -> {
                        val synthetic = DiscoveredAdbService(
                            serviceName = "manual",
                            serviceType = current.serviceType,
                            host = current.endpoint.host,
                            port = current.endpoint.port
                        )
                        found = synthetic.takeIf(predicate)
                        found != null
                    }

                    is AdbDiscoveryState.Failed -> {
                        terminalFailure = current.failure
                        true
                    }

                    is AdbDiscoveryState.TimedOut -> {
                        terminalFailure = current.failure
                        true
                    }

                    else -> false
                }
            }
            found
        }
        if (matched != null) return matched
        val resolvedFailure = terminalFailure ?: errorMapper.mapDiscoveryTimeout(serviceType)
        synchronized(lock) {
            if (terminalFailure == null &&
                activeType == serviceType &&
                activeServices.values.none(predicate)
            ) {
                stopBackendOnlyUnlocked()
                _state.value = AdbDiscoveryState.TimedOut(serviceType, resolvedFailure)
            }
        }
        throw AdbClientException(resolvedFailure)
    }

    override fun close() {
        stopDiscovery()
    }

    private fun onResolved(serviceType: AdbServiceType, service: DiscoveredAdbService) {
        synchronized(lock) {
            if (activeType != serviceType) return
            val seq = ++sequenceCounter
            val stamped = service.copy(updatedAtSequence = seq)

            val staleKeys = activeServices.entries
                .filter { (_, existing) ->
                    existing.serviceName == stamped.serviceName ||
                        (
                            existing.host == stamped.host &&
                                existing.deviceInstancePrefix == stamped.deviceInstancePrefix
                            )
                }
                .map { it.key }
            for (key in staleKeys) {
                activeServices.remove(key)
            }
            activeServices[stamped.serviceName] = stamped
            timeoutJob?.cancel()
            timeoutJob = null
            _state.value = AdbDiscoveryState.Found(
                serviceType = serviceType,
                services = activeServices.values.sortedByDescending { it.updatedAtSequence }
            )
        }
    }

    private fun onLost(serviceType: AdbServiceType, serviceName: String) {
        synchronized(lock) {
            if (activeType != serviceType) return
            val removed = activeServices.remove(serviceName) ?: return
            if (activeServices.isEmpty()) {
                _state.value = AdbDiscoveryState.Searching(
                    serviceType = serviceType,
                    timeoutMs = AdbDiscovery.DEFAULT_DISCOVERY_TIMEOUT_MS
                )
            } else {
                _state.value = AdbDiscoveryState.Found(
                    serviceType = removed.serviceType,
                    services = activeServices.values.sortedByDescending { it.updatedAtSequence }
                )
            }
        }
    }

    private fun onFailed(serviceType: AdbServiceType, cause: Throwable) {
        synchronized(lock) {
            if (activeType != serviceType) return
            stopBackendOnlyUnlocked()
            val mapped = errorMapper.map(cause, AdbOperationStage.DISCOVERY)
            _state.value = AdbDiscoveryState.Failed(serviceType, mapped)
        }
    }

    private fun armTimeoutUnlocked(serviceType: AdbServiceType, timeoutMs: Long) {
        timeoutJob?.cancel()
        timeoutJob = scope.launch {
            try {
                delay(timeoutMs)
                synchronized(lock) {
                    if (activeType == serviceType && activeServices.isEmpty()) {
                        stopBackendOnlyUnlocked()
                        val failure = errorMapper.mapDiscoveryTimeout(serviceType)
                        _state.value = AdbDiscoveryState.TimedOut(serviceType, failure)
                    }
                }
            } catch (_: CancellationException) {
                // Cancelled when a service resolves or discovery stops.
            }
        }
    }

    private fun stopUnlocked(resetState: Boolean) {
        timeoutJob?.cancel()
        timeoutJob = null
        stopBackendOnlyUnlocked()
        activeServices.clear()
        activeType = null
        if (resetState) {
            _state.value = AdbDiscoveryState.Idle
        }
    }

    private fun stopBackendOnlyUnlocked() {
        try {
            activeHandle?.close()
        } catch (_: IOException) {
            // Ignore cleanup errors on already-stopped NsdManager listeners.
        } finally {
            activeHandle = null
        }
    }

    private inner class BackendCallback(private val serviceType: AdbServiceType) :
        NsdDiscoveryListener {
        override fun onServiceResolved(service: DiscoveredAdbService) =
            onResolved(serviceType, service)

        override fun onServiceLost(serviceName: String, serviceType: AdbServiceType) =
            onLost(this.serviceType, serviceName)

        override fun onDiscoveryFailed(cause: Throwable) = onFailed(serviceType, cause)
    }
}
