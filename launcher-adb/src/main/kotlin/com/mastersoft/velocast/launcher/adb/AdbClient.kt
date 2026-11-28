package com.mastersoft.velocast.launcher.adb

import android.content.Context
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.security.GeneralSecurityException
import java.util.Collections
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Swappable ADB client abstraction (`docs/adb-library-decision.md`, `S07A` #1–#2).
 *
 * Callers in `:launcher-adb` (`AdbLauncher`, `PrepareRunner`, USB OTG) and `:app-viewer` program
 * exclusively against this interface so the underlying implementation can be replaced without
 * touching session or UI code.
 */
interface AdbClient : Closeable {
    /** Currently active ADB connection metadata, or `null` when disconnected. */
    val connectedDevice: AdbConnectedDevice?

    /** Discovery state machine backing Wireless Debugging pairing and dynamic port resolution. */
    val discovery: AdbDiscovery

    /**
     * Generates (on first call) and persists the client RSA-2048 key pair and self-signed X.509
     * certificate in the Keystore-protected [AdbKeyStore].
     */
    fun ensureKeyIdentity(alias: String = AdbKeyStore.DEFAULT_KEY_ALIAS): AdbKeyIdentity

    /**
     * Marks [host] as previously paired/authorised (used when hydrating saved devices so expired
     * 7-day authorisations report [AdbFailure.AuthExpired] rather than [AdbFailure.NotPaired]).
     */
    fun recordKnownPairedHost(host: String)

    /**
     * Pairs with an Android 11+ Wireless Debugging pairing service (`_adb-tls-pairing._tcp`) at
     * `host:port` using the 6-digit [code] (or QR password).
     */
    suspend fun pair(
        host: String,
        port: Int,
        code: String,
        keyAlias: String = AdbKeyStore.DEFAULT_KEY_ALIAS
    ): AdbPairingResult

    /**
     * Builds a standard `WIFI:T:ADB;S:<service>;P:<password>;;` QR payload, invokes [onQrPayload]
     * so the viewer UI can display the QR code, waits for the old phone's Settings QR scanner to
     * advertise the matching `_adb-tls-pairing._tcp` service, and completes SPAKE2 + TLS pairing.
     */
    suspend fun pairWithQr(
        keyAlias: String = AdbKeyStore.DEFAULT_KEY_ALIAS,
        timeoutMs: Long = DEFAULT_QR_PAIR_TIMEOUT_MS,
        onQrPayload: (AdbQrPayload) -> Unit = {}
    ): AdbPairingResult

    /**
     * Connects to `adbd` over TLS (`_adb-tls-connect._tcp`) or legacy auth.
     *
     * - When [port] is `null`, discovers the current Wireless Debugging port via [discovery].
     * - When [host] and [port] are given (e.g. a cached endpoint from an earlier session) and TCP
     *   connection is refused because Wireless Debugging was toggled and picked a new port,
     *   automatically re-discovers `_adb-tls-connect._tcp` on [host] before failing.
     */
    suspend fun connect(
        host: String? = null,
        port: Int? = null,
        keyAlias: String = AdbKeyStore.DEFAULT_KEY_ALIAS
    ): AdbConnectedDevice

    /** Executes [command] as `uid 2000` (`shell`) on the connected phone. */
    suspend fun shell(command: String): AdbShellResult

    /** Pushes [file] to [remotePath] over the ADB `sync:` service. */
    suspend fun push(file: File, remotePath: String, mode: Int = AdbServices.DEFAULT_FILE_MODE)

    /** Opens a raw multiplexed ADB stream (e.g. `"shell:..."`, `"tcp:27183"`, `"sync:"`). */
    suspend fun openStream(service: String): AdbByteStream

    /** Closes any active ADB connection while keeping stored keys intact. */
    suspend fun disconnect()

    companion object {
        const val DEFAULT_QR_PAIR_TIMEOUT_MS = 60_000L
    }
}

/** Tunable timeouts and transport hooks for [InHouseAdbClient]. */
data class AdbClientConfig(
    val connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
    val handshakeReadTimeoutMs: Int = DEFAULT_HANDSHAKE_READ_TIMEOUT_MS,
    val discoveryTimeoutMs: Long = AdbDiscovery.DEFAULT_DISCOVERY_TIMEOUT_MS,
    val portRefreshDiscoveryTimeoutMs: Long = DEFAULT_PORT_REFRESH_TIMEOUT_MS,
    val streamIoTimeoutMs: Long = DEFAULT_STREAM_IO_TIMEOUT_MS,
    val offerPublicKeyOnLegacyAuth: Boolean = false
) {
    companion object {
        const val DEFAULT_CONNECT_TIMEOUT_MS = 5_000
        const val DEFAULT_HANDSHAKE_READ_TIMEOUT_MS = 8_000
        const val DEFAULT_PORT_REFRESH_TIMEOUT_MS = 3_000L
        const val DEFAULT_STREAM_IO_TIMEOUT_MS = 15_000L
    }
}

/** Factory creating the recommended [AdbClient] implementation (`docs/adb-library-decision.md`). */
object AdbClientFactory {
    fun create(context: Context, config: AdbClientConfig = AdbClientConfig()): AdbClient {
        val keyStore = KeystoreProtectedAdbKeyStore(context)
        val errorMapper = AdbErrorMapper()
        val discovery = AdbMdnsDiscovery(
            backend = AndroidNsdBackend(context),
            errorMapper = errorMapper
        )
        return InHouseAdbClient(
            keyStore = keyStore,
            discovery = discovery,
            errorMapper = errorMapper,
            config = config
        )
    }
}

/**
 * Default [AdbClient] implementation backed by Keystore-sealed RSA-2048 X.509 credentials,
 * Conscrypt TLS 1.3 + RFC 5705 exporter, BoringSSL-compatible SPAKE2 (`curve25519-kotlin`), and
 * packet-preserving stream multiplexing.
 */
class InHouseAdbClient internal constructor(
    private val keyStore: AdbKeyStore,
    override val discovery: AdbDiscovery,
    private val errorMapper: AdbErrorMapper = AdbErrorMapper(),
    private val config: AdbClientConfig = AdbClientConfig(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val scrubber: AdbSecretScrubber = AdbSecretScrubber.defaultInstance,
    transportFactory: (String, Int) -> AdbPacketTransport = { host, port ->
        SocketAdbPacketTransport(
            host = host,
            port = port,
            connectTimeoutMs = config.connectTimeoutMs,
            handshakeReadTimeoutMs = config.handshakeReadTimeoutMs
        )
    }
) : AdbClient {
    private val sessionMutex = Mutex()
    private val knownPairedHosts: MutableSet<String> = Collections.synchronizedSet(LinkedHashSet())
    private val runner = AdbSessionCoordinator(
        discovery = discovery,
        errorMapper = errorMapper,
        config = config,
        ioDispatcher = ioDispatcher,
        knownPairedHosts = knownPairedHosts,
        transportFactory = transportFactory
    )

    override val connectedDevice: AdbConnectedDevice?
        get() = runner.connectedDevice

    override fun ensureKeyIdentity(alias: String): AdbKeyIdentity =
        keyStore.getOrCreateIdentity(alias)

    override fun recordKnownPairedHost(host: String) {
        val normalized = host.trim()
        if (normalized.isNotEmpty()) {
            knownPairedHosts.add(normalized)
        }
    }

    override suspend fun pair(
        host: String,
        port: Int,
        code: String,
        keyAlias: String
    ): AdbPairingResult = withContext(ioDispatcher) {
        if (code.isBlank()) {
            throw AdbClientException(AdbFailure.WrongPairingCode("Pairing code must not be blank"))
        }
        scrubber.registerLiteral(code)
        val identity = ensureKeyIdentity(keyAlias)
        runner.pair(host.trim(), port, code, identity)
    }

    override suspend fun pairWithQr(
        keyAlias: String,
        timeoutMs: Long,
        onQrPayload: (AdbQrPayload) -> Unit
    ): AdbPairingResult {
        ensureKeyIdentity(keyAlias)
        val qrPayload = AdbQrPayload.generate(scrubber = scrubber)
        val discovered = discovery.awaitService(
            serviceType = AdbServiceType.TLS_PAIRING,
            timeoutMs = timeoutMs,
            onDiscoveryStarted = { onQrPayload(qrPayload) }
        ) { service ->
            service.serviceName == qrPayload.serviceName
        }
        return pair(
            host = discovered.host,
            port = discovered.port,
            code = qrPayload.password,
            keyAlias = keyAlias
        )
    }

    override suspend fun connect(host: String?, port: Int?, keyAlias: String): AdbConnectedDevice =
        sessionMutex.withLock {
            val identity = ensureKeyIdentity(keyAlias)
            runner.connect(host?.trim()?.takeIf(String::isNotEmpty), port, identity)
        }

    override suspend fun shell(command: String): AdbShellResult =
        runner.executeOnActiveSession { AdbServices.executeShell(it, command) }

    override suspend fun push(file: File, remotePath: String, mode: Int) =
        runner.executeOnActiveSession { AdbServices.pushFile(it, file, remotePath, mode) }

    override suspend fun openStream(service: String): AdbByteStream =
        runner.executeOnActiveSession { it.openStream(service) }

    override suspend fun disconnect() {
        sessionMutex.withLock {
            withContext(ioDispatcher) { runner.closeActiveSession() }
        }
    }

    override fun close() {
        runner.closeActiveSession()
        discovery.close()
    }
}

private class AdbSessionCoordinator(
    private val discovery: AdbDiscovery,
    private val errorMapper: AdbErrorMapper,
    private val config: AdbClientConfig,
    private val ioDispatcher: CoroutineDispatcher,
    private val knownPairedHosts: MutableSet<String>,
    private val transportFactory: (String, Int) -> AdbPacketTransport
) {
    @Volatile
    private var activeSession: AdbConnectionSession? = null

    val connectedDevice: AdbConnectedDevice?
        get() = activeSession?.takeIf { it.isOpen }?.connectedDevice

    fun pair(host: String, port: Int, code: String, identity: AdbKeyIdentity): AdbPairingResult {
        val endpoint = validateEndpoint(host, port, AdbOperationStage.PAIRING)
        return runMapped(AdbOperationStage.PAIRING, targetHost = endpoint.host) {
            AdbPairingProtocol.pairAsClient(
                host = endpoint.host,
                port = endpoint.port,
                pairingCode = code,
                identity = identity,
                connectTimeoutMs = config.connectTimeoutMs,
                readTimeoutMs = config.handshakeReadTimeoutMs
            ).also { knownPairedHosts.add(endpoint.host) }
        }
    }

    suspend fun connect(host: String?, port: Int?, identity: AdbKeyIdentity): AdbConnectedDevice {
        val endpoint = resolveConnectEndpoint(host, port)
        val wasPaired = endpoint.host in knownPairedHosts
        closeActiveSession()
        return try {
            withContext(ioDispatcher) {
                runMapped(AdbOperationStage.CONNECT, endpoint.host, wasPaired) {
                    openAndActivate(endpoint, identity)
                }
            }
        } catch (firstError: AdbClientException) {
            val refreshed = if (port != null && isRefusedConnection(firstError)) {
                tryRefreshPort(endpoint.host, endpoint.port)
            } else {
                null
            }
            if (refreshed == null) throw firstError
            withContext(ioDispatcher) {
                runMapped(AdbOperationStage.CONNECT, refreshed.host, wasPaired) {
                    openAndActivate(refreshed, identity)
                }
            }
        }
    }

    suspend fun <T> executeOnActiveSession(block: (AdbConnectionSession) -> T): T =
        withContext(ioDispatcher) {
            val session = activeSession?.takeIf { it.isOpen }
                ?: throw AdbClientException(
                    AdbFailure.TransportError("ADB client is not connected")
                )
            runMapped(AdbOperationStage.STREAM) { block(session) }
        }

    fun closeActiveSession() {
        activeSession?.close()
        activeSession = null
    }

    private suspend fun resolveConnectEndpoint(host: String?, port: Int?): AdbEndpoint =
        if (host != null && port != null) {
            validateEndpoint(host, port, AdbOperationStage.CONNECT)
        } else {
            discovery.awaitService(
                serviceType = AdbServiceType.TLS_CONNECT,
                timeoutMs = config.discoveryTimeoutMs
            ) { candidate ->
                host == null || candidate.host == host
            }.endpoint
        }

    private suspend fun tryRefreshPort(host: String, stalePort: Int): AdbEndpoint? = try {
        discovery.awaitService(
            serviceType = AdbServiceType.TLS_CONNECT,
            timeoutMs = config.portRefreshDiscoveryTimeoutMs
        ) { service ->
            service.host == host && service.port != stalePort
        }.endpoint
    } catch (_: AdbClientException) {
        null
    }

    private fun openAndActivate(
        endpoint: AdbEndpoint,
        identity: AdbKeyIdentity
    ): AdbConnectedDevice {
        val transport = transportFactory(endpoint.host, endpoint.port)
        val session = AdbConnectionSession.connect(
            host = endpoint.host,
            port = endpoint.port,
            transport = transport,
            identity = identity,
            offerPublicKeyOnAuthPrompt = config.offerPublicKeyOnLegacyAuth,
            streamIoTimeoutMs = config.streamIoTimeoutMs
        )
        activeSession = session
        knownPairedHosts.add(endpoint.host)
        return session.connectedDevice
    }

    private fun validateEndpoint(host: String, port: Int, stage: AdbOperationStage): AdbEndpoint =
        runMapped(stage, targetHost = host) {
            AdbEndpoint(host, port)
        }

    private inline fun <T> runMapped(
        stage: AdbOperationStage,
        targetHost: String? = null,
        wasPaired: Boolean = false,
        block: () -> T
    ): T = try {
        block()
    } catch (e: AdbClientException) {
        throw e
    } catch (e: IOException) {
        throw AdbClientException(errorMapper.map(e, stage, targetHost, wasPaired), e)
    } catch (e: GeneralSecurityException) {
        throw AdbClientException(errorMapper.map(e, stage, targetHost, wasPaired), e)
    } catch (e: IllegalArgumentException) {
        throw AdbClientException(errorMapper.map(e, stage, targetHost, wasPaired), e)
    }

    private fun isRefusedConnection(error: AdbClientException): Boolean {
        val root = error.cause
        return root is ConnectException || root?.cause is ConnectException
    }
}
