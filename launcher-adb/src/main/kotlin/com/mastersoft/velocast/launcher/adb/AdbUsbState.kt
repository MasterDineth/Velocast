package com.mastersoft.velocast.launcher.adb

import java.io.Closeable
import java.io.IOException
import java.util.Locale

/**
 * Standard Android ADB USB interface constants (`packages/modules/adb/adb.h`).
 */
object AdbUsbConstants {
    const val INTERFACE_CLASS_ADB = 0xFF
    const val INTERFACE_SUBCLASS_ADB = 0x42
    const val INTERFACE_PROTOCOL_ADB = 0x01

    /**
     * Maximum single `UsbDeviceConnection.bulkTransfer` chunk size (`16 KB`), compatible with
     * Android 8.0–8.1 (`API 26–27`) `usbfs` buffer caps as well as `API 28+`, and an exact
     * multiple of `64`, `512`, and `1024`-byte USB bulk `wMaxPacketSize` values.
     */
    const val DEFAULT_MAX_BULK_TRANSFER_BYTES = 16 * 1024
}

/**
 * Snapshot of an attached USB device inspected via `UsbManager` (`S07C` #1–#2).
 */
data class AdbUsbDeviceDescriptor(
    val deviceName: String,
    val deviceId: Int,
    val vendorId: Int,
    val productId: Int,
    val serialNumber: String? = null,
    val productName: String? = null,
    val manufacturerName: String? = null,
    val hasAdbInterface: Boolean = true,
    val interfaceId: Int = 0,
    val inMaxPacketSize: Int = DEFAULT_HIGH_SPEED_MAX_PACKET,
    val outMaxPacketSize: Int = DEFAULT_HIGH_SPEED_MAX_PACKET,
    val hasUsbPermission: Boolean = false
) {
    init {
        require(deviceName.isNotBlank()) { "deviceName must not be blank" }
        require(!hasAdbInterface || (inMaxPacketSize > 0 && outMaxPacketSize > 0)) {
            "ADB USB endpoints must report positive maxPacketSize " +
                "(in=$inMaxPacketSize, out=$outMaxPacketSize)"
        }
    }

    /**
     * Correlation identifier preferring hardware [serialNumber] when readable and falling back to
     * `vid:pid` when Android 10+ withholds [serialNumber] prior to `UsbManager` permission grant.
     */
    val correlationKey: String
        get() = serialNumber?.takeIf(String::isNotBlank) ?: vendorProductKey

    val vendorProductKey: String
        get() = String.format(Locale.ROOT, "vid:%04x:pid:%04x", vendorId, productId)

    /** Returns `true` when [serialOrDeviceName] is `null`/blank or matches this USB device. */
    fun matchesTarget(serialOrDeviceName: String?): Boolean {
        val target = serialOrDeviceName?.trim()?.takeIf(String::isNotEmpty) ?: return true
        return deviceName == target ||
            serialNumber == target ||
            correlationKey.equals(target, ignoreCase = true) ||
            vendorProductKey.equals(target, ignoreCase = true)
    }

    /**
     * Returns `true` when [candidate] represents the same physical phone re-enumerating on a new
     * USB bus address after a reboot (even before `UsbManager` permission unlocks `serialNumber`).
     */
    fun matchesAfterReboot(candidate: AdbUsbDeviceDescriptor): Boolean {
        if (!candidate.hasAdbInterface) return false
        val knownSerial = serialNumber?.takeIf(String::isNotBlank)
        val candidateSerial = candidate.serialNumber?.takeIf(String::isNotBlank)
        return if (knownSerial != null && candidateSerial != null) {
            knownSerial == candidateSerial
        } else {
            vendorId == candidate.vendorId && productId == candidate.productId
        }
    }

    companion object {
        const val DEFAULT_HIGH_SPEED_MAX_PACKET = 512
    }
}

/**
 * Distinguishes which approval dialog is currently blocking USB setup in [AdbUsbState.Authorising].
 */
enum class AdbUsbAuthStage {
    /** Waiting for Android's local USB host access dialog on the Viewer phone. */
    VIEWER_USB_PERMISSION,

    /** Waiting for the *"Allow USB debugging?"* confirmation dialog on the old phone. */
    DEVICE_ADB_APPROVAL
}

/**
 * UI-facing state of USB OTG connectivity for the **Add old phone** and **Connecting** screens
 * (`S07C` #4).
 */
sealed interface AdbUsbState {
    /**
     * No ADB-capable USB OTG device is currently attached.
     *
     * @property nonAdbUsbAttached `true` when a USB peripheral is attached without an ADB
     *   interface (for example, an old phone whose USB debugging switch is turned off).
     * @property awaitingRebootReconnect `true` when a previously active or authorised ADB phone
     *   detached and the viewer is waiting for it to re-enumerate (e.g. during a reboot).
     * @property lastFailure Most recent typed USB failure, if any.
     */
    data class Disconnected(
        val nonAdbUsbAttached: Boolean = false,
        val awaitingRebootReconnect: Boolean = false,
        val lastFailure: AdbFailure? = null
    ) : AdbUsbState

    /**
     * An ADB-capable phone (`class 0xFF, subclass 0x42, protocol 0x01`) is physically attached.
     */
    data class CableDetected(
        val device: AdbUsbDeviceDescriptor,
        val permissionGranted: Boolean = device.hasUsbPermission,
        val reconnectedAfterDetach: Boolean = false,
        val lastFailure: AdbFailure? = null
    ) : AdbUsbState

    /**
     * Waiting for user approval on either the Viewer ([AdbUsbAuthStage.VIEWER_USB_PERMISSION]) or
     * the old phone ([AdbUsbAuthStage.DEVICE_ADB_APPROVAL]).
     */
    data class Authorising(
        val device: AdbUsbDeviceDescriptor,
        val stage: AdbUsbAuthStage = AdbUsbAuthStage.DEVICE_ADB_APPROVAL
    ) : AdbUsbState

    /**
     * Viewer USB permission and `adbd` authentication have both succeeded; the cable is ready for
     * Prepare commands or tunneled session streaming.
     */
    data class Ready(val device: AdbUsbDeviceDescriptor, val connectedDevice: AdbConnectedDevice) :
        AdbUsbState
}

/**
 * Callbacks emitted by a [UsbHostBackend] for USB hotplug and permission broadcasts.
 */
interface UsbHostEventListener {
    fun onDeviceAttached(device: AdbUsbDeviceDescriptor)

    fun onDeviceDetached(deviceName: String)

    fun onPermissionResult(deviceName: String, granted: Boolean)
}

/**
 * Platform abstraction over Android's `UsbManager` and broadcast receiver registration so USB
 * discovery, permission flows, `AUTH_EXPIRED`, and reboot re-enumeration are unit-testable on JVM.
 */
interface UsbHostBackend : Closeable {
    fun listDevices(): List<AdbUsbDeviceDescriptor>

    fun hasPermission(deviceName: String): Boolean

    fun requestPermission(deviceName: String)

    @Throws(IOException::class)
    fun openAdbConnection(deviceName: String): UsbBulkConnection

    fun observeEvents(listener: UsbHostEventListener): Closeable
}
