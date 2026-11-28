package com.mastersoft.velocast.launcher.adb

import com.mastersoft.velocast.client.DataChannelMode
import com.mastersoft.velocast.client.MirrorClient
import com.mastersoft.velocast.client.ReconnectConfig
import com.mastersoft.velocast.client.SessionConfig
import com.mastersoft.velocast.client.SessionEngine
import com.mastersoft.velocast.client.SessionError
import com.mastersoft.velocast.client.SessionLifecycleHooks
import com.mastersoft.velocast.client.SessionMetrics
import com.mastersoft.velocast.client.SessionState
import com.mastersoft.velocast.protocol.AuthMode
import com.mastersoft.velocast.protocol.ControlPacket
import com.mastersoft.velocast.protocol.ControlReader
import com.mastersoft.velocast.protocol.DeviceInfo
import com.mastersoft.velocast.protocol.DisplayGeometry
import com.mastersoft.velocast.protocol.ErrorCode
import com.mastersoft.velocast.protocol.Features
import com.mastersoft.velocast.protocol.FrameWriter
import com.mastersoft.velocast.protocol.HkdfAuthenticator
import com.mastersoft.velocast.protocol.LaunchMode
import com.mastersoft.velocast.protocol.MessageType
import com.mastersoft.velocast.protocol.Phase
import com.mastersoft.velocast.protocol.ProtocolV2
import com.mastersoft.velocast.protocol.ServerHandshake
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.PublicKey
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AdbUsbLifecycleAndLauncherTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val closeables = ArrayList<Closeable>()

    @After
    fun tearDown() {
        for (c in closeables.asReversed()) {
            runCatching { c.close() }
        }
        closeables.clear()
    }

    @Test
    fun `tracks attach detach broadcasts, non-ADB devices, and Viewer USB permission prompts`() =
        runBlocking {
            val trustedKeys = Collections.synchronizedSet(LinkedHashSet<PublicKey>())
            val usbBackend = FakeUsbHostBackend(trustedKeys).also { closeables += it }
            val env = createClientEnv(usbBackend)

            assertTrue(env.client.usbState.value is AdbUsbState.Disconnected)

            // 1. Phone plugged in with USB debugging OFF -> stays Disconnected(nonAdbUsbAttached=true).
            usbBackend.attachNonAdbDevice("/dev/bus/usb/001/009")
            val nonAdb = env.client.usbState.value as AdbUsbState.Disconnected
            assertTrue(nonAdb.nonAdbUsbAttached)
            usbBackend.detachDevice("/dev/bus/usb/001/009")

            // 2. Phone with ADB interface attached without Viewer UsbManager permission yet
            // (Android 10+ hides serialNumber until permission is granted).
            usbBackend.attachAdbDevice(
                deviceName = "/dev/bus/usb/001/002",
                deviceId = 2,
                serial = "6f8d1516",
                initialPermission = false,
                approvalPolicy = TargetAuthApprovalPolicy.AUTO_APPROVE
            )
            val detected = env.client.usbState.value as AdbUsbState.CableDetected
            assertFalse(detected.permissionGranted)
            assertNull(
                "Serial must be null before UsbManager permission is granted",
                detected.device.serialNumber
            )

            // 3. Denying Viewer USB permission surfaces AuthExpired while keeping CableDetected.
            usbBackend.permissionReply = false
            try {
                env.client.usb.connect()
                fail("Expected AdbFailure.AuthExpired when Viewer USB permission is denied")
            } catch (e: AdbClientException) {
                assertTrue(e.failure is AdbFailure.AuthExpired)
            }
            val afterDenial = env.client.usbState.value as AdbUsbState.CableDetected
            assertTrue(afterDenial.lastFailure is AdbFailure.AuthExpired)

            // 4. Granting Viewer USB permission transitions Authorising(VIEWER_USB_PERMISSION) -> Ready
            // and populates hardware serialNumber.
            val observedStates = CopyOnWriteArrayList<AdbUsbState>()
            val collector = launch {
                env.client.usbState.collect { observedStates += it }
            }
            usbBackend.permissionReply = null // Manual async approval so we observe Authorising.
            val connectJob = async { env.client.usb.connect() }
            withTimeout(2_000L) {
                env.client.usbState.first {
                    it is AdbUsbState.Authorising &&
                        it.stage == AdbUsbAuthStage.VIEWER_USB_PERMISSION
                }
            }
            usbBackend.deliverPermissionResult("/dev/bus/usb/001/002", granted = true)

            val connected = connectJob.await()
            assertEquals(AdbTransportKind.USB, connected.transportKind)
            assertEquals("6f8d1516", connected.usbSerialNumber)
            val ready = env.client.usbState.value as AdbUsbState.Ready
            assertEquals("6f8d1516", ready.device.serialNumber)
            assertTrue(
                observedStates.any {
                    it is AdbUsbState.Authorising &&
                        it.stage == AdbUsbAuthStage.VIEWER_USB_PERMISSION
                }
            )
            collector.cancelAndJoin()
        }

    @Test
    fun `handles target authorisation dialog Authorising to Ready and AUTH_EXPIRED on timeout`() =
        runBlocking {
            val trustedKeys = Collections.synchronizedSet(LinkedHashSet<PublicKey>())
            val usbBackend = FakeUsbHostBackend(trustedKeys).also { closeables += it }
            val env = createClientEnv(
                usbBackend = usbBackend,
                usbAuthPromptTimeoutMs = 200
            )

            // 1. Broken-screen phone cannot tap "Allow USB debugging?" -> Authorising -> AUTH_EXPIRED.
            usbBackend.attachAdbDevice(
                deviceName = "/dev/bus/usb/001/002",
                deviceId = 2,
                serial = "6f8d1516",
                initialPermission = true,
                approvalPolicy = TargetAuthApprovalPolicy.NEVER_APPROVE_TIMEOUT
            )
            val seenAuthorising = AtomicBoolean(false)
            val stateWatcher = launch {
                env.client.usbState.collect { st ->
                    if (st is AdbUsbState.Authorising &&
                        st.stage == AdbUsbAuthStage.DEVICE_ADB_APPROVAL
                    ) {
                        seenAuthorising.set(true)
                    }
                }
            }
            try {
                env.client.usb.connect()
                fail("Expected AUTH_EXPIRED when on-device approval times out")
            } catch (e: AdbClientException) {
                assertTrue(
                    "Expected AuthExpired, got ${e.failure}",
                    e.failure is AdbFailure.AuthExpired
                )
                assertEquals(ErrorCode.AUTH_EXPIRED, e.failure.errorCode)
                assertTrue(e.failure.toSessionError() is SessionError.AuthExpired)
            }
            assertTrue("Must emit Authorising(DEVICE_ADB_APPROVAL)", seenAuthorising.get())
            val retryableCableState = env.client.usbState.value as AdbUsbState.CableDetected
            assertTrue(retryableCableState.lastFailure is AdbFailure.AuthExpired)

            // 2. User approves with OTG mouse -> Authorising -> Ready, and subsequent reconnects
            // skip the prompt because the RSA-2048 key is now stored in trustedKeys!
            usbBackend.updateApprovalPolicy(
                "/dev/bus/usb/001/002",
                TargetAuthApprovalPolicy.APPROVE_AFTER_50MS
            )
            val firstConnect = env.client.usb.connect()
            assertEquals("M2101K6G", firstConnect.model)
            assertTrue(env.client.usbState.value is AdbUsbState.Ready)
            assertEquals("uid=2000(shell)\n", env.client.shell("id").stdout)
            env.client.disconnect()
            assertTrue(
                "Cable stays CableDetected after clean disconnect",
                env.client.usbState.value is AdbUsbState.CableDetected
            )

            // Reconnect with NEVER_APPROVE_TIMEOUT: succeeds immediately because signature matches!
            usbBackend.updateApprovalPolicy(
                "/dev/bus/usb/001/002",
                TargetAuthApprovalPolicy.NEVER_APPROVE_TIMEOUT
            )
            val secondConnect = env.client.usb.connect()
            assertEquals(AdbTransportKind.USB, secondConnect.transportKind)
            assertTrue(env.client.usbState.value is AdbUsbState.Ready)
            stateWatcher.cancelAndJoin()
        }

    @Test
    fun `AdbLauncher chooses USB automatically when Wi-Fi is unavailable and tunnels session`() =
        runBlocking {
            val jarFile = createSampleJar()
            val trustedKeys = Collections.synchronizedSet(LinkedHashSet<PublicKey>())
            val usbBackend = FakeUsbHostBackend(trustedKeys).also { closeables += it }
            val env = createClientEnv(usbBackend, discoveryTimeoutMs = 150L)
            trustedKeys.add(env.client.ensureKeyIdentity().publicKey)

            usbBackend.attachAdbDevice(
                deviceName = "/dev/bus/usb/001/002",
                deviceId = 2,
                serial = "6f8d1516",
                initialPermission = true,
                approvalPolicy = TargetAuthApprovalPolicy.NEVER_APPROVE_TIMEOUT
            )

            val metrics = SessionMetrics()
            val launcher = AdbLauncher(
                adbClient = env.client,
                jarSource = FileServerJarSource(jarFile),
                config = AdbLauncherConfig(
                    host = "127.0.0.1",
                    port = 1,
                    transportKind = AdbTransportKind.WIFI
                ),
                onDiagnostics = { cat, msg -> metrics.recordDiagnosticsEvent(cat, msg) }
            ).also { closeables += it }

            val harness = createEngineHarness(launcher, metrics)
            try {
                harness.engine.connect()
                val streaming = awaitStreamingOrFailed(harness.engine)
                assertUsbTunnelSessionActive(streaming, metrics, launcher)
            } finally {
                harness.shutdown(launcher)
            }
        }

    @Test
    fun `reconnects automatically over USB after old phone reboots onto a new USB bus address`() =
        runBlocking {
            val jarFile = createSampleJar()
            val trustedKeys = Collections.synchronizedSet(LinkedHashSet<PublicKey>())
            val usbBackend = FakeUsbHostBackend(trustedKeys).also { closeables += it }
            val env = createClientEnv(usbBackend, discoveryTimeoutMs = 100L)
            trustedKeys.add(env.client.ensureKeyIdentity().publicKey)

            usbBackend.attachAdbDevice(
                deviceName = "/dev/bus/usb/001/002",
                deviceId = 2,
                serial = "6f8d1516",
                initialPermission = true,
                approvalPolicy = TargetAuthApprovalPolicy.NEVER_APPROVE_TIMEOUT
            )

            val launcher = AdbLauncher(
                adbClient = env.client,
                jarSource = FileServerJarSource(jarFile),
                config = AdbLauncherConfig(transportKind = AdbTransportKind.USB)
            ).also { closeables += it }

            val harness = createFastReconnectHarness(launcher)
            val triggerHandle = launcher.bindUsbReconnectTrigger(harness.engine).also {
                closeables += it
            }
            try {
                val firstPort = connectInitialUsbSession(harness.engine, launcher)
                simulateRebootAndVerifyReconnect(usbBackend, harness.engine, launcher, firstPort)
                triggerHandle.close()
            } finally {
                harness.shutdown(launcher)
            }
        }

    private suspend fun connectInitialUsbSession(
        engine: SessionEngine,
        launcher: AdbLauncher
    ): Int {
        engine.connect()
        val firstStream = withTimeout(5_000L) {
            engine.state.first { it is SessionState.Streaming && it.deviceInfo != null }
        } as SessionState.Streaming
        assertTrue(launcher.usbState.value is AdbUsbState.Ready)
        return firstStream.endpoint.port
    }

    private suspend fun simulateRebootAndVerifyReconnect(
        usbBackend: FakeUsbHostBackend,
        engine: SessionEngine,
        launcher: AdbLauncher,
        firstPort: Int
    ) {
        usbBackend.detachDevice("/dev/bus/usb/001/002")
        val disconnectedUsb = launcher.usbState.value as AdbUsbState.Disconnected
        assertTrue(disconnectedUsb.awaitingRebootReconnect)

        withTimeout(3_000L) {
            engine.state.first { it is SessionState.Reconnecting }
        }
        usbBackend.attachNonAdbDevice("/dev/bus/usb/001/003")
        delay(250L)
        assertFalse(
            "Engine must keep reconnecting while old phone boots, got ${engine.state.value}",
            engine.state.value is SessionState.Failed
        )
        usbBackend.detachDevice("/dev/bus/usb/001/003")

        usbBackend.permissionReply = true
        usbBackend.attachAdbDevice(
            deviceName = "/dev/bus/usb/001/004",
            deviceId = 4,
            serial = "6f8d1516",
            initialPermission = false,
            approvalPolicy = TargetAuthApprovalPolicy.NEVER_APPROVE_TIMEOUT
        )

        val recovered = withTimeout(5_000L) {
            engine.state.first {
                (
                    it is SessionState.Streaming && it.endpoint.port != firstPort &&
                        it.deviceInfo != null
                    ) ||
                    it is SessionState.Failed
            }
        }
        assertTrue(
            "Expected recovered Streaming after reboot, got $recovered",
            recovered is SessionState.Streaming
        )
        val readyAfterReboot = launcher.usbState.value as AdbUsbState.Ready
        assertEquals("/dev/bus/usb/001/004", readyAfterReboot.device.deviceName)
        assertEquals("6f8d1516", readyAfterReboot.device.serialNumber)
    }

    private suspend fun awaitStreamingOrFailed(engine: SessionEngine): SessionState =
        withTimeout(5_000L) {
            engine.state.first {
                (it is SessionState.Streaming && it.deviceInfo != null) ||
                    it is SessionState.Failed
            }
        }

    private fun assertUsbTunnelSessionActive(
        streaming: SessionState,
        metrics: SessionMetrics,
        launcher: AdbLauncher
    ) {
        assertTrue(
            "Expected Streaming over USB, got $streaming",
            streaming is SessionState.Streaming
        )
        val active = streaming as SessionState.Streaming
        assertEquals(DataChannelMode.ADB_TUNNEL, active.endpoint.dataChannelMode)
        assertEquals(AdbLauncher.REASON_USB_TRANSPORT, active.endpoint.dataChannelReason)
        assertEquals("M2101K6G", active.deviceInfo?.model)
        assertEquals(DataChannelMode.ADB_TUNNEL, metrics.state.value.dataChannelMode)
        assertTrue(launcher.usbState.value is AdbUsbState.Ready)
    }

    private fun createEngineHarness(
        launcher: AdbLauncher,
        metrics: SessionMetrics
    ): EngineTestHarness {
        val scopeJob = SupervisorJob()
        val scope = CoroutineScope(Dispatchers.IO + scopeJob)
        val engine = SessionEngine(
            serverLauncher = launcher,
            transport = MirrorClient(ioDispatcher = Dispatchers.IO),
            parentScope = scope,
            config = SessionConfig(handshakeTimeoutMs = 3_000L, socketReadTimeoutMs = 3_000L),
            lifecycleHooks = object : SessionLifecycleHooks {
                override fun onDataChannelSelected(mode: DataChannelMode, reason: String?) {
                    metrics.recordDataChannel(mode, reason)
                }
            }
        )
        return EngineTestHarness(engine, scopeJob)
    }

    private fun createFastReconnectHarness(launcher: AdbLauncher): EngineTestHarness {
        val scopeJob = SupervisorJob()
        val scope = CoroutineScope(Dispatchers.IO + scopeJob)
        val engine = SessionEngine(
            serverLauncher = launcher,
            transport = MirrorClient(ioDispatcher = Dispatchers.IO),
            parentScope = scope,
            config = SessionConfig(
                heartbeatIntervalMs = 200L,
                socketReadTimeoutMs = 600L,
                silenceTimeoutMs = 800L,
                handshakeTimeoutMs = 3_000L,
                reconnect = ReconnectConfig(
                    initialDelayMs = 150L,
                    maxDelayMs = 500L,
                    maxDurationMs = 30_000L
                )
            )
        )
        return EngineTestHarness(engine, scopeJob)
    }

    private fun createClientEnv(
        usbBackend: FakeUsbHostBackend,
        discoveryTimeoutMs: Long = 500L,
        usbAuthPromptTimeoutMs: Int = 1_000
    ): UsbTestClientEnv {
        val nsdBackend = FakeNsdBackend()
        val errorMapper = AdbErrorMapper { LocalNetworkSnapshot(isLanAvailable = true) }
        val discovery = AdbMdnsDiscovery(nsdBackend, errorMapper)
        val client = InHouseAdbClient(
            keyStore = InMemoryAdbKeyStore(),
            discovery = discovery,
            errorMapper = errorMapper,
            config = AdbClientConfig(
                connectTimeoutMs = 300,
                handshakeReadTimeoutMs = 1_000,
                discoveryTimeoutMs = discoveryTimeoutMs,
                portRefreshDiscoveryTimeoutMs = 150L,
                usbPermissionTimeoutMs = 2_000L,
                usbAuthPromptTimeoutMs = usbAuthPromptTimeoutMs
            ),
            usbBackend = usbBackend
        ).also { closeables += it }
        return UsbTestClientEnv(client)
    }

    private fun createSampleJar(): File =
        tempFolder.newFile("velocast-server-${System.nanoTime()}.jar").apply {
            writeBytes(ByteArray(4096) { idx -> (idx * 17 + 3).toByte() })
        }

    private class UsbTestClientEnv(val client: InHouseAdbClient)

    private class EngineTestHarness(
        val engine: SessionEngine,
        private val scopeJob: kotlinx.coroutines.CompletableJob
    ) {
        suspend fun shutdown(launcher: AdbLauncher) {
            withContext(NonCancellable) {
                engine.disconnect()
                engine.close()
                launcher.close()
                scopeJob.cancelAndJoin()
            }
        }
    }
}

