package com.mastersoft.velocast.launcher.adb

import com.mastersoft.velocast.client.DataChannelMode
import com.mastersoft.velocast.client.LaunchPhase
import com.mastersoft.velocast.client.ServerEndpoint
import com.mastersoft.velocast.client.ServerLauncher
import com.mastersoft.velocast.client.SessionError
import com.mastersoft.velocast.client.SessionErrorException
import com.mastersoft.velocast.protocol.AuthMode
import com.mastersoft.velocast.protocol.HkdfAuthenticator
import com.mastersoft.velocast.protocol.SessionSecrets
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Underlying ADB transport used to reach the target phone (`S07B`). */
enum class AdbTransportKind {
    WIFI,
    USB
}

/** Policy controlling how [AdbLauncher] chooses between direct TCP and an ADB stream tunnel. */
enum class DataChannelPreference {
    AUTO,
    FORCE_DIRECT_TCP,
    FORCE_ADB_TUNNEL
}

/** Observable data-channel decision exposed by [AdbLauncher] for diagnostics. */
data class DataChannelDecision(
    val mode: DataChannelMode,
    val reason: String,
    val bindAddress: String,
    val remotePort: Int
)

/** Configuration for [AdbLauncher]. */
data class AdbLauncherConfig(
    val host: String? = null,
    val port: Int? = null,
    val keyAlias: String = AdbKeyStore.DEFAULT_KEY_ALIAS,
    val transportKind: AdbTransportKind = AdbTransportKind.WIFI,
    val dataChannelPreference: DataChannelPreference = DataChannelPreference.AUTO,
    val remoteJarPath: String = DEFAULT_REMOTE_JAR_PATH,
    val readyTimeoutMs: Long = DEFAULT_READY_TIMEOUT_MS,
    val bindHostOverride: String? = null
) {
    init {
        require(remoteJarPath.isNotBlank()) { "remoteJarPath must not be blank" }
        require(readyTimeoutMs > 0L) { "readyTimeoutMs must be positive: $readyTimeoutMs" }
    }

    companion object {
        const val DEFAULT_REMOTE_JAR_PATH = "/data/local/tmp/velocast-server.jar"
        const val DEFAULT_READY_TIMEOUT_MS = 10_000L
    }
}

internal data class ReadyLine(val port: Int, val nonce: String?)

internal object ReadyLineParser {
    private const val PREFIX = "READY"
    private const val MIN_PORT = 1
    private const val MAX_PORT = 65_535
    private val WHITESPACE = Regex("\\s+")

    fun isReadyCandidate(line: String): Boolean {
        val trimmed = line.trim()
        return trimmed == PREFIX || trimmed.startsWith("$PREFIX ")
    }

    fun parse(line: String): ReadyLine {
        val trimmed = line.trim()
        if (!isReadyCandidate(trimmed)) {
            throw IllegalArgumentException("Expected READY line, got '$trimmed'")
        }
        val tokens = trimmed.split(WHITESPACE)
        var port: Int? = null
        var nonce: String? = null
        for (i in 1 until tokens.size) {
            val token = tokens[i]
            val eqIdx = token.indexOf('=')
            if (eqIdx > 0 && eqIdx < token.length - 1) {
                val key = token.substring(0, eqIdx)
                val value = token.substring(eqIdx + 1)
                when (key) {
                    "port" -> port = value.toIntOrNull()
                    "nonce" -> nonce = value
                }
            }
        }
        val validPort = port?.takeIf { it in MIN_PORT..MAX_PORT }
            ?: throw IllegalArgumentException("Missing or invalid port in READY line: '$trimmed'")
        return ReadyLine(port = validPort, nonce = nonce)
    }
}

/**
 * [ServerLauncher] implementation that connects over [AdbClient], verifies/pushes
 * `velocast-server.jar` by SHA-256, launches `:server-core` as `uid 2000` with
 * `--secret-from-stdin`, writes the 256-bit per-session secret strictly to stdin, waits for
 * `READY`, and keeps the shell stream open for the lifetime of the session (`S07B`).
 */
