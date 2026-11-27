package com.mastersoft.velocast.server.platform

import android.os.Build
import android.os.IBinder
import com.mastersoft.velocast.protocol.ProtocolV2
import com.mastersoft.velocast.protocol.Status
import com.mastersoft.velocast.protocol.StatusKind
import com.mastersoft.velocast.server.Logger
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.TimeUnit

/**
 * Snapshot of mutable host device state captured at session start and restored on session end or
 * unexpected server termination via `CleanUp` (FUN-06).
 */
data class SavedDeviceState(
    val accelerometerRotation: Int,
    val userRotation: Int,
    val displayPowerOn: Boolean
)

/**
 * Runtime capability probe results reported to the viewer via `STATUS` after session authentication
 * and advertised as feature bits in `SERVER_HELLO` (CQ-03, SEC-04).
 */
data class ServerCapabilities(
    val inputInjection: Boolean = true,
    val rotationWatcher: Boolean,
    val displayPowerControl: Boolean,
    val notificationShade: Boolean,
    val wake: Boolean,
    val clipboard: Boolean = true
) {
    fun toFeatureMask(): Int {
        var mask = FEATURE_ROTATION_EVENTS
        if (displayPowerControl) {
            mask = mask or FEATURE_DISPLAY_POWER
        }
        if (clipboard) {
            mask = mask or FEATURE_CLIPBOARD
        }
        return mask
    }

    fun toStatusDetail(): String {
        val raw = "input_injection=${bit(inputInjection)}," +
            "rotation_watcher=${bit(rotationWatcher)}," +
            "display_power=${bit(displayPowerControl)}," +
            "notification_shade=${bit(notificationShade)}," +
            "wake=${bit(wake)}," +
            "clipboard=${bit(clipboard)}"
        return truncateUtf8(raw, ProtocolV2.MAX_STATUS_DETAIL_BYTES)
    }

    fun degradedStatuses(): List<Status> {
        val out = ArrayList<Status>(MAX_CAPABILITIES)
        if (!inputInjection) {
            out += Status(StatusKind.DEGRADED, 0, CAP_INPUT_INJECTION)
        }
        if (!rotationWatcher) {
            out += Status(StatusKind.DEGRADED, BIT_ROTATION_EVENTS, CAP_ROTATION_WATCHER)
        }
        if (!displayPowerControl) {
            out += Status(StatusKind.DEGRADED, BIT_DISPLAY_POWER, CAP_DISPLAY_POWER)
        }
        if (!notificationShade) {
            out += Status(StatusKind.DEGRADED, 0, CAP_NOTIFICATION_SHADE)
        }
        if (!wake) {
            out += Status(StatusKind.DEGRADED, 0, CAP_WAKE)
        }
        if (!clipboard) {
            out += Status(StatusKind.DEGRADED, BIT_CLIPBOARD, CAP_CLIPBOARD)
        }
        return out
    }

    private fun bit(enabled: Boolean): Int = if (enabled) 1 else 0

    private fun truncateUtf8(text: String, maxBytes: Int): String {
        if (text.toByteArray(Charsets.UTF_8).size <= maxBytes) return text
        val sb = StringBuilder()
        var bytes = 0
        for (ch in text) {
            val charBytes = ch.toString().toByteArray(Charsets.UTF_8).size
            if (bytes + charBytes > maxBytes) break
            sb.append(ch)
            bytes += charBytes
        }
        return sb.toString()
    }

    companion object {
        const val FEATURE_CLIPBOARD = 0x02
        const val FEATURE_DISPLAY_POWER = 0x08
        const val FEATURE_ROTATION_EVENTS = 0x10
        const val BIT_CLIPBOARD = 1
        const val BIT_DISPLAY_POWER = 3
        const val BIT_ROTATION_EVENTS = 4
        const val CAP_INPUT_INJECTION = "input_injection"
        const val CAP_ROTATION_WATCHER = "rotation_watcher"
        const val CAP_DISPLAY_POWER = "display_power"
        const val CAP_NOTIFICATION_SHADE = "notification_shade"
        const val CAP_WAKE = "wake"
        const val CAP_CLIPBOARD = "clipboard"
        private const val MAX_CAPABILITIES = 6
    }
}

