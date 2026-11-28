package com.mastersoft.velocast.launcher.adb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Production [UsbHostBackend] backed by Android's `UsbManager`, package-explicit `PendingIntent`
 * permission requests (`targetSdk = 36` safe), and `ACTION_USB_DEVICE_ATTACHED` /
 * `ACTION_USB_DEVICE_DETACHED` broadcasts (`S07C` #1–#2).
 */
class AndroidUsbHostBackend(context: Context) : UsbHostBackend {
    private val appContext = context.applicationContext
    private val usbManager = appContext.getSystemService(Context.USB_SERVICE) as? UsbManager
    private val permissionAction = "${appContext.packageName}$PERMISSION_ACTION_SUFFIX"
    private val listeners = CopyOnWriteArrayList<UsbHostEventListener>()
    private val receiverLock = Any()

    @Volatile
    private var activeReceiver: BroadcastReceiver? = null

    override fun listDevices(): List<AdbUsbDeviceDescriptor> {
        val manager = usbManager ?: return emptyList()
        return manager.deviceList.values
            .map { device -> AndroidUsbConnectionOpener.describe(manager, device) }
            .sortedWith(
                compareByDescending<AdbUsbDeviceDescriptor> { it.hasAdbInterface }
                    .thenBy { it.deviceName }
            )
    }

    override fun hasPermission(deviceName: String): Boolean {
        val manager = usbManager
        val device = manager?.deviceList?.get(deviceName)
        return manager != null && device != null && manager.hasPermission(device)
    }

    override fun requestPermission(deviceName: String) {
        val manager = usbManager
        val device = manager?.deviceList?.get(deviceName)
        if (manager != null && device != null) {
            val explicitIntent = Intent(permissionAction).apply {
                setPackage(appContext.packageName)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                UsbBroadcastCompat.mutablePendingIntentFlag()
            val pendingIntent = PendingIntent.getBroadcast(appContext, 0, explicitIntent, flags)
            manager.requestPermission(device, pendingIntent)
        }
    }

    override fun openAdbConnection(deviceName: String): UsbBulkConnection {
        val manager = usbManager
            ?: throw IOException("Android UsbManager is unavailable on this device")
        val device = manager.deviceList[deviceName]
            ?: throw EOFException("USB device '$deviceName' is no longer attached")
        return AndroidUsbConnectionOpener.open(manager, device)
    }

    override fun observeEvents(listener: UsbHostEventListener): Closeable {
        synchronized(receiverLock) {
            listeners += listener
            if (activeReceiver == null) {
                activeReceiver = registerUsbBroadcastReceiver()
            }
        }
        return Closeable {
            synchronized(receiverLock) {
                listeners.remove(listener)
                if (listeners.isEmpty()) {
                    unregisterReceiverLocked()
                }
            }
        }
    }

    override fun close() {
        synchronized(receiverLock) {
            listeners.clear()
            unregisterReceiverLocked()
        }
    }

    private fun registerUsbBroadcastReceiver(): BroadcastReceiver {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                dispatchBroadcast(intent)
            }
        }
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(permissionAction)
        }
        UsbBroadcastCompat.registerNotExported(appContext, receiver, filter)
        return receiver
    }

    private fun unregisterReceiverLocked() {
        val receiver = activeReceiver ?: return
        activeReceiver = null
        try {
            appContext.unregisterReceiver(receiver)
        } catch (_: IllegalArgumentException) {
            // Receiver was already unregistered.
        }
    }

    private fun dispatchBroadcast(intent: Intent) {
        val device = UsbBroadcastCompat.extractUsbDevice(intent) ?: return
        when (intent.action) {
            UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                val manager = usbManager ?: return
                val descriptor = AndroidUsbConnectionOpener.describe(manager, device)
                for (listener in listeners) {
                    listener.onDeviceAttached(descriptor)
                }
            }

            UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                for (listener in listeners) {
                    listener.onDeviceDetached(device.deviceName)
                }
            }

            permissionAction -> {
                val grantedExtra = intent.getBooleanExtra(
                    UsbManager.EXTRA_PERMISSION_GRANTED,
                    false
                )
                val granted = grantedExtra || (usbManager?.hasPermission(device) == true)
                for (listener in listeners) {
                    listener.onPermissionResult(device.deviceName, granted)
                }
            }
        }
    }

    companion object {
        const val PERMISSION_ACTION_SUFFIX = ".USB_ADB_PERMISSION"
    }
}

