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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * USB OTG client operations exposed via [AdbClient.usb] (`S07C` #1–#4).
 */
interface AdbUsbClient {
    /** UI-facing USB OTG state (`Disconnected`, `CableDetected`, `Authorising`, `Ready`). */
    val state: StateFlow<AdbUsbState>

    /** Returns all currently attached USB peripherals inspected via `UsbManager`. */
    fun attachedDevices(): List<AdbUsbDeviceDescriptor> = emptyList()

    /** Registers a callback invoked when a previously connected USB phone re-attaches after reboot. */
    fun observeReattach(listener: (AdbUsbDeviceDescriptor) -> Unit): Closeable = Closeable {}

    /**
     * Requests Android `UsbManager` host permission on the Viewer for the attached ADB cable
     * matching [serialOrDeviceName] (or the first attached ADB device when `null`).
     */
    suspend fun requestPermission(serialOrDeviceName: String? = null): AdbUsbDeviceDescriptor =
        throw AdbClientException(
            AdbFailure.DebuggingOff("USB host transport is not configured on this AdbClient")
        )

    /**
     * Connects to `adbd` over USB OTG (`class 0xFF, subclass 0x42, protocol 0x01`), requesting
     * Viewer `UsbManager` permission if needed and handling the target authorisation dialog
     * (`Authorising` / [AdbFailure.AuthExpired]).
     */
    suspend fun connect(
        serialOrDeviceName: String? = null,
        keyAlias: String = AdbKeyStore.DEFAULT_KEY_ALIAS,
        allowInteractiveAuth: Boolean = true
    ): AdbConnectedDevice
}

/**
 * Swappable ADB client abstraction (`docs/adb-library-decision.md`, `S07A`–`S07C`).
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

    /** USB OTG discovery, permission, state, and connection facet (`S07C`). */
    val usb: AdbUsbClient
        get() = DelegatingFallbackUsbClient(this)

    /** Convenience alias for `usb.state` (`Disconnected`, `CableDetected`, `Authorising`, `Ready`). */
    val usbState: StateFlow<AdbUsbState>
        get() = usb.state

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
     * Connects to `adbd` over TLS (`_adb-tls-connect._tcp`) or legacy TCP auth.
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

private class DelegatingFallbackUsbClient(private val parent: AdbClient) : AdbUsbClient {
    override val state: StateFlow<AdbUsbState> = DefaultUsbStateFlow.flow

    override suspend fun connect(
        serialOrDeviceName: String?,
        keyAlias: String,
        allowInteractiveAuth: Boolean
    ): AdbConnectedDevice =
        parent.connect(host = serialOrDeviceName, port = null, keyAlias = keyAlias)
            .copy(transportKind = AdbTransportKind.USB)
}

private object DefaultUsbStateFlow {
    val flow: StateFlow<AdbUsbState> = MutableStateFlow(AdbUsbState.Disconnected())
}

/** Tunable timeouts and transport hooks for [InHouseAdbClient]. */
data class AdbClientConfig(
    val connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
    val handshakeReadTimeoutMs: Int = DEFAULT_HANDSHAKE_READ_TIMEOUT_MS,
    val discoveryTimeoutMs: Long = AdbDiscovery.DEFAULT_DISCOVERY_TIMEOUT_MS,
    val portRefreshDiscoveryTimeoutMs: Long = DEFAULT_PORT_REFRESH_TIMEOUT_MS,
    val streamIoTimeoutMs: Long = DEFAULT_STREAM_IO_TIMEOUT_MS,
    val offerPublicKeyOnLegacyAuth: Boolean = false,
    val usbPermissionTimeoutMs: Long = DEFAULT_USB_PERMISSION_TIMEOUT_MS,
    val usbAuthPromptTimeoutMs: Int = DEFAULT_USB_AUTH_PROMPT_TIMEOUT_MS,
    val usbWriteTimeoutMs: Int = AdbUsbTransport.DEFAULT_USB_WRITE_TIMEOUT_MS
) {
    companion object {
        const val DEFAULT_CONNECT_TIMEOUT_MS = 5_000
        const val DEFAULT_HANDSHAKE_READ_TIMEOUT_MS = 8_000
        const val DEFAULT_PORT_REFRESH_TIMEOUT_MS = 3_000L
        const val DEFAULT_STREAM_IO_TIMEOUT_MS = 15_000L
        const val DEFAULT_USB_PERMISSION_TIMEOUT_MS = 30_000L
        const val DEFAULT_USB_AUTH_PROMPT_TIMEOUT_MS = 15_000
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
            config = config,
            usbBackend = AndroidUsbHostBackend(context)
        )
    }
}

/**
 * Default [AdbClient] implementation backed by Keystore-sealed RSA-2048 X.509 credentials,
 * Conscrypt TLS 1.3 + RFC 5705 exporter, BoringSSL-compatible SPAKE2 (`curve25519-kotlin`),
 * Android USB Host bulk framing ([AdbUsbTransport]), and packet-preserving stream multiplexing.
 */