internal enum class TargetAuthApprovalPolicy {
    AUTO_APPROVE,
    APPROVE_AFTER_50MS,
    NEVER_APPROVE_TIMEOUT
}

private class FakeUsbHostBackend(private val trustedClientKeys: MutableSet<PublicKey>) :
    UsbHostBackend {
    private val listeners = CopyOnWriteArrayList<UsbHostEventListener>()
    private val devices = ConcurrentHashMap<String, SimulatedUsbTarget>()
    private val openConnections = CopyOnWriteArrayList<Closeable>()
    private val nextServerPort = AtomicInteger(42_000)

    @Volatile
    var permissionReply: Boolean? = true

    fun attachNonAdbDevice(deviceName: String) {
        val desc = AdbUsbDeviceDescriptor(
            deviceName = deviceName,
            deviceId = deviceName.hashCode(),
            vendorId = 0x18D1,
            productId = 0x4EE1,
            hasAdbInterface = false,
            hasUsbPermission = false
        )
        val target =
            SimulatedUsbTarget(
                desc,
                serial = null,
                hasPermission = false,
                policy = TargetAuthApprovalPolicy.AUTO_APPROVE
            )
        devices[deviceName] = target
        for (l in listeners) l.onDeviceAttached(target.currentDescriptor())
    }

    fun attachAdbDevice(
        deviceName: String,
        deviceId: Int,
        serial: String,
        initialPermission: Boolean,
        approvalPolicy: TargetAuthApprovalPolicy
    ) {
        val base = AdbUsbDeviceDescriptor(
            deviceName = deviceName,
            deviceId = deviceId,
            vendorId = 0x18D1,
            productId = 0x4EE7,
            serialNumber = serial,
            productName = "sweet",
            manufacturerName = "Xiaomi",
            hasAdbInterface = true,
            hasUsbPermission = initialPermission
        )
        val target =
            SimulatedUsbTarget(
                base,
                serial = serial,
                hasPermission = initialPermission,
                policy = approvalPolicy
            )
        devices[deviceName] = target
        for (l in listeners) l.onDeviceAttached(target.currentDescriptor())
    }

    fun updateApprovalPolicy(deviceName: String, policy: TargetAuthApprovalPolicy) {
        devices[deviceName]?.policy = policy
    }

    fun detachDevice(deviceName: String) {
        val removed = devices.remove(deviceName)
        removed?.closeAllConnections()
        for (l in listeners) l.onDeviceDetached(deviceName)
    }

    fun deliverPermissionResult(deviceName: String, granted: Boolean) {
        if (granted) {
            devices[deviceName]?.hasPermission = true
        }
        for (l in listeners) l.onPermissionResult(deviceName, granted)
    }

    override fun listDevices(): List<AdbUsbDeviceDescriptor> =
        devices.values.map { it.currentDescriptor() }

    override fun hasPermission(deviceName: String): Boolean =
        devices[deviceName]?.hasPermission == true

    override fun requestPermission(deviceName: String) {
        val autoReply = permissionReply ?: return
        deliverPermissionResult(deviceName, autoReply)
    }

    override fun openAdbConnection(deviceName: String): UsbBulkConnection {
        val target = devices[deviceName]
            ?: throw EOFException("USB device '$deviceName' is not attached")
        if (!target.hasPermission) {
            throw IOException("No USB permission for '$deviceName'")
        }
        val daemon = FakeUsbAdbdDaemon(
            descriptor = target.currentDescriptor(),
            trustedClientKeys = trustedClientKeys,
            policyProvider = { target.policy },
            pushedFiles = target.pushedFiles,
            portSupplier = { nextServerPort.incrementAndGet() }
        )
        target.activeConnections += daemon
        openConnections += daemon
        daemon.start()
        return daemon
    }

    override fun observeEvents(listener: UsbHostEventListener): Closeable {
        listeners += listener
        return Closeable { listeners.remove(listener) }
    }

    override fun close() {
        for (c in openConnections) runCatching { c.close() }
        openConnections.clear()
        devices.clear()
        listeners.clear()
    }

    private class SimulatedUsbTarget(
        private val template: AdbUsbDeviceDescriptor,
        private val serial: String?,
        @Volatile var hasPermission: Boolean,
        @Volatile var policy: TargetAuthApprovalPolicy
    ) {
        val pushedFiles = ConcurrentHashMap<String, ByteArray>()
        val activeConnections = CopyOnWriteArrayList<Closeable>()

        fun currentDescriptor(): AdbUsbDeviceDescriptor = template.copy(
            serialNumber = if (hasPermission) serial else null,
            hasUsbPermission = hasPermission
        )

        fun closeAllConnections() {
            for (c in activeConnections) runCatching { c.close() }
            activeConnections.clear()
        }
    }
}