private object UsbBroadcastCompat {
    fun registerNotExported(context: Context, receiver: BroadcastReceiver, filter: IntentFilter) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
    }

    fun extractUsbDevice(intent: Intent): UsbDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }

    fun mutablePendingIntentFlag(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
}

internal object AndroidUsbConnectionOpener {
    fun describe(usbManager: UsbManager, device: UsbDevice): AdbUsbDeviceDescriptor {
        val hasPerm = usbManager.hasPermission(device)
        val serial = if (hasPerm) readSerialSafely(device) else null
        val adbEndpoints = findAdbInterfaceAndEndpoints(device)
        return if (adbEndpoints != null) {
            AdbUsbDeviceDescriptor(
                deviceName = device.deviceName,
                deviceId = device.deviceId,
                vendorId = device.vendorId,
                productId = device.productId,
                serialNumber = serial,
                productName = device.productName,
                manufacturerName = device.manufacturerName,
                hasAdbInterface = true,
                interfaceId = adbEndpoints.usbInterface.id,
                inMaxPacketSize = adbEndpoints.inEndpoint.maxPacketSize,
                outMaxPacketSize = adbEndpoints.outEndpoint.maxPacketSize,
                hasUsbPermission = hasPerm
            )
        } else {
            AdbUsbDeviceDescriptor(
                deviceName = device.deviceName,
                deviceId = device.deviceId,
                vendorId = device.vendorId,
                productId = device.productId,
                serialNumber = serial,
                productName = device.productName,
                manufacturerName = device.manufacturerName,
                hasAdbInterface = false,
                hasUsbPermission = hasPerm
            )
        }
    }

    @Throws(IOException::class)
    fun open(usbManager: UsbManager, device: UsbDevice): UsbBulkConnection {
        val resolved = requirePermittedAdbEndpoints(usbManager, device)
        val rawConn = openAndClaimDevice(usbManager, device, resolved.usbInterface)
        val descriptor = describe(usbManager, device)
        return AndroidUsbBulkConnection(
            usbManager = usbManager,
            usbDevice = device,
            usbInterface = resolved.usbInterface,
            inEndpoint = resolved.inEndpoint,
            outEndpoint = resolved.outEndpoint,
            rawConnection = rawConn,
            descriptor = descriptor
        )
    }

    private fun requirePermittedAdbEndpoints(
        usbManager: UsbManager,
        device: UsbDevice
    ): ResolvedAdbUsbEndpoints {
        if (!usbManager.hasPermission(device)) {
            throw IOException("Missing Android UsbManager permission for ${device.deviceName}")
        }
        return findAdbInterfaceAndEndpoints(device)
            ?: throw IOException(
                "USB device ${device.deviceName} does not expose an ADB interface " +
                    "(class=0xFF, subclass=0x42, protocol=0x01)"
            )
    }

    private fun openAndClaimDevice(
        usbManager: UsbManager,
        device: UsbDevice,
        adbInterface: UsbInterface
    ): UsbDeviceConnection {
        val rawConn = usbManager.openDevice(device)
            ?: throw IOException("UsbManager.openDevice returned null for ${device.deviceName}")
        if (!rawConn.claimInterface(adbInterface, true)) {
            rawConn.close()
            throw IOException("Failed claiming USB ADB interface on ${device.deviceName}")
        }
        return rawConn
    }