class InHouseAdbClient internal constructor(
    private val keyStore: AdbKeyStore,
    override val discovery: AdbDiscovery,
    private val errorMapper: AdbErrorMapper = AdbErrorMapper(),
    private val config: AdbClientConfig = AdbClientConfig(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    usbBackend: UsbHostBackend = NoOpUsbHostBackend,
    transportFactory: (String, Int) -> AdbPacketTransport = { host, port ->
        SocketAdbPacketTransport(
            host = host,
            port = port,
            connectTimeoutMs = config.connectTimeoutMs,
            handshakeReadTimeoutMs = config.handshakeReadTimeoutMs
        )
    }
) : AdbClient {
    private val scrubber: AdbSecretScrubber = AdbSecretScrubber.defaultInstance
    private val sessionMutex = Mutex()
    private val knownPairedHosts: MutableSet<String> = Collections.synchronizedSet(LinkedHashSet())

    @Volatile
    private var usbDetachCallback: () -> Unit = {}

    private val usbController = AdbUsbController(
        backend = usbBackend,
        onActiveDeviceDetached = { usbDetachCallback() }
    )
    private val runner = AdbSessionCoordinator(
        discovery = discovery,
        errorMapper = errorMapper,
        config = config,
        ioDispatcher = ioDispatcher,
        knownPairedHosts = knownPairedHosts,
        usbController = usbController,
        transportFactory = transportFactory
    )

    init {
        usbDetachCallback = { runner.closeUsbSessionOnDetach() }
    }

    override val connectedDevice: AdbConnectedDevice?
        get() = runner.connectedDevice

    override val usb: AdbUsbClient = object : AdbUsbClient {
        override val state: StateFlow<AdbUsbState>
            get() = usbController.state

        override fun attachedDevices(): List<AdbUsbDeviceDescriptor> =
            usbController.attachedDevices()

        override fun observeReattach(listener: (AdbUsbDeviceDescriptor) -> Unit): Closeable =
            usbController.observeReattach(listener)

        override suspend fun requestPermission(
            serialOrDeviceName: String?
        ): AdbUsbDeviceDescriptor {
            val target = usbController.selectTargetAdbDevice(serialOrDeviceName)
            return usbController.ensureViewerPermission(target, config.usbPermissionTimeoutMs)
        }

        override suspend fun connect(
            serialOrDeviceName: String?,
            keyAlias: String,
            allowInteractiveAuth: Boolean
        ): AdbConnectedDevice = sessionMutex.withLock {
            val identity = ensureKeyIdentity(keyAlias)
            runner.connectUsb(serialOrDeviceName, identity, allowInteractiveAuth)
        }
    }

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
            runner.connectWifi(host?.trim()?.takeIf(String::isNotEmpty), port, identity)
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
        usbController.close()
        discovery.close()
    }
}

