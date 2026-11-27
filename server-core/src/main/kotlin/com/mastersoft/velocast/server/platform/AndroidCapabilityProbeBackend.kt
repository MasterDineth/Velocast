package com.mastersoft.velocast.server.platform

import android.graphics.PixelFormat
import android.media.ImageReader
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import com.mastersoft.velocast.server.CapabilityProbeBackend
import com.mastersoft.velocast.server.Logger
import com.mastersoft.velocast.server.ProbeDeniedException
import com.mastersoft.velocast.server.ProbeEnvironmentInfo
import com.mastersoft.velocast.server.UnsupportedPlatformException
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.lang.reflect.Method
import java.util.concurrent.TimeUnit

/**
 * Android platform backend for [com.mastersoft.velocast.server.CapabilityProbe].
 *
 * Delegates directly to the production `platform/` shims ([VirtualDisplayBridge],
 * [BinderResolvers], [WindowManagerRotationHelper], [DisplayPowerHandlesResolver],
 * [ClipboardBinderInvoker]) so probe results and production behaviour share a single per-API-level
 * implementation and restore mutable device state before returning.
 */
internal class AndroidCapabilityProbeBackend(
    private val platformContext: PlatformContext,
    private val logger: Logger,
    private val sdkInt: Int = Build.VERSION.SDK_INT
) : CapabilityProbeBackend {

    override val environmentInfo: ProbeEnvironmentInfo
        get() = ProbeEnvironmentInfo(
            sdkInt = sdkInt,
            uid = platformContext.callerUid,
            packageName = platformContext.callerPackage,
            model = Build.MODEL.orEmpty()
        )

    override fun probeVirtualDisplay(): String {
        val bridge = VirtualDisplayBridge(logger, platformContext, sdkInt)
        val geom = bridge.queryDefaultDisplayGeometry()
        val width = geom.width.coerceAtMost(PROBE_MAX_WIDTH).coerceAtLeast(MIN_DISPLAY_DIM)
        val height = (geom.height.toLong() * width / geom.width)
            .toInt()
            .coerceAtLeast(MIN_DISPLAY_DIM)
        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 1)
        try {
            bridge.startMirroring(reader.surface, width, height)
            return "DisplayManager.createVirtualDisplay (${width}x$height)"
        } finally {
            bridge.close()
            reader.close()
        }
    }

    override fun probeInputInjection(): String = ProbeInputHelpers.injectKey(
        sdkInt = sdkInt,
        logger = logger,
        keyCode = KeyEvent.KEYCODE_UNKNOWN,
        action = KeyEvent.ACTION_UP,
        downTimeMs = SystemClock.uptimeMillis(),
        eventTimeMs = SystemClock.uptimeMillis(),
        label = "KEYCODE_UNKNOWN"
    )

    override fun probeWakeKey(): String {
        val now = SystemClock.uptimeMillis()
        ProbeInputHelpers.injectKey(
            sdkInt = sdkInt,
            logger = logger,
            keyCode = KeyEvent.KEYCODE_WAKEUP,
            action = KeyEvent.ACTION_DOWN,
            downTimeMs = now,
            eventTimeMs = now,
            label = "KEYCODE_WAKEUP"
        )
        return ProbeInputHelpers.injectKey(
            sdkInt = sdkInt,
            logger = logger,
            keyCode = KeyEvent.KEYCODE_WAKEUP,
            action = KeyEvent.ACTION_UP,
            downTimeMs = now,
            eventTimeMs = now + WAKE_TAP_DURATION_MS,
            label = "KEYCODE_WAKEUP"
        )
    }

    override fun probeNotificationShade(): String {
        val binderError = try {
            ProbeShadeHelpers.probeViaBinder(logger)
            return "IStatusBarService.expandNotificationsPanel/collapsePanels"
        } catch (e: ReflectiveOperationException) {
            e
        } catch (e: SecurityException) {
            e
        } catch (e: UnsupportedPlatformException) {
            e
        } catch (e: LinkageError) {
            e
        }
        return ProbeShadeHelpers.probeViaCmd(binderError, logger)
    }

    override fun probeRotation(): String {
        val controller = AndroidDeviceStateController(
            logger = logger,
            platformContext = platformContext,
            sdkInt = sdkInt
        )
        val initialState = controller.captureState()
        val (wm, initialRotation) = ProbeRotationHelpers.resolveWindowManagerAndRotation()
        val targetRotation = (initialRotation + 1) % ROTATION_COUNT
        try {
            val methodName = WindowManagerRotationHelper.freezeRotation(
                wm = wm,
                rotation = targetRotation,
                callerPackage = platformContext.callerPackage,
                sdkInt = sdkInt
            ) ?: throw UnsupportedPlatformException("IWindowManager.freezeRotation unavailable")
            val afterRotation =
                wm.javaClass.getMethod("getDefaultDisplayRotation").invoke(wm) as? Int
            return "IWindowManager.$methodName " +
                "(before=$initialRotation, target=$targetRotation, after=$afterRotation)"
        } finally {
            ProbeReflection.runQuietly(logger, "restoreRotationState") {
                AndroidDeviceStateController.restoreRotationState(
                    accelerometerRotation = initialState.accelerometerRotation,
                    userRotation = initialState.userRotation,
                    logger = logger,
                    callerPackage = platformContext.callerPackage,
                    sdkInt = sdkInt
                )
            }
        }
    }

    override fun probeDisplayPower(): String {
        val wasOn = AndroidDeviceStateController(
            logger = logger,
            platformContext = platformContext,
            sdkInt = sdkInt
        ).captureState().displayPowerOn
        val handles = DisplayPowerHandlesResolver.resolveOrThrow(sdkInt, logger)
        val token = handles.initialToken
        try {
            handles.setDisplayPowerMode.invoke(
                null,
                token,
                ReflectionDisplayPowerBackend.POWER_MODE_OFF
            )
            handles.setDisplayPowerMode.invoke(
                null,
                token,
                ReflectionDisplayPowerBackend.POWER_MODE_NORMAL
            )
            val setter = "${handles.setDisplayPowerMode.declaringClass.simpleName}." +
                "setDisplayPowerMode"
            return "$setter (${handles.tokenSourceLabel})"
        } finally {
            ProbeReflection.runQuietly(logger, "restoreDisplayPower") {
                val restoreMode = if (wasOn) {
                    ReflectionDisplayPowerBackend.POWER_MODE_NORMAL
                } else {
                    ReflectionDisplayPowerBackend.POWER_MODE_OFF
                }
                handles.setDisplayPowerMode.invoke(null, token, restoreMode)
            }
        }
    }

    override fun probeClipboard(): String {
        val clipboard =
            ProbeReflection.resolveServiceInterface("clipboard", "android.content.IClipboard\$Stub")
                ?: throw UnsupportedPlatformException("IClipboard service unavailable")
        return ClipboardBinderInvoker.probeRoundTrip(clipboard, platformContext, sdkInt, logger)
    }

    private companion object {
        const val PROBE_MAX_WIDTH = 720
        const val MIN_DISPLAY_DIM = 64
        const val ROTATION_COUNT = 4
        const val WAKE_TAP_DURATION_MS = 10L
    }
}