class AdbLauncher(
    private val adbClient: AdbClient,
    private val jarSource: ServerJarSource,
    private val config: AdbLauncherConfig = AdbLauncherConfig(),
    private val secretGenerator: () -> ByteArray = { SessionSecrets.generate() },
    private val scrubber: AdbSecretScrubber = AdbSecretScrubber.defaultInstance,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val onDiagnostics: (category: String, message: String) -> Unit = { _, _ -> }
) : ServerLauncher,
    Closeable {

    private val lifecycleMutex = Mutex()
    private val drainScope = CoroutineScope(SupervisorJob() + ioDispatcher)

    private val _lastDataChannelDecision = MutableStateFlow<DataChannelDecision?>(null)
    val lastDataChannelDecision: StateFlow<DataChannelDecision?> =
        _lastDataChannelDecision.asStateFlow()

    @Volatile
    private var activeShellStream: AdbByteStream? = null

    @Volatile
    private var activeAuthenticator: HkdfAuthenticator? = null

    @Volatile
    private var activeDrainJob: Job? = null

    @Volatile
    private var preferTunnelForSession: Boolean = false

    override suspend fun start(onPhase: (LaunchPhase) -> Unit): ServerEndpoint =
        lifecycleMutex.withLock {
            stopActiveProcessLocked()
            if (config.host == null || config.port == null) {
                onPhase(LaunchPhase.DISCOVERING)
            }
            onPhase(LaunchPhase.CONNECTING)
            val connected = connectAdb()

            onPhase(LaunchPhase.STARTING_SERVER)
            RemoteServerJarSync.ensureSynced(
                adbClient = adbClient,
                jarSource = jarSource,
                remoteJarPath = config.remoteJarPath,
                scrubber = scrubber,
                ioDispatcher = ioDispatcher,
                onDiagnostics = onDiagnostics
            )

            val selection = DataChannelSelector.select(config, connected, preferTunnelForSession)
            launchServerProcessLocked(selection)
        }

    override suspend fun fallbackAfterDirectConnectFailure(
        failedEndpoint: ServerEndpoint,
        cause: SessionError
    ): ServerEndpoint? = lifecycleMutex.withLock {
        val eligible = config.dataChannelPreference == DataChannelPreference.AUTO &&
            failedEndpoint.dataChannelMode == DataChannelMode.DIRECT_TCP &&
            adbClient.connectedDevice != null
        if (!eligible) {
            return@withLock null
        }
        stopActiveProcessLocked()
        preferTunnelForSession = true
        val reasonDetail = scrubber.redact(cause.detail ?: cause.code.name)
        onDiagnostics(
            CATEGORY_DATA_CHANNEL,
            "Direct TCP connect failed ($reasonDetail); falling back to ADB tunnel"
        )
        launchServerProcessLocked(
            ChannelSelection(
                mode = DataChannelMode.ADB_TUNNEL,
                reason = REASON_WIFI_DIRECT_FALLBACK,
                bindArg = BIND_LOOPBACK,
                connectHost = LOOPBACK_HOST
            )
        )
    }

    override suspend fun stop() {
        lifecycleMutex.withLock {
            stopActiveProcessLocked()
        }
    }

    override fun close() {
        activeDrainJob?.cancel()
        activeDrainJob = null
        closeQuietly(activeShellStream)
        activeShellStream = null
        activeAuthenticator?.close()
        activeAuthenticator = null
        drainScope.cancel()
    }

    private suspend fun connectAdb(): AdbConnectedDevice = try {
        adbClient.connect(
            host = config.host,
            port = config.port,
            keyAlias = config.keyAlias
        )
    } catch (e: AdbClientException) {
        throw SessionErrorException(e.failure.toSessionError(), e)
    }

    private suspend fun launchServerProcessLocked(selection: ChannelSelection): ServerEndpoint {
        val command = ServerProcessIo.buildCommand(config.remoteJarPath, selection.bindArg)
        val secret = secretGenerator()
        val authenticator = HkdfAuthenticator(secret, AuthMode.SESSION_SECRET)
        val secretHex = Sha256.toLowerHex(secret)
        scrubber.registerLiteral(secretHex)
        secret.fill(0)

        val stream = ServerProcessIo.openShellStream(adbClient, command, authenticator, scrubber)
        val ready = try {
            ServerProcessIo.writeSecretToStdin(stream, secretHex, ioDispatcher)
            ServerProcessIo.awaitReadyLine(stream, config.readyTimeoutMs, scrubber, ioDispatcher)
        } catch (e: SessionErrorException) {
            closeQuietly(stream)
            authenticator.close()
            throw e
        } catch (e: IOException) {
            closeQuietly(stream)
            authenticator.close()
            throw SessionErrorException(
                SessionError.ServerStartFailed(
                    scrubber.redact(e.message ?: "Failed starting remote server")
                ),
                e
            )
        }

        stream.setReadTimeout(0)
        activeShellStream = stream
        activeAuthenticator = authenticator
        activeDrainJob = launchOutputDrain(stream)

        val decision = DataChannelDecision(
            mode = selection.mode,
            reason = selection.reason,
            bindAddress = selection.bindArg,
            remotePort = ready.port
        )
        _lastDataChannelDecision.value = decision
        onDiagnostics(
            CATEGORY_DATA_CHANNEL,
            "mode=${decision.mode} reason=${decision.reason} " +
                "bind=${decision.bindAddress} port=${decision.remotePort}"
        )

        val connector = if (selection.mode == DataChannelMode.ADB_TUNNEL) {
            AdbTunnelSocketConnector(adbClient)
        } else {
            null
        }
        return ServerEndpoint(
            host = selection.connectHost,
            port = ready.port,
            authenticator = authenticator,
            sessionId = ready.nonce,
            dataChannelMode = selection.mode,
            dataChannelReason = selection.reason,
            tunnelConnector = connector
        )
    }

    private fun launchOutputDrain(stream: AdbByteStream): Job = drainScope.launch {
        try {
            val input = stream.inputStream
            while (isActive && !stream.isClosed) {
                val line = ServerProcessIo.readLineLf(input) ?: break
                val trimmed = line.trim()
                if (trimmed.isNotEmpty()) {
                    onDiagnostics(CATEGORY_SERVER_STDOUT, scrubber.redact(trimmed))
                }
            }
        } catch (_: IOException) {
            // Stream closed when session stopped or server exited.
        }
    }

    private suspend fun stopActiveProcessLocked() {
        val job = activeDrainJob.also { activeDrainJob = null }
        val stream = activeShellStream.also { activeShellStream = null }
        val auth = activeAuthenticator.also { activeAuthenticator = null }
        closeQuietly(stream)
        auth?.close()
        job?.cancel()
        job?.join()
    }

    companion object {
        const val SERVER_MAIN_CLASS = "com.mastersoft.velocast.server.Main"
        const val BIND_LOOPBACK = "loopback"
        const val LOOPBACK_HOST = "127.0.0.1"

        const val REASON_USB_TRANSPORT = "usb_transport"
        const val REASON_FORCED_TUNNEL = "forced_tunnel"
        const val REASON_WIFI_FALLBACK_STICKY = "wifi_fallback_sticky"
        const val REASON_WIFI_DIRECT_FALLBACK = "wifi_direct_tcp_fallback"
        const val REASON_WIFI_DIRECT_TCP = "wifi_direct_tcp"
        const val REASON_NO_WLAN_IP = "no_wlan_ip"

        private const val CATEGORY_DATA_CHANNEL = "DATA_CHANNEL"
        private const val CATEGORY_SERVER_STDOUT = "SERVER_STDOUT"
    }
}