private class AdbSessionCoordinator(
    private val discovery: AdbDiscovery,
    private val errorMapper: AdbErrorMapper,
    private val config: AdbClientConfig,
    private val ioDispatcher: CoroutineDispatcher,
    private val knownPairedHosts: MutableSet<String>,
    private val usbController: AdbUsbController,
    private val transportFactory: (String, Int) -> AdbPacketTransport
) {
    @Volatile
    private var activeSession: AdbConnectionSession? = null

    val connectedDevice: AdbConnectedDevice?
        get() = activeSession?.takeIf { it.isOpen }?.connectedDevice

    fun pair(host: String, port: Int, code: String, identity: AdbKeyIdentity): AdbPairingResult {
        val endpoint = WifiEndpointResolver.validate(
            host,
            port,
            AdbOperationStage.PAIRING,
            errorMapper
        )
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

    suspend fun connectWifi(
        host: String?,
        port: Int?,
        identity: AdbKeyIdentity
    ): AdbConnectedDevice {
        val endpoint = WifiEndpointResolver.resolve(discovery, config, errorMapper, host, port)
        val wasPaired = endpoint.host in knownPairedHosts
        closeActiveSession()
        return try {
            withContext(ioDispatcher) {
                runMapped(AdbOperationStage.CONNECT, endpoint.host, wasPaired) {
                    openAndActivateWifi(endpoint, identity)
                }
            }
        } catch (firstError: AdbClientException) {
            val refreshed = WifiEndpointResolver.refreshIfRefused(
                discovery = discovery,
                config = config,
                endpoint = endpoint,
                hadExplicitPort = port != null,
                error = firstError
            ) ?: throw firstError
            withContext(ioDispatcher) {
                runMapped(AdbOperationStage.CONNECT, refreshed.host, wasPaired) {
                    openAndActivateWifi(refreshed, identity)
                }
            }
        }
    }

    suspend fun connectUsb(
        serialOrDeviceName: String?,
        identity: AdbKeyIdentity,
        allowInteractiveAuth: Boolean
    ): AdbConnectedDevice {
        val candidate = usbController.selectTargetAdbDevice(serialOrDeviceName)
        val permitted = usbController.ensureViewerPermission(
            target = candidate,
            timeoutMs = config.usbPermissionTimeoutMs
        )
        closeActiveSession()
        return try {
            withContext(ioDispatcher) {
                runMapped(AdbOperationStage.USB_CONNECT) {
                    openAndActivateUsb(permitted, identity, allowInteractiveAuth)
                }
            }
        } catch (e: AdbClientException) {
            usbController.registry.onSessionTransition(
                permitted,
                connected = null,
                failure = e.failure
            )
            throw e
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
        val previous = activeSession
        activeSession = null
        val wasUsb = previous?.connectedDevice?.transportKind == AdbTransportKind.USB
        previous?.close()
        if (wasUsb) {
            usbController.registry.onSessionTransition(
                device = null,
                connected = null,
                failure = null
            )
        }
    }

    fun closeUsbSessionOnDetach() {
        val current = activeSession
        if (current?.connectedDevice?.transportKind == AdbTransportKind.USB) {
            activeSession = null
            current.close()
        }
    }

    private fun openAndActivateWifi(
        endpoint: AdbEndpoint,
        identity: AdbKeyIdentity
    ): AdbConnectedDevice {
        val transport = transportFactory(endpoint.host, endpoint.port)
        val session = AdbConnectionSession.connectWithOptions(
            transport = transport,
            identity = identity,
            options = AdbHandshakeOptions(
                host = endpoint.host,
                port = endpoint.port,
                transportKind = AdbTransportKind.WIFI,
                offerPublicKeyOnAuthPrompt = config.offerPublicKeyOnLegacyAuth,
                handshakeReadTimeoutMs = config.handshakeReadTimeoutMs,
                authPromptTimeoutMs = config.usbAuthPromptTimeoutMs,
                streamIoTimeoutMs = config.streamIoTimeoutMs
            )
        )
        activeSession = session
        knownPairedHosts.add(endpoint.host)
        return session.connectedDevice
    }

    private fun openAndActivateUsb(
        device: AdbUsbDeviceDescriptor,
        identity: AdbKeyIdentity,
        allowInteractiveAuth: Boolean
    ): AdbConnectedDevice {
        val bulkConnection = usbController.openBulkConnection(device)
        val transport = AdbUsbTransport(
            connection = bulkConnection,
            handshakeReadTimeoutMs = config.handshakeReadTimeoutMs,
            writeTimeoutMs = config.usbWriteTimeoutMs
        )
        val usbHostLabel = "usb:${device.serialNumber ?: device.deviceName}"
        val session = AdbConnectionSession.connectWithOptions(
            transport = transport,
            identity = identity,
            options = AdbHandshakeOptions(
                host = usbHostLabel,
                port = 0,
                transportKind = AdbTransportKind.USB,
                usbSerialNumber = device.serialNumber,
                offerPublicKeyOnAuthPrompt = allowInteractiveAuth,
                handshakeReadTimeoutMs = config.handshakeReadTimeoutMs,
                authPromptTimeoutMs = config.usbAuthPromptTimeoutMs,
                streamIoTimeoutMs = config.streamIoTimeoutMs,
                onAuthPromptRequired = {
                    usbController.registry.onAuthorising(
                        device,
                        AdbUsbAuthStage.DEVICE_ADB_APPROVAL
                    )
                }
            )
        )
        activeSession = session
        usbController.registry.onSessionTransition(
            device = device,
            connected = session.connectedDevice,
            failure = null
        )
        return session.connectedDevice
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
}

private object WifiEndpointResolver {
    fun validate(
        host: String,
        port: Int,
        stage: AdbOperationStage,
        errorMapper: AdbErrorMapper
    ): AdbEndpoint = try {
        AdbEndpoint(host, port)
    } catch (e: IllegalArgumentException) {
        throw AdbClientException(errorMapper.map(e, stage, targetHost = host), e)
    }

    suspend fun resolve(
        discovery: AdbDiscovery,
        config: AdbClientConfig,
        errorMapper: AdbErrorMapper,
        host: String?,
        port: Int?
    ): AdbEndpoint = if (host != null && port != null) {
        validate(host, port, AdbOperationStage.CONNECT, errorMapper)
    } else {
        discovery.awaitService(
            serviceType = AdbServiceType.TLS_CONNECT,
            timeoutMs = config.discoveryTimeoutMs
        ) { candidate ->
            host == null || candidate.host == host
        }.endpoint
    }

    suspend fun refreshIfRefused(
        discovery: AdbDiscovery,
        config: AdbClientConfig,
        endpoint: AdbEndpoint,
        hadExplicitPort: Boolean,
        error: AdbClientException
    ): AdbEndpoint? {
        val root = error.cause
        val refused = root is ConnectException || root?.cause is ConnectException
        if (!hadExplicitPort || !refused) return null
        return try {
            discovery.awaitService(
                serviceType = AdbServiceType.TLS_CONNECT,
                timeoutMs = config.portRefreshDiscoveryTimeoutMs
            ) { service ->
                service.host == endpoint.host && service.port != endpoint.port
            }.endpoint
        } catch (_: AdbClientException) {
            null
        }
    }
}