    private fun findAdbInterfaceAndEndpoints(device: UsbDevice): ResolvedAdbUsbEndpoints? {
        var matched: ResolvedAdbUsbEndpoints? = null
        var index = 0
        while (index < device.interfaceCount && matched == null) {
            val intf = device.getInterface(index)
            val isAdb = intf.interfaceClass == AdbUsbConstants.INTERFACE_CLASS_ADB &&
                intf.interfaceSubclass == AdbUsbConstants.INTERFACE_SUBCLASS_ADB &&
                intf.interfaceProtocol == AdbUsbConstants.INTERFACE_PROTOCOL_ADB
            if (isAdb) {
                matched = extractBulkPair(intf)
            }
            index++
        }
        return matched
    }

    private fun extractBulkPair(intf: UsbInterface): ResolvedAdbUsbEndpoints? {
        var bulkIn: UsbEndpoint? = null
        var bulkOut: UsbEndpoint? = null
        for (e in 0 until intf.endpointCount) {
            val ep = intf.getEndpoint(e)
            if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK && ep.maxPacketSize > 0) {
                when (ep.direction) {
                    UsbConstants.USB_DIR_IN -> if (bulkIn == null) bulkIn = ep
                    UsbConstants.USB_DIR_OUT -> if (bulkOut == null) bulkOut = ep
                }
            }
        }
        return if (bulkIn != null && bulkOut != null) {
            ResolvedAdbUsbEndpoints(intf, bulkIn, bulkOut)
        } else {
            null
        }
    }

    private fun readSerialSafely(device: UsbDevice): String? = try {
        device.serialNumber?.takeIf(String::isNotBlank)
    } catch (_: SecurityException) {
        null
    }

    private class ResolvedAdbUsbEndpoints(
        val usbInterface: UsbInterface,
        val inEndpoint: UsbEndpoint,
        val outEndpoint: UsbEndpoint
    )
}

private class AndroidUsbBulkConnection(
    private val usbManager: UsbManager,
    private val usbDevice: UsbDevice,
    private val usbInterface: UsbInterface,
    private val inEndpoint: UsbEndpoint,
    private val outEndpoint: UsbEndpoint,
    private val rawConnection: UsbDeviceConnection,
    override val descriptor: AdbUsbDeviceDescriptor
) : UsbBulkConnection {
    private val closed = AtomicBoolean(false)

    override val isClosed: Boolean
        get() = closed.get()

    override fun bulkIn(buffer: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int {
        ensureOpen()
        val transferred = rawConnection.bulkTransfer(inEndpoint, buffer, offset, length, timeoutMs)
        if (transferred < 0) {
            handleNegativeTransfer("IN", timeoutMs, transferred)
        }
        return transferred
    }

    override fun bulkOut(buffer: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int {
        ensureOpen()
        val transferred = rawConnection.bulkTransfer(outEndpoint, buffer, offset, length, timeoutMs)
        if (transferred < 0) {
            handleNegativeTransfer("OUT", timeoutMs, transferred)
        }
        return transferred
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            try {
                rawConnection.releaseInterface(usbInterface)
            } catch (_: IllegalStateException) {
                // Interface was already released on detached hardware.
            }
            rawConnection.close()
        }
    }

    private fun ensureOpen() {
        if (closed.get()) {
            throw EOFException("USB connection to ${descriptor.deviceName} is closed")
        }
    }

    private fun handleNegativeTransfer(direction: String, timeoutMs: Int, code: Int): Nothing {
        val stillAttached = !closed.get() && usbManager.deviceList.containsKey(usbDevice.deviceName)
        if (!closed.get() && !stillAttached) {
            close()
        }
        val failure: IOException = when {
            closed.get() && stillAttached ->
                EOFException("USB bulk $direction aborted because connection closed")

            !stillAttached -> EOFException(
                "USB device ${descriptor.deviceName} detached or closed during bulk $direction"
            )

            timeoutMs > 0 -> SocketTimeoutException(
                "USB bulk $direction timed out after ${timeoutMs}ms on ${descriptor.deviceName}"
            )

            else -> IOException("USB bulk $direction failed ($code) on ${descriptor.deviceName}")
        }
        throw failure
    }
}
