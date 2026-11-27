package com.mastersoft.velocast.server.platform

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
 * Runtime capability probe results reported in `STATUS` after session authentication.
 */
data class ServerCapabilities(
    val rotationWatcher: Boolean,
    val displayPowerControl: Boolean,
    val notificationShade: Boolean,
    val wake: Boolean
) {
    fun toFeatureMask(): Int {
        var mask = FEATURE_ROTATION_EVENTS
        if (displayPowerControl) {
            mask = mask or FEATURE_DISPLAY_POWER
        }
        return mask
    }

    fun toStatusDetail(): String {
        val raw = "rotation_watcher=${bit(rotationWatcher)}," +
            "display_power=${bit(displayPowerControl)}," +
            "notification_shade=${bit(notificationShade)}," +
            "wake=${bit(wake)}"
        return truncateUtf8(raw, ProtocolV2.MAX_STATUS_DETAIL_BYTES)
    }

    fun degradedStatuses(): List<Status> {
        val out = ArrayList<Status>(MAX_CAPABILITIES)
        if (!rotationWatcher) {
            out += Status(StatusKind.DEGRADED, BIT_ROTATION_EVENTS, "rotation_watcher")
        }
        if (!displayPowerControl) {
            out += Status(StatusKind.DEGRADED, BIT_DISPLAY_POWER, "display_power")
        }
        if (!notificationShade) {
            out += Status(StatusKind.DEGRADED, 0, "notification_shade")
        }
        if (!wake) {
            out += Status(StatusKind.DEGRADED, 0, "wake")
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
        const val FEATURE_DISPLAY_POWER = 0x08
        const val FEATURE_ROTATION_EVENTS = 0x10
        private const val BIT_DISPLAY_POWER = 3
        private const val BIT_ROTATION_EVENTS = 4
        private const val MAX_CAPABILITIES = 4
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
    private val displayPowerBackend: DisplayPowerBackend = ReflectionDisplayPowerBackend(logger)
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
        restoreRotationState(state.accelerometerRotation, state.userRotation, logger)
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

        fun restoreRotationState(accelerometerRotation: Int, userRotation: Int, logger: Logger) {
            val wm = resolveWindowManager(logger)
            var restoredViaBinder = false
            if (wm != null) {
                restoredViaBinder = if (accelerometerRotation == 0) {
                    freezeRotation(wm, userRotation.coerceIn(0, ProtocolV2.MAX_ROTATION), logger)
                } else {
                    thawRotation(wm, logger)
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

        private fun thawRotation(wm: Any, logger: Logger): Boolean = try {
            val withCaller = try {
                wm.javaClass.getMethod("thawRotation", String::class.java)
            } catch (_: NoSuchMethodException) {
                null
            }
            if (withCaller != null) {
                withCaller.invoke(wm, "velocast-restore")
            } else {
                wm.javaClass.getMethod("thawRotation").invoke(wm)
            }
            true
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

        private fun freezeRotation(wm: Any, rotation: Int, logger: Logger): Boolean = try {
            val withCaller = try {
                wm.javaClass.getMethod(
                    "freezeRotation",
                    Int::class.javaPrimitiveType,
                    String::class.java
                )
            } catch (_: NoSuchMethodException) {
                null
            }
            if (withCaller != null) {
                withCaller.invoke(wm, rotation, "velocast-restore")
            } else {
                wm.javaClass.getMethod("freezeRotation", Int::class.javaPrimitiveType)
                    .invoke(wm, rotation)
            }
            true
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