internal data class ChannelSelection(
    val mode: DataChannelMode,
    val reason: String,
    val bindArg: String,
    val connectHost: String
)

private object RemoteServerJarSync {
    private const val CATEGORY_SERVER_JAR = "SERVER_JAR"

    suspend fun ensureSynced(
        adbClient: AdbClient,
        jarSource: ServerJarSource,
        remoteJarPath: String,
        scrubber: AdbSecretScrubber,
        ioDispatcher: CoroutineDispatcher,
        onDiagnostics: (String, String) -> Unit
    ) {
        val localJar = withContext(ioDispatcher) { jarSource.resolveLocalJar() }
        val expectedSha256 = withContext(ioDispatcher) {
            Sha256.hashFileHex(localJar).lowercase(Locale.ROOT)
        }
        val initialRemoteHash = queryRemoteSha256(adbClient, remoteJarPath)
        if (initialRemoteHash == expectedSha256) {
            onDiagnostics(CATEGORY_SERVER_JAR, "Remote jar SHA-256 matched ($expectedSha256)")
            return
        }

        onDiagnostics(
            CATEGORY_SERVER_JAR,
            "Pushing server jar to $remoteJarPath (remote=${initialRemoteHash ?: "missing"})"
        )
        pushJarOrThrow(adbClient, localJar, remoteJarPath, scrubber)

        val verifiedHash = queryRemoteSha256(adbClient, remoteJarPath)
        if (verifiedHash != expectedSha256) {
            throw SessionErrorException(
                SessionError.ServerStartFailed(
                    "Server jar SHA-256 mismatch after push " +
                        "(expected=$expectedSha256 actual=${verifiedHash ?: "missing"})"
                )
            )
        }
    }