private object ProbeRotationHelpers {
    fun resolveWindowManagerAndRotation(): Pair<Any, Int> {
        val wm =
            ProbeReflection.resolveServiceInterface("window", "android.view.IWindowManager\$Stub")
                ?: throw UnsupportedPlatformException("IWindowManager service unavailable")
        val rotation = wm.javaClass.getMethod("getDefaultDisplayRotation").invoke(wm) as? Int
            ?: throw UnsupportedPlatformException("getDefaultDisplayRotation returned null")
        return wm to rotation
    }
}

private object ProbeInputHelpers {
    private const val INJECT_MODE_WAIT_FOR_RESULT = 1

    fun injectKey(
        sdkInt: Int,
        logger: Logger,
        keyCode: Int,
        action: Int,
        downTimeMs: Long,
        eventTimeMs: Long,
        label: String
    ): String {
        val (instance, method) = BinderResolvers.resolveInputManager(sdkInt, logger)
        val event = KeyEvent(
            downTimeMs,
            eventTimeMs,
            action,
            keyCode,
            0,
            0,
            KeyCharacterMap.VIRTUAL_KEYBOARD,
            0,
            0,
            InputDevice.SOURCE_KEYBOARD
        )
        val result = method.invoke(instance, event, INJECT_MODE_WAIT_FOR_RESULT)
        if (result as? Boolean == false) {
            throw ProbeDeniedException(
                permission = "android.permission.INJECT_EVENTS",
                message = "InputManager.injectInputEvent returned false for keyCode=$keyCode"
            )
        }
        return "InputManager.injectInputEvent ($label via ${instance.javaClass.simpleName})"
    }
}