private class FakeUsbAdbdDaemon(
    override val descriptor: AdbUsbDeviceDescriptor,
    private val trustedClientKeys: MutableSet<PublicKey>,
    private val policyProvider: () -> TargetAuthApprovalPolicy,
    private val pushedFiles: MutableMap<String, ByteArray>,
    private val portSupplier: () -> Int
) : UsbBulkConnection {
    private val closed = AtomicBoolean(false)
    private val hostToDevice = LinkedBlockingQueue<ByteArray>()
    private val deviceToHost = LinkedBlockingQueue<ByteArray>()
    private val executor = Executors.newCachedThreadPool()
    private val authToken = ByteArray(20) { idx -> (idx * 11 + 7).toByte() }

    @Volatile
    private var lastPipedSecretHex: String? = null

    override val isClosed: Boolean
        get() = closed.get()

    fun start() {
        executor.execute { runDaemonLoop() }
    }

    override fun bulkIn(buffer: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int {
        ensureDaemonOpen()
        val chunk = deviceToHost.poll(timeoutMs.coerceAtLeast(1).toLong(), TimeUnit.MILLISECONDS)
        if (chunk == null) {
            ensureDaemonOpen()
            throw SocketTimeoutException("Fake USB bulk IN timeout (${timeoutMs}ms)")
        }
        val toCopy = minOf(length, chunk.size)
        chunk.copyInto(buffer, offset, 0, toCopy)
        return toCopy
    }

    override fun bulkOut(buffer: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int {
        ensureDaemonOpen()
        hostToDevice.offer(buffer.copyOfRange(offset, offset + length))
        return length
    }

    private fun ensureDaemonOpen() {
        if (closed.get()) throw EOFException("Fake USB connection closed")
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            executor.shutdownNow()
        }
    }

    private fun runDaemonLoop() {
        try {
            if (authenticateUsbClient()) {
                val banner = (
                    "device::ro.product.name=sweet;ro.product.model=M2101K6G;" +
                        "ro.product.device=sweet;features=shell_v2,cmd,stat_v2\u0000"
                    ).toByteArray(Charsets.UTF_8)
                sendDevicePacket(
                    AdbPacket(
                        AdbPacket.A_CNXN,
                        AdbPacket.A_VERSION_SKIP_CHECKSUM,
                        AdbPacket.DEFAULT_MAX_DATA,
                        banner
                    ),
                    skipChecksum = true
                )
                serveMultiplexedStreams()
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (_: IOException) {
        }
    }

    private fun authenticateUsbClient(): Boolean {
        val cnxn = readHostPacket()
        val hasCnxn = cnxn != null && cnxn.command == AdbPacket.A_CNXN
        if (hasCnxn) {
            sendDevicePacket(
                AdbPacket(AdbPacket.A_AUTH, AdbPacket.ADB_AUTH_TOKEN, 0, authToken),
                skipChecksum = false
            )
        }
        val sigPkt = if (hasCnxn) readHostPacket() else null
        return when {
            sigPkt == null -> false

            sigPkt.command == AdbPacket.A_AUTH &&
                sigPkt.arg0 == AdbPacket.ADB_AUTH_SIGNATURE &&
                verifyTokenSignature(sigPkt.payload) -> true

            else -> requestOnDeviceApproval()
        }
    }

    private fun verifyTokenSignature(signature: ByteArray): Boolean {
        val snapshot = synchronized(trustedClientKeys) { trustedClientKeys.toList() }
        for (key in snapshot) {
            val rsa = key as? RSAPublicKey ?: continue
            val ok = runCatching {
                val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
                cipher.init(Cipher.DECRYPT_MODE, rsa)
                val decrypted = cipher.doFinal(signature)
                decrypted.size >= 20 &&
                    decrypted.copyOfRange(decrypted.size - 20, decrypted.size)
                        .contentEquals(authToken)
            }.getOrDefault(false)
            if (ok) return true
        }
        return false
    }

    private fun requestOnDeviceApproval(): Boolean {
        val secondToken = ByteArray(20) { idx -> (idx + 0x33).toByte() }
        sendDevicePacket(
            AdbPacket(AdbPacket.A_AUTH, AdbPacket.ADB_AUTH_TOKEN, 0, secondToken),
            skipChecksum = false
        )
        val pubKeyPkt = readHostPacket()
        val validPubKey = pubKeyPkt != null &&
            pubKeyPkt.command == AdbPacket.A_AUTH &&
            pubKeyPkt.arg0 == AdbPacket.ADB_AUTH_RSAPUBLICKEY
        if (!validPubKey) return false

        val text = String(pubKeyPkt.payload, Charsets.UTF_8).trimEnd('\u0000')
        val base64Part = text.substringBefore(' ')
        val structBytes = Base64.getDecoder().decode(base64Part)
        val decodedKey = AndroidPubkeyCodec.decodeBinaryStruct(structBytes)
        return when (policyProvider()) {
            TargetAuthApprovalPolicy.AUTO_APPROVE -> {
                trustedClientKeys.add(decodedKey)
                true
            }

            TargetAuthApprovalPolicy.APPROVE_AFTER_50MS -> {
                Thread.sleep(50L)
                trustedClientKeys.add(decodedKey)
                true
            }

            TargetAuthApprovalPolicy.NEVER_APPROVE_TIMEOUT -> {
                while (!closed.get()) {
                    Thread.sleep(50L)
                }
                false
            }
        }
    }

    private fun serveMultiplexedStreams() {
        var nextRemoteId = 200
        val syncStreams = HashMap<Int, UsbSyncState>()
        val appProcessStreams = HashSet<Int>()
        val tunnelStreams = HashMap<Int, PipedOutputStream>()

        while (!closed.get()) {
            val pkt = readHostPacket() ?: break
            when (pkt.command) {
                AdbPacket.A_OPEN -> {
                    val remoteId = ++nextRemoteId
                    val localId = pkt.arg0
                    val service = String(
                        pkt.payload,
                        0,
                        (pkt.payload.size - 1).coerceAtLeast(0),
                        Charsets.UTF_8
                    )
                    sendDevicePacket(AdbPacket(AdbPacket.A_OKAY, remoteId, localId), true)
                    when {
                        service.startsWith("shell,v2,raw:") ->
                            handleShellV2(service.removePrefix("shell,v2,raw:"), remoteId, localId)

                        service == "sync:" -> syncStreams[remoteId] = UsbSyncState(localId)

                        service.startsWith("shell:CLASSPATH=") -> appProcessStreams.add(remoteId)

                        service.startsWith("tcp:") ->
                            tunnelStreams[remoteId] =
                                startProtocolV2ServerOverAdbStream(remoteId, localId)
                    }
                }

                AdbPacket.A_WRTE -> {
                    val localId = pkt.arg0
                    val remoteId = pkt.arg1
                    sendDevicePacket(AdbPacket(AdbPacket.A_OKAY, remoteId, localId), true)
                    when {
                        remoteId in appProcessStreams -> {
                            val secretHex = String(pkt.payload, Charsets.UTF_8).trim()
                            lastPipedSecretHex = secretHex
                            val port = portSupplier()
                            val ready = "READY port=$port nonce=aabbccddeeff0011\n"
                                .toByteArray(Charsets.UTF_8)
                            sendDevicePacket(
                                AdbPacket(AdbPacket.A_WRTE, remoteId, localId, ready),
                                true
                            )
                        }

                        remoteId in syncStreams ->
                            syncStreams[remoteId]?.consume(pkt.payload, remoteId)

                        remoteId in tunnelStreams -> {
                            tunnelStreams[remoteId]?.write(pkt.payload)
                            tunnelStreams[remoteId]?.flush()
                        }
                    }
                }

                AdbPacket.A_CLSE -> {
                    syncStreams.remove(pkt.arg1)
                    appProcessStreams.remove(pkt.arg1)
                    tunnelStreams.remove(pkt.arg1)?.close()
                }
            }
        }
    }

    private fun handleShellV2(cmd: String, remoteId: Int, localId: Int) {
        val (stdoutText, exitCode) = when {
            cmd == "id" -> "uid=2000(shell)\n" to 0

            cmd.startsWith("sha256sum ") -> {
                val path = cmd.removePrefix("sha256sum ").trim()
                val bytes = pushedFiles[path]
                if (bytes == null) "" to 1 else "${Sha256.hashBytesHex(bytes)}  $path\n" to 0
            }

            else -> "ok:$cmd\n" to 0
        }
        val outBytes = stdoutText.toByteArray(Charsets.UTF_8)
        val v2 = ByteBuffer.allocate(5 + outBytes.size + 6)
            .order(ByteOrder.LITTLE_ENDIAN)
            .put(1.toByte())
            .putInt(outBytes.size)
            .put(outBytes)
            .put(3.toByte())
            .putInt(1)
            .put(exitCode.toByte())
            .array()
        sendDevicePacket(AdbPacket(AdbPacket.A_WRTE, remoteId, localId, v2), true)
        sendDevicePacket(AdbPacket(AdbPacket.A_CLSE, remoteId, localId), true)
    }

    private fun startProtocolV2ServerOverAdbStream(remoteId: Int, localId: Int): PipedOutputStream {
        val secretHex = checkNotNull(lastPipedSecretHex) { "Secret must be piped before tcp open" }
        val secret = ByteArray(ProtocolV2.SECRET_SIZE) { i ->
            secretHex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        val clientIn = PipedInputStream(64 * 1024)
        val clientOut = PipedOutputStream(clientIn)
        val adbOutStream = object : java.io.OutputStream() {
            override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
            override fun write(b: ByteArray, off: Int, len: Int) {
                if (len > 0 && !closed.get()) {
                    sendDevicePacket(
                        AdbPacket(
                            AdbPacket.A_WRTE,
                            remoteId,
                            localId,
                            b.copyOfRange(off, off + len)
                        ),
                        skipChecksum = true
                    )
                }
            }
        }
        executor.execute {
            val auth = HkdfAuthenticator(secret, AuthMode.SESSION_SECRET)
            try {
                val nonce = ByteArray(ProtocolV2.NONCE_SIZE) { idx -> (idx + 0x40).toByte() }
                val hs = ServerHandshake(auth, Features.SECURE_CHANNEL, nonce)
                val reader = ControlReader(clientIn)
                val writer = FrameWriter(adbOutStream)
                val pkt = ControlPacket()

                if (!reader.read(pkt) || pkt.type != MessageType.HELLO) return@execute
                writer.writeMessage(hs.onHello(reader.payload, 0, pkt.payloadLength))

                if (!reader.read(pkt) || pkt.type != MessageType.AUTH_PROOF) return@execute
                writer.writeMessage(hs.onClientProof(reader.payload, 0, pkt.payloadLength))

                val keys = hs.deriveSessionKeys()!!
                writer.cipher = keys.newServerOutboundCipher()
                reader.cipher = keys.newServerInboundCipher()
                keys.close()
                writer.phase = Phase.SESSION
                reader.phase = Phase.SESSION

                writer.writeMessage(
                    DeviceInfo(
                        sdkInt = 36,
                        launchMode = LaunchMode.SHELL,
                        display = DisplayGeometry(1080, 2400, 0),
                        manufacturer = "Xiaomi",
                        model = "M2101K6G"
                    )
                )
                while (!closed.get() && reader.read(pkt)) {
                    if (pkt.type == MessageType.PING) {
                        writer.writeMessage(
                            com.mastersoft.velocast.protocol.Pong(
                                token = pkt.param,
                                serverUs = (System.nanoTime() / 1_000L).coerceAtLeast(0L)
                            )
                        )
                    } else if (pkt.type == MessageType.BYE) {
                        break
                    }
                }
            } catch (_: IOException) {
            } finally {
                auth.close()
            }
        }
        return clientOut
    }

    private inner class UsbSyncState(private val localId: Int) {
        private val raw = ByteArrayOutputStream()
        private var path: String? = null
        private val content = ByteArrayOutputStream()

        fun consume(chunk: ByteArray, remoteId: Int) {
            raw.write(chunk)
            val all = raw.toByteArray()
            var cursor = 0
            var nextCursor = tryConsumeSyncFrame(all, cursor, remoteId)
            while (nextCursor != null) {
                cursor = nextCursor
                nextCursor = tryConsumeSyncFrame(all, cursor, remoteId)
            }
            val rem = all.copyOfRange(cursor, all.size)
            raw.reset()
            raw.write(rem)
        }

        private fun tryConsumeSyncFrame(all: ByteArray, cursor: Int, remoteId: Int): Int? {
            if (cursor + 8 > all.size) return null
            val id = String(all, cursor, 4, Charsets.US_ASCII)
            val len = ByteBuffer.wrap(all, cursor + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
            return when (id) {
                "SEND" -> if (cursor + 8 + len <= all.size) {
                    path = String(all, cursor + 8, len, Charsets.UTF_8).substringBeforeLast(',')
                    content.reset()
                    cursor + 8 + len
                } else {
                    null
                }

                "DATA" -> if (cursor + 8 + len <= all.size) {
                    content.write(all, cursor + 8, len)
                    cursor + 8 + len
                } else {
                    null
                }

                "DONE" -> {
                    path?.let { pushedFiles[it] = content.toByteArray() }
                    val okay = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
                        .put("OKAY".toByteArray(Charsets.US_ASCII)).putInt(0).array()
                    sendDevicePacket(AdbPacket(AdbPacket.A_WRTE, remoteId, localId, okay), true)
                    cursor + 8
                }

                "QUIT" -> {
                    sendDevicePacket(AdbPacket(AdbPacket.A_CLSE, remoteId, localId), true)
                    cursor + 8
                }

                else -> null
            }
        }
    }

    private fun readHostPacket(): AdbPacket? {
        val headerBytes = pollNextNonEmptyChunk() ?: return null
        val hdr = AdbPacket.decodeHeader(headerBytes)
        val payload = if (hdr.payloadLength == 0) {
            AdbPacket.EMPTY_BYTES
        } else {
            readHostPayload(hdr.payloadLength)
        }
        return payload?.let { AdbPacket(hdr.command, hdr.arg0, hdr.arg1, it) }
    }

    private fun pollNextNonEmptyChunk(): ByteArray? {
        var found: ByteArray? = null
        while (!closed.get() && found == null) {
            val next = hostToDevice.poll(100L, TimeUnit.MILLISECONDS)
            if (next != null && next.isNotEmpty()) {
                found = next
            }
        }
        return found
    }

    private fun readHostPayload(payloadLength: Int): ByteArray? {
        val out = ByteArrayOutputStream(payloadLength)
        var aborted = false
        while (out.size() < payloadLength && !closed.get() && !aborted) {
            val part = hostToDevice.poll(1_000L, TimeUnit.MILLISECONDS)
            if (part == null) {
                aborted = true
            } else if (part.isNotEmpty()) {
                out.write(part)
            }
        }
        if (out.size() < payloadLength) return null
        if (payloadLength % descriptor.outMaxPacketSize == 0) {
            val peeked = hostToDevice.peek()
            if (peeked != null && peeked.isEmpty()) {
                hostToDevice.poll()
            }
        }
        return out.toByteArray()
    }

    private fun sendDevicePacket(packet: AdbPacket, skipChecksum: Boolean) {
        deviceToHost.offer(packet.encodeHeader(skipChecksum))
        if (packet.payload.isNotEmpty()) {
            var offset = 0
            while (offset < packet.payload.size) {
                val len = minOf(
                    AdbUsbConstants.DEFAULT_MAX_BULK_TRANSFER_BYTES,
                    packet.payload.size - offset
                )
                deviceToHost.offer(packet.payload.copyOfRange(offset, offset + len))
                offset += len
            }
            if (packet.payload.size % descriptor.inMaxPacketSize == 0) {
                deviceToHost.offer(ByteArray(0))
            }
        }
    }
}