    private suspend fun pushJarOrThrow(
        adbClient: AdbClient,
        localJar: File,
        remoteJarPath: String,
        scrubber: AdbSecretScrubber
    ) {
        try {
            adbClient.push(localJar, remoteJarPath)
        } catch (e: AdbClientException) {
            throw SessionErrorException(e.failure.toSessionError(), e)
        } catch (e: IOException) {
            throw SessionErrorException(
                SessionError.ServerStartFailed(scrubber.redact(e.message ?: "ADB push failed")),
                e
            )
        }
    }

    private suspend fun queryRemoteSha256(adbClient: AdbClient, remoteJarPath: String): String? =
        try {
            val result = adbClient.shell("sha256sum $remoteJarPath")
            Sha256.parseRemoteSha256Output(result)
        } catch (e: AdbClientException) {
            throw SessionErrorException(e.failure.toSessionError(), e)
        }
}

private object DataChannelSelector {
    fun select(
        config: AdbLauncherConfig,
        connected: AdbConnectedDevice,
        preferTunnelForSession: Boolean
    ): ChannelSelection {
        val candidateIp = (config.bindHostOverride ?: connected.host).trim()
        val directIp = candidateIp.takeIf { IpLiteralCheck.isNonLoopbackIpLiteral(it) }
        return when {
            config.transportKind == AdbTransportKind.USB -> ChannelSelection(
                mode = DataChannelMode.ADB_TUNNEL,
                reason = AdbLauncher.REASON_USB_TRANSPORT,
                bindArg = AdbLauncher.BIND_LOOPBACK,
                connectHost = AdbLauncher.LOOPBACK_HOST
            )

            config.dataChannelPreference == DataChannelPreference.FORCE_ADB_TUNNEL ->
                ChannelSelection(
                    mode = DataChannelMode.ADB_TUNNEL,
                    reason = AdbLauncher.REASON_FORCED_TUNNEL,
                    bindArg = AdbLauncher.BIND_LOOPBACK,
                    connectHost = AdbLauncher.LOOPBACK_HOST
                )

            preferTunnelForSession -> ChannelSelection(
                mode = DataChannelMode.ADB_TUNNEL,
                reason = AdbLauncher.REASON_WIFI_FALLBACK_STICKY,
                bindArg = AdbLauncher.BIND_LOOPBACK,
                connectHost = AdbLauncher.LOOPBACK_HOST
            )

            directIp != null -> ChannelSelection(
                mode = DataChannelMode.DIRECT_TCP,
                reason = AdbLauncher.REASON_WIFI_DIRECT_TCP,
                bindArg = directIp,
                connectHost = directIp
            )

            config.dataChannelPreference == DataChannelPreference.FORCE_DIRECT_TCP ->
                throw SessionErrorException(
                    SessionError.ServerStartFailed(
                        "Direct TCP requested but no non-loopback WLAN IP was resolved"
                    )
                )

            else -> ChannelSelection(
                mode = DataChannelMode.ADB_TUNNEL,
                reason = AdbLauncher.REASON_NO_WLAN_IP,
                bindArg = AdbLauncher.BIND_LOOPBACK,
                connectHost = AdbLauncher.LOOPBACK_HOST
            )
        }
    }
}