private object ProbeShadeHelpers {
    private const val SHADE_SETTLE_MS = 150L
    private const val COMMAND_TIMEOUT_MS = 3_000L

    private data class CommandOutput(val exitCode: Int, val output: String)

    fun probeViaBinder(logger: Logger) {
        val service = ProbeReflection.resolveServiceInterface(
            "statusbar",
            "com.android.internal.statusbar.IStatusBarService\$Stub"
        ) ?: throw UnsupportedPlatformException("IStatusBarService unavailable")
        val expand = service.javaClass.getMethod("expandNotificationsPanel")
            .apply { isAccessible = true }
        val collapse = service.javaClass.getMethod("collapsePanels")
            .apply { isAccessible = true }
        try {
            expand.invoke(service)
            Thread.sleep(SHADE_SETTLE_MS)
        } finally {
            ProbeReflection.runQuietly(logger, "collapsePanels") { collapse.invoke(service) }
        }
    }

    fun probeViaCmd(priorError: Throwable, logger: Logger): String {
        val expandRes = runCommand("/system/bin/cmd", "statusbar", "expand-notifications")
        if (expandRes.exitCode != 0) {
            collapseQuietly(logger)
            throw classifyFailure("cmd statusbar expand-notifications", expandRes, priorError)
        }
        val collapseRes = sleepAndCollapse(logger)
        if (collapseRes.exitCode != 0) {
            throw classifyFailure("cmd statusbar collapse", collapseRes, priorError)
        }
        return "cmd statusbar expand-notifications/collapse"
    }

    private fun sleepAndCollapse(logger: Logger): CommandOutput {
        try {
            Thread.sleep(SHADE_SETTLE_MS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            collapseQuietly(logger)
            throw e
        }
        return runCommand("/system/bin/cmd", "statusbar", "collapse")
    }

    private fun collapseQuietly(logger: Logger) {
        ProbeReflection.runQuietly(logger, "cmd statusbar collapse") {
            runCommand("/system/bin/cmd", "statusbar", "collapse")
        }
    }

    private fun runCommand(vararg cmd: String): CommandOutput {
        val proc = ProcessBuilder(*cmd)
            .redirectErrorStream(true)
            .start()
        val text = BufferedReader(InputStreamReader(proc.inputStream)).use { it.readText() }.trim()
        val finished = proc.waitFor(COMMAND_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        return if (!finished) {
            proc.destroyForcibly()
            CommandOutput(-1, "Command timed out: ${cmd.joinToString(" ")}")
        } else {
            CommandOutput(proc.exitValue(), text)
        }
    }

    private fun classifyFailure(
        cmdLabel: String,
        result: CommandOutput,
        priorError: Throwable
    ): Throwable {
        val isSecurity = result.output.contains("SecurityException", ignoreCase = true) ||
            result.output.contains("Permission Denial", ignoreCase = true) ||
            result.output.contains("requires android.permission.", ignoreCase = true)
        return if (isSecurity) {
            SecurityException(result.output, priorError)
        } else {
            UnsupportedPlatformException(
                "$cmdLabel exited ${result.exitCode}: ${result.output}",
                priorError
            )
        }
    }
}

private object ProbeReflection {
    private const val TAG = "CapabilityProbeBackend"

    fun resolveServiceInterface(serviceName: String, stubClassName: String): Any? {
        val smClass = Class.forName("android.os.ServiceManager")
        val binder = smClass.getDeclaredMethod("getService", String::class.java)
            .apply { isAccessible = true }
            .invoke(null, serviceName) as? IBinder ?: return null
        val stubClass = Class.forName(stubClassName)
        return stubClass.getDeclaredMethod("asInterface", IBinder::class.java)
            .apply { isAccessible = true }
            .invoke(null, binder)
    }

    inline fun runQuietly(logger: Logger, label: String, block: () -> Unit) {
        try {
            block()
        } catch (e: ReflectiveOperationException) {
            logger.debug(TAG, "Cleanup step $label failed", e)
        } catch (e: SecurityException) {
            logger.debug(TAG, "Cleanup step $label denied", e)
        } catch (e: LinkageError) {
            logger.debug(TAG, "Cleanup step $label linkage error", e)
        } catch (e: IOException) {
            logger.debug(TAG, "Cleanup step $label I/O error", e)
        } catch (e: IllegalStateException) {
            logger.debug(TAG, "Cleanup step $label state error", e)
        } catch (e: UnsupportedPlatformException) {
            logger.debug(TAG, "Cleanup step $label unsupported", e)
        }
    }
}