/**
 * Abstraction for reading and restoring host device rotation lock and display power state.
 */
interface DeviceStateController {
    val displayPowerSupported: Boolean

    fun captureState(): SavedDeviceState

    fun restoreState(state: SavedDeviceState)

    fun setDisplayPowerOn(on: Boolean): Boolean
}

class AndroidDeviceStateController(
    private val logger: Logger,
    private val displayPowerBackend: DisplayPowerBackend = ReflectionDisplayPowerBackend(logger),
    private val platformContext: PlatformContext = FakeContext(),
    private val sdkInt: Int = Build.VERSION.SDK_INT
) : DeviceStateController {
    override val displayPowerSupported: Boolean
        get() = displayPowerBackend.isSupported

    override fun captureState(): SavedDeviceState {
        val accelRotation = readSettingInt("accelerometer_rotation")
            ?: readAccelerometerViaWindowManager()
            ?: 1
        val userRotation = (
            readSettingInt("user_rotation")
                ?: readUserRotationViaWindowManager()
                ?: 0
            ).coerceIn(0, ProtocolV2.MAX_ROTATION)
        val displayOn = displayPowerBackend.isDisplayPowerOn()
        return SavedDeviceState(
            accelerometerRotation = if (accelRotation == 0) 0 else 1,
            userRotation = userRotation,
            displayPowerOn = displayOn
        )
    }

    override fun restoreState(state: SavedDeviceState) {
        restoreRotationState(
            accelerometerRotation = state.accelerometerRotation,
            userRotation = state.userRotation,
            logger = logger,
            callerPackage = platformContext.callerPackage,
            sdkInt = sdkInt
        )
        if (displayPowerBackend.isSupported) {
            displayPowerBackend.setDisplayPowerOn(state.displayPowerOn)
        }
    }

    override fun setDisplayPowerOn(on: Boolean): Boolean = displayPowerBackend.setDisplayPowerOn(on)

    private fun readSettingInt(key: String): Int? = try {
        val proc = ProcessBuilder("/system/bin/settings", "get", "system", key).start()
        val line = BufferedReader(InputStreamReader(proc.inputStream)).use { it.readLine() }
        if (!proc.waitFor(COMMAND_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            proc.destroyForcibly()
            null
        } else {
            line?.trim()?.toIntOrNull()
        }
    } catch (e: IOException) {
        logger.debug(TAG, "settings get $key failed", e)
        null
    } catch (e: SecurityException) {
        logger.debug(TAG, "settings get $key denied", e)
        null
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        logger.debug(TAG, "settings get $key interrupted", e)
        null
    }

    private fun readAccelerometerViaWindowManager(): Int? = try {
        val wm = resolveWindowManager(logger) ?: return null
        val frozen = wm.javaClass.getMethod("isRotationFrozen").invoke(wm) as? Boolean
        if (frozen == null) {
            null
        } else if (frozen) {
            0
        } else {
            1
        }
    } catch (e: InvocationTargetException) {
        logger.debug(TAG, "isRotationFrozen failed", e.targetException ?: e)
        null
    } catch (e: ReflectiveOperationException) {
        logger.debug(TAG, "isRotationFrozen unavailable", e)
        null
    } catch (e: SecurityException) {
        logger.debug(TAG, "isRotationFrozen denied", e)
        null
    }

    private fun readUserRotationViaWindowManager(): Int? = try {
        val wm = resolveWindowManager(logger) ?: return null
        wm.javaClass.getMethod("getDefaultDisplayRotation").invoke(wm) as? Int
    } catch (e: InvocationTargetException) {
        logger.debug(TAG, "getDefaultDisplayRotation failed", e.targetException ?: e)
        null
    } catch (e: ReflectiveOperationException) {
        logger.debug(TAG, "getDefaultDisplayRotation unavailable", e)
        null
    } catch (e: SecurityException) {
        logger.debug(TAG, "getDefaultDisplayRotation denied", e)
        null
    }

    companion object {
        private const val TAG = "DeviceStateController"
        private const val COMMAND_TIMEOUT_MS = 1_500L

        fun restoreRotationState(
            accelerometerRotation: Int,
            userRotation: Int,
            logger: Logger,
            callerPackage: String = ShellIdentity.DEFAULT_PACKAGE_NAME,
            sdkInt: Int = Build.VERSION.SDK_INT
        ) {
            val wm = resolveWindowManager(logger)
            var restoredViaBinder = false
            if (wm != null) {
                restoredViaBinder = if (accelerometerRotation == 0) {
                    freezeRotation(
                        wm = wm,
                        rotation = userRotation.coerceIn(0, ProtocolV2.MAX_ROTATION),
                        callerPackage = callerPackage,
                        sdkInt = sdkInt,
                        logger = logger
                    )
                } else {
                    thawRotation(wm, callerPackage, sdkInt, logger)
                }
            }
            if (!restoredViaBinder) {
                putSetting("accelerometer_rotation", accelerometerRotation.toString(), logger)
                putSetting(
                    "user_rotation",
                    userRotation.coerceIn(0, ProtocolV2.MAX_ROTATION).toString(),
                    logger
                )
            }
        }

        private fun resolveWindowManager(logger: Logger): Any? = try {
            val smClass = Class.forName("android.os.ServiceManager")
            val binder = smClass.getDeclaredMethod("getService", String::class.java)
                .apply { isAccessible = true }
                .invoke(null, "window") as? IBinder
            if (binder == null) {
                null
            } else {
                val stubClass = Class.forName("android.view.IWindowManager\$Stub")
                stubClass.getDeclaredMethod("asInterface", IBinder::class.java)
                    .apply { isAccessible = true }
                    .invoke(null, binder)
            }
        } catch (e: ReflectiveOperationException) {
            logger.debug(TAG, "IWindowManager lookup failed", e)
            null
        } catch (e: SecurityException) {
            logger.debug(TAG, "IWindowManager lookup denied", e)
            null
        }

        private fun thawRotation(
            wm: Any,
            callerPackage: String,
            sdkInt: Int,
            logger: Logger
        ): Boolean = try {
            WindowManagerRotationHelper.thawRotation(wm, callerPackage, sdkInt) != null
        } catch (e: InvocationTargetException) {
            logger.warn(TAG, "thawRotation failed", e.targetException ?: e)
            false
        } catch (e: ReflectiveOperationException) {
            logger.warn(TAG, "thawRotation unavailable", e)
            false
        } catch (e: SecurityException) {
            logger.warn(TAG, "thawRotation denied", e)
            false
        }

        private fun freezeRotation(
            wm: Any,
            rotation: Int,
            callerPackage: String,
            sdkInt: Int,
            logger: Logger
        ): Boolean = try {
            WindowManagerRotationHelper.freezeRotation(
                wm = wm,
                rotation = rotation,
                callerPackage = callerPackage,
                sdkInt = sdkInt
            ) != null
        } catch (e: InvocationTargetException) {
            logger.warn(TAG, "freezeRotation failed", e.targetException ?: e)
            false
        } catch (e: ReflectiveOperationException) {
            logger.warn(TAG, "freezeRotation unavailable", e)
            false
        } catch (e: SecurityException) {
            logger.warn(TAG, "freezeRotation denied", e)
            false
        }

        private fun putSetting(key: String, value: String, logger: Logger) {
            try {
                val proc = ProcessBuilder("/system/bin/settings", "put", "system", key, value)
                    .start()
                if (!proc.waitFor(COMMAND_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                    proc.destroyForcibly()
                }
            } catch (e: IOException) {
                logger.warn(TAG, "settings put $key=$value failed", e)
            } catch (e: SecurityException) {
                logger.warn(TAG, "settings put $key=$value denied", e)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                logger.warn(TAG, "settings put $key=$value interrupted", e)
            }
        }
    }
}
