package com.mastersoft.velocast.launcher.adb

import java.io.Closeable
import java.io.EOFException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Coordinates USB OTG discovery, Viewer `UsbManager` permission prompts, attach/detach broadcasts,
 * target `adbd` approval (`Authorising` / `AUTH_EXPIRED`), and reboot re-enumeration (`S07C` #2, #4).
 */
internal class AdbUsbController(
    private val backend: UsbHostBackend,
    private val onActiveDeviceDetached: (deviceName: String) -> Unit = {}
) : Closeable {
    internal val registry = UsbDeviceStateRegistry(backend)
    private val permissionWaiters = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    private val reattachListeners = CopyOnWriteArrayList<(AdbUsbDeviceDescriptor) -> Unit>()

    val state: StateFlow<AdbUsbState> = registry.state

    private val backendSubscription: Closeable = backend.observeEvents(
        object : UsbHostEventListener {
            override fun onDeviceAttached(device: AdbUsbDeviceDescriptor) {
                if (registry.onAttached(device)) {
                    for (listener in reattachListeners) {
                        listener(device)
                    }
                }
            }

            override fun onDeviceDetached(deviceName: String) {
                val wasActive = registry.onDetached(deviceName)
                permissionWaiters.remove(deviceName)?.complete(false)
                if (wasActive) {
                    onActiveDeviceDetached(deviceName)
                }
            }

            override fun onPermissionResult(deviceName: String, granted: Boolean) {
                if (granted) {
                    registry.refreshSingle(deviceName)
                }
                permissionWaiters.remove(deviceName)?.complete(granted)
            }
        }
    )

    init {
        registry.refreshAll()
    }

    fun attachedDevices(): List<AdbUsbDeviceDescriptor> = registry.attachedDevices()

    fun observeReattach(listener: (AdbUsbDeviceDescriptor) -> Unit): Closeable {
        reattachListeners += listener
        return Closeable { reattachListeners.remove(listener) }
    }

    suspend fun ensureViewerPermission(
        target: AdbUsbDeviceDescriptor,
        timeoutMs: Long
    ): AdbUsbDeviceDescriptor {
        if (backend.hasPermission(target.deviceName)) {
            return registry.refreshSingle(target.deviceName)
                ?: target.copy(hasUsbPermission = true)
        }
        val deferred = CompletableDeferred<Boolean>()
        val existing = permissionWaiters.putIfAbsent(target.deviceName, deferred)
        val waiter = existing ?: deferred
        if (existing == null) {
            registry.onAuthorising(target, AdbUsbAuthStage.VIEWER_USB_PERMISSION)
            backend.requestPermission(target.deviceName)
        }
        val granted = try {
            withTimeoutOrNull(timeoutMs) { waiter.await() }
        } finally {
            permissionWaiters.remove(target.deviceName, waiter)
        }
        return resolvePermissionOutcome(target.deviceName, granted)
    }

    @Throws(AdbClientException::class)
    fun selectTargetAdbDevice(serialOrDeviceName: String?): AdbUsbDeviceDescriptor {
        val currentDevices = registry.attachedDevices()
        val adbCandidates = currentDevices.filter { it.hasAdbInterface }
        if (adbCandidates.isEmpty()) {
            val detail = if (currentDevices.isNotEmpty()) {
                "USB device is attached without an ADB interface (USB debugging is off)"
            } else {
                "No ADB-capable USB OTG cable is attached"
            }
            throw AdbClientException(AdbFailure.DebuggingOff(detail))
        }
        return adbCandidates.firstOrNull { it.matchesTarget(serialOrDeviceName) }
            ?: throw AdbClientException(
                AdbFailure.DebuggingOff(
                    "Attached USB device does not match requested target '$serialOrDeviceName'"
                )
            )
    }

    fun openBulkConnection(device: AdbUsbDeviceDescriptor): UsbBulkConnection =
        backend.openAdbConnection(device.deviceName)

    override fun close() {
        backendSubscription.close()
        for ((_, waiter) in permissionWaiters) {
            waiter.complete(false)
        }
        permissionWaiters.clear()
        reattachListeners.clear()
        backend.close()
    }

    private fun resolvePermissionOutcome(
        deviceName: String,
        granted: Boolean?
    ): AdbUsbDeviceDescriptor {
        val updated = registry.refreshSingle(deviceName)
            ?: throw AdbClientException(
                AdbFailure.TransportError("USB cable detached while awaiting permission")
            )
        return if (granted == true || updated.hasUsbPermission) {
            registry.markPermissionGranted(updated)
        } else {
            val reason = if (granted == null) {
                "Timed out waiting for USB permission on the viewer"
            } else {
                "USB permission was denied on the viewer"
            }
            val failure = AdbFailure.AuthExpired(reason)
            registry.onSessionTransition(updated, connected = null, failure = failure)
            throw AdbClientException(failure)
        }
    }
}

internal class UsbDeviceStateRegistry(private val backend: UsbHostBackend) {
    private val lock = Any()
    private val attachedDevices = LinkedHashMap<String, AdbUsbDeviceDescriptor>()
    private var lastReadyDescriptor: AdbUsbDeviceDescriptor? = null
    private var activeConnectedDeviceName: String? = null
    private var awaitingRebootReconnect: Boolean = false

    private val _state = MutableStateFlow<AdbUsbState>(AdbUsbState.Disconnected())
    val state: StateFlow<AdbUsbState> = _state.asStateFlow()

    fun attachedDevices(): List<AdbUsbDeviceDescriptor> {
        refreshAll()
        return synchronized(lock) { attachedDevices.values.toList() }
    }

    fun refreshAll() {
        val freshList = backend.listDevices()
        synchronized(lock) {
            attachedDevices.clear()
            for (desc in freshList) {
                attachedDevices[desc.deviceName] = desc
            }
            val current = _state.value
            val preserveActiveState = current is AdbUsbState.Authorising ||
                (current is AdbUsbState.Ready && current.device.deviceName in attachedDevices)
            if (!preserveActiveState) {
                val prevFailure = when (current) {
                    is AdbUsbState.CableDetected -> current.lastFailure
                    is AdbUsbState.Disconnected -> current.lastFailure
                    else -> null
                }
                syncIdleUnlocked(prevFailure)
            }
        }
    }

    fun refreshSingle(deviceName: String): AdbUsbDeviceDescriptor? = synchronized(lock) {
        val fresh = backend.listDevices().firstOrNull { it.deviceName == deviceName }
        if (fresh != null) {
            attachedDevices[deviceName] = fresh
        } else {
            attachedDevices.remove(deviceName)
        }
        fresh
    }

    fun markPermissionGranted(device: AdbUsbDeviceDescriptor): AdbUsbDeviceDescriptor =
        synchronized(lock) {
            val permitted = device.copy(hasUsbPermission = true)
            attachedDevices[permitted.deviceName] = permitted
            _state.value = AdbUsbState.CableDetected(permitted, permissionGranted = true)
            permitted
        }

    fun onAttached(device: AdbUsbDeviceDescriptor): Boolean = synchronized(lock) {
        attachedDevices[device.deviceName] = device
        var isReattach = false
        if (device.hasAdbInterface) {
            val previous = lastReadyDescriptor
            isReattach = awaitingRebootReconnect &&
                (previous == null || previous.matchesAfterReboot(device))
            _state.value = AdbUsbState.CableDetected(
                device = device,
                permissionGranted = device.hasUsbPermission,
                reconnectedAfterDetach = isReattach
            )
        } else if (attachedDevices.values.none { it.hasAdbInterface }) {
            _state.value = AdbUsbState.Disconnected(
                nonAdbUsbAttached = true,
                awaitingRebootReconnect = awaitingRebootReconnect
            )
        }
        isReattach
    }

    fun onDetached(deviceName: String): Boolean = synchronized(lock) {
        val removed = attachedDevices.remove(deviceName)
        val wasActiveSession = activeConnectedDeviceName == deviceName
        if (wasActiveSession) {
            activeConnectedDeviceName = null
        }
        val matchesReady = lastReadyDescriptor?.deviceName == deviceName ||
            (removed != null && lastReadyDescriptor?.matchesAfterReboot(removed) == true)
        if (wasActiveSession || matchesReady) {
            awaitingRebootReconnect = true
        }
        syncIdleUnlocked(lastFailure = null)
        wasActiveSession
    }

    fun onAuthorising(device: AdbUsbDeviceDescriptor, stage: AdbUsbAuthStage) = synchronized(lock) {
        _state.value = AdbUsbState.Authorising(device = device, stage = stage)
    }

    fun onSessionTransition(
        device: AdbUsbDeviceDescriptor?,
        connected: AdbConnectedDevice?,
        failure: AdbFailure?
    ) {
        val refreshed = device?.let { refreshSingle(it.deviceName) ?: it }
        synchronized(lock) {
            if (connected != null && refreshed != null) {
                lastReadyDescriptor = refreshed
                activeConnectedDeviceName = refreshed.deviceName
                awaitingRebootReconnect = false
                _state.value = AdbUsbState.Ready(refreshed, connected)
            } else {
                activeConnectedDeviceName = null
                syncIdleUnlocked(failure)
            }
        }
    }

    private fun syncIdleUnlocked(lastFailure: AdbFailure?) {
        val primaryAdb = attachedDevices.values.firstOrNull { it.hasAdbInterface }
        _state.value = if (primaryAdb != null) {
            AdbUsbState.CableDetected(
                device = primaryAdb,
                permissionGranted = primaryAdb.hasUsbPermission,
                reconnectedAfterDetach = awaitingRebootReconnect,
                lastFailure = lastFailure
            )
        } else {
            AdbUsbState.Disconnected(
                nonAdbUsbAttached = attachedDevices.values.any { !it.hasAdbInterface },
                awaitingRebootReconnect = awaitingRebootReconnect,
                lastFailure = lastFailure
            )
        }
    }
}

/** No-op [UsbHostBackend] used when no Android [UsbHostBackend] is supplied in Wi-Fi-only tests. */
internal object NoOpUsbHostBackend : UsbHostBackend {
    override fun listDevices(): List<AdbUsbDeviceDescriptor> = emptyList()

    override fun hasPermission(deviceName: String): Boolean = false

    override fun requestPermission(deviceName: String) = Unit

    override fun openAdbConnection(deviceName: String): UsbBulkConnection =
        throw EOFException("No USB host backend configured")

    override fun observeEvents(listener: UsbHostEventListener): Closeable = Closeable {}

    override fun close() = Unit
}