private object ServerProcessIo {
    private const val INITIAL_PREAMBLE_CAPACITY = 8
    private const val MAX_PREAMBLE_LOG_LINES = 8
    private const val MAX_PREAMBLE_LINES = 64
    private const val LINE_BUF_INITIAL_BYTES = 128
    private const val MAX_LINE_BYTES = 4096

    fun buildCommand(remoteJarPath: String, bindArg: String): String =
        "CLASSPATH=$remoteJarPath app_process / ${AdbLauncher.SERVER_MAIN_CLASS} " +
            "--port 0 --bind $bindArg --secret-from-stdin"

    suspend fun openShellStream(
        adbClient: AdbClient,
        command: String,
        authenticator: HkdfAuthenticator,
        scrubber: AdbSecretScrubber
    ): AdbByteStream = try {
        adbClient.openStream("shell:$command")
    } catch (e: AdbClientException) {
        authenticator.close()
        throw SessionErrorException(e.failure.toSessionError(), e)
    } catch (e: IOException) {
        authenticator.close()
        throw SessionErrorException(
            SessionError.ServerStartFailed(scrubber.redact(e.message ?: "openStream failed")),
            e
        )
    }

    suspend fun writeSecretToStdin(
        stream: AdbByteStream,
        secretHex: String,
        ioDispatcher: CoroutineDispatcher
    ) {
        val secretBytes = "$secretHex\n".toByteArray(Charsets.UTF_8)
        try {
            withContext(ioDispatcher) {
                stream.outputStream.write(secretBytes)
                stream.outputStream.flush()
            }
        } finally {
            secretBytes.fill(0)
        }
    }

    suspend fun awaitReadyLine(
        stream: AdbByteStream,
        timeoutMs: Long,
        scrubber: AdbSecretScrubber,
        ioDispatcher: CoroutineDispatcher
    ): ReadyLine {
        stream.setReadTimeout(timeoutMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        val timedOut = AtomicBoolean(false)
        val preamble = ArrayList<String>(INITIAL_PREAMBLE_CAPACITY)
        return coroutineScope {
            val killerJob = launch {
                delay(timeoutMs)
                timedOut.set(true)
                closeQuietly(stream)
            }
            try {
                withContext(ioDispatcher) {
                    readUntilReady(stream.inputStream, preamble, timedOut, timeoutMs, scrubber)
                }
            } finally {
                killerJob.cancel()
            }
        }
    }

    fun readLineLf(input: InputStream): String? {
        val buf = ByteArrayOutputStream(LINE_BUF_INITIAL_BYTES)
        var byteVal = input.read()
        if (byteVal < 0) return null
        while (byteVal >= 0 && byteVal != '\n'.code) {
            if (buf.size() < MAX_LINE_BYTES) {
                buf.write(byteVal)
            }
            byteVal = input.read()
        }
        return buf.toString(Charsets.UTF_8.name()).trimEnd('\r')
    }

    private fun readUntilReady(
        input: InputStream,
        preamble: MutableList<String>,
        timedOut: AtomicBoolean,
        timeoutMs: Long,
        scrubber: AdbSecretScrubber
    ): ReadyLine {
        var found: ReadyLine? = null
        var linesRead = 0
        while (found == null) {
            val line = readLineLf(input)
                ?: throw buildMissingReadyException(timedOut.get(), timeoutMs, preamble, scrubber)
            val trimmed = line.trim()
            if (trimmed.isNotEmpty()) {
                if (ReadyLineParser.isReadyCandidate(trimmed)) {
                    found = parseReadyOrThrow(trimmed, scrubber)
                } else {
                    linesRead++
                    if (preamble.size < MAX_PREAMBLE_LOG_LINES) {
                        preamble += scrubber.redact(trimmed)
                    }
                    if (linesRead >= MAX_PREAMBLE_LINES) {
                        throw SessionErrorException(
                            SessionError.ServerStartFailed(
                                "Server emitted $linesRead lines without READY: " +
                                    preamble.joinToString(" | ")
                            )
                        )
                    }
                }
            }
        }
        return found
    }

    private fun parseReadyOrThrow(line: String, scrubber: AdbSecretScrubber): ReadyLine = try {
        ReadyLineParser.parse(line)
    } catch (e: IllegalArgumentException) {
        throw SessionErrorException(
            SessionError.ServerStartFailed(scrubber.redact(e.message ?: "Malformed READY line")),
            e
        )
    }

    private fun buildMissingReadyException(
        didTimeout: Boolean,
        timeoutMs: Long,
        preamble: List<String>,
        scrubber: AdbSecretScrubber
    ): SessionErrorException {
        val suffix = if (preamble.isEmpty()) "" else " (output: ${preamble.joinToString(" | ")})"
        val msg = if (didTimeout) {
            "Timed out after ${timeoutMs}ms waiting for server READY$suffix"
        } else {
            "Server exited before printing READY$suffix"
        }
        return SessionErrorException(SessionError.ServerStartFailed(scrubber.redact(msg)))
    }
}

private fun closeQuietly(closeable: Closeable?) {
    if (closeable == null) return
    try {
        closeable.close()
    } catch (_: IOException) {
    }
}

internal object IpLiteralCheck {
    private const val IPV4_OCTETS = 4
    private const val MAX_OCTET = 255
    private const val LOOPBACK_OCTET = 127
    private const val IPV6_GROUPS = 8
    private const val MAX_HEX_CHARS = 4

    fun isNonLoopbackIpLiteral(host: String): Boolean {
        val trimmed = host.trim()
        return if (trimmed.isEmpty()) {
            false
        } else if ('.' in trimmed && ':' !in trimmed) {
            isNonLoopbackIpv4(trimmed)
        } else if (':' in trimmed) {
            isNonLoopbackIpv6(trimmed)
        } else {
            false
        }
    }

    private fun isNonLoopbackIpv4(spec: String): Boolean {
        val parts = spec.split('.')
        val octets = if (parts.size == IPV4_OCTETS) {
            parts.mapNotNull { it.toIntOrNull()?.takeIf { v -> v in 0..MAX_OCTET } }
        } else {
            emptyList()
        }
        return octets.size == IPV4_OCTETS &&
            octets[0] != LOOPBACK_OCTET &&
            octets.any { it != 0 }
    }

    private fun isNonLoopbackIpv6(spec: String): Boolean {
        val unbracketed = spec.removePrefix("[").removeSuffix("]")
        val addrPart = unbracketed.substringBefore('%')
        val validShape = isValidIpv6Literal(addrPart)
        val normalized = addrPart.lowercase(Locale.ROOT)
        val isLoopback = normalized == "::1" || normalized == "0:0:0:0:0:0:0:1"
        val isUnspecified = normalized == "::" || normalized == "0:0:0:0:0:0:0:0"
        return validShape && !isLoopback && !isUnspecified
    }

    private fun isValidIpv6Literal(addr: String): Boolean {
        val firstDouble = addr.indexOf("::")
        if (firstDouble >= 0 && addr.indexOf("::", firstDouble + 2) >= 0) return false
        val hasCompression = firstDouble >= 0
        val head = if (hasCompression) addr.substring(0, firstDouble) else addr
        val tail = if (hasCompression) addr.substring(firstDouble + 2) else ""
        val headGroups = if (head.isEmpty()) emptyList() else head.split(':')
        val tailGroups = if (tail.isEmpty()) emptyList() else tail.split(':')
        val total = headGroups.size + tailGroups.size
        val validCount = if (hasCompression) total < IPV6_GROUPS else total == IPV6_GROUPS
        return validCount &&
            headGroups.all(::isHexGroup) &&
            tailGroups.all(::isHexGroup)
    }

    private fun isHexGroup(group: String): Boolean =
        group.length in 1..MAX_HEX_CHARS && group.all { ch ->
            ch in '0'..'9' || ch in 'a'..'f' || ch in 'A'..'F'
        }
}
