package com.mastersoft.velocast.server.platform

import android.content.ClipData
import android.graphics.PixelFormat
import android.media.ImageReader
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyCharacterMap
import android.view.KeyEvent
import com.mastersoft.velocast.server.CapabilityProbeBackend
import com.mastersoft.velocast.server.Logger
import com.mastersoft.velocast.server.ProbeDeniedException
import com.mastersoft.velocast.server.ProbeEnvironmentInfo
import com.mastersoft.velocast.server.UnsupportedPlatformException
import dalvik.system.PathClassLoader
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.lang.reflect.Method
import java.util.concurrent.TimeUnit

/**
 * Android platform backend for [com.mastersoft.velocast.server.CapabilityProbe].
 *
 * Each probe exercises the real Android service or hidden API as the current UID (typically shell
 * uid 2000) and restores mutable device state (virtual display, shade, rotation lock, display
 * power, clipboard) before returning.
 */
internal class AndroidCapabilityProbeBackend(
    private val platformContext: PlatformContext,
    private val logger: Logger
) : CapabilityProbeBackend {

    override val environmentInfo: ProbeEnvironmentInfo
        get() = ProbeEnvironmentInfo(
            sdkInt = Build.VERSION.SDK_INT,
            uid = platformContext.callerUid,
            packageName = platformContext.callerPackage,
            model = Build.MODEL.orEmpty()
        )

    override fun probeVirtualDisplay(): String {
        val bridge = VirtualDisplayBridge(logger)
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
        keyCode = KeyEvent.KEYCODE_UNKNOWN,
        action = KeyEvent.ACTION_UP,
        downTimeMs = SystemClock.uptimeMillis(),
        eventTimeMs = SystemClock.uptimeMillis(),
        label = "KEYCODE_UNKNOWN"
    )

    override fun probeWakeKey(): String {
        val now = SystemClock.uptimeMillis()
        ProbeInputHelpers.injectKey(
            keyCode = KeyEvent.KEYCODE_WAKEUP,
            action = KeyEvent.ACTION_DOWN,
            downTimeMs = now,
            eventTimeMs = now,
            label = "KEYCODE_WAKEUP"
        )
        return ProbeInputHelpers.injectKey(
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
        val controller = AndroidDeviceStateController(logger)
        val initialState = controller.captureState()
        val wm =
            ProbeReflection.resolveServiceInterface("window", "android.view.IWindowManager\$Stub")
                ?: throw UnsupportedPlatformException("IWindowManager service unavailable")
        val initialRotation = wm.javaClass.getMethod("getDefaultDisplayRotation").invoke(wm) as? Int
            ?: throw UnsupportedPlatformException("getDefaultDisplayRotation returned null")
        val targetRotation = (initialRotation + 1) % ROTATION_COUNT
        try {
            val methodName = ProbeRotationHelpers.invokeFreezeRotation(
                wm,
                targetRotation,
                platformContext.callerPackage
            )
            val afterRotation =
                wm.javaClass.getMethod("getDefaultDisplayRotation").invoke(wm) as? Int
            return "IWindowManager.$methodName " +
                "(before=$initialRotation, target=$targetRotation, after=$afterRotation)"
        } finally {
            ProbeReflection.runQuietly(logger, "restoreRotationState") {
                AndroidDeviceStateController.restoreRotationState(
                    initialState.accelerometerRotation,
                    initialState.userRotation,
                    logger
                )
            }
        }
    }

    override fun probeDisplayPower(): String {
        val wasOn = AndroidDeviceStateController(logger).captureState().displayPowerOn
        val (setModeMethod, token) = ProbeDisplayPowerHelpers.resolveHandles()
        try {
            setModeMethod.invoke(null, token, POWER_MODE_OFF)
            setModeMethod.invoke(null, token, POWER_MODE_NORMAL)
            return "${setModeMethod.declaringClass.simpleName}.setDisplayPowerMode"
        } finally {
            ProbeReflection.runQuietly(logger, "restoreDisplayPower") {
                val restoreMode = if (wasOn) POWER_MODE_NORMAL else POWER_MODE_OFF
                setModeMethod.invoke(null, token, restoreMode)
            }
        }
    }

    override fun probeClipboard(): String {
        val clipboard =
            ProbeReflection.resolveServiceInterface("clipboard", "android.content.IClipboard\$Stub")
                ?: throw UnsupportedPlatformException("IClipboard service unavailable")
        return ProbeClipboardHelpers.probeRoundTrip(clipboard, platformContext, logger)
    }

    private companion object {
        const val PROBE_MAX_WIDTH = 720
        const val MIN_DISPLAY_DIM = 64
        const val ROTATION_COUNT = 4
        const val POWER_MODE_OFF = 0
        const val POWER_MODE_NORMAL = 2
        const val WAKE_TAP_DURATION_MS = 10L
    }
}

private object ProbeInputHelpers {
    private const val INJECT_MODE_WAIT_FOR_RESULT = 1

    fun injectKey(
        keyCode: Int,
        action: Int,
        downTimeMs: Long,
        eventTimeMs: Long,
        label: String
    ): String {
        val (instance, method) = resolveInputManager()
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

    private fun resolveInputManager(): Pair<Any, Method> {
        val candidates = listOf(
            "android.hardware.input.InputManagerGlobal",
            "android.hardware.input.InputManager"
        )
        var lastError: Throwable? = null
        for (className in candidates) {
            try {
                val resolved = reflectInputManager(className)
                if (resolved != null) return resolved
            } catch (e: ReflectiveOperationException) {
                lastError = e
            } catch (e: SecurityException) {
                lastError = e
            } catch (e: LinkageError) {
                lastError = e
            }
        }
        throw UnsupportedPlatformException(
            "InputManager.injectInputEvent unavailable on API ${Build.VERSION.SDK_INT}",
            lastError
        )
    }

    private fun reflectInputManager(className: String): Pair<Any, Method>? {
        val cls = Class.forName(className)
        val instance = cls.getDeclaredMethod("getInstance")
            .apply { isAccessible = true }
            .invoke(null) ?: return null
        val method = cls.getDeclaredMethod(
            "injectInputEvent",
            InputEvent::class.java,
            Int::class.javaPrimitiveType
        ).apply { isAccessible = true }
        return instance to method
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

private object ProbeRotationHelpers {
    fun invokeFreezeRotation(wm: Any, rotation: Int, callingPackage: String): String {
        val cls = wm.javaClass
        val twoArg = ProbeReflection.optionalMethod(
            cls,
            "freezeRotation",
            Int::class.javaPrimitiveType,
            String::class.java
        )
        return if (twoArg != null) {
            twoArg.invoke(wm, rotation, callingPackage)
            "freezeRotation(int, String)"
        } else {
            val oneArg = ProbeReflection.optionalMethod(
                cls,
                "freezeRotation",
                Int::class.javaPrimitiveType
            ) ?: throw UnsupportedPlatformException("IWindowManager.freezeRotation unavailable")
            oneArg.invoke(wm, rotation)
            "freezeRotation(int)"
        }
    }
}

private object ProbeDisplayPowerHelpers {
    private const val SERVICES_JAR_PATH = "/system/framework/services.jar"

    fun resolveHandles(): Pair<Method, IBinder> {
        val surfaceControl = Class.forName("android.view.SurfaceControl")
        val displayControl = findDisplayControlClass()
        val setMode = findSetDisplayPowerMode(surfaceControl)
            ?: displayControl?.let { findSetDisplayPowerMode(it) }
            ?: throw UnsupportedPlatformException(
                "setDisplayPowerMode unavailable on API ${Build.VERSION.SDK_INT}"
            )
        val token = resolvePhysicalDisplayToken(surfaceControl)
            ?: displayControl?.let { resolvePhysicalDisplayToken(it) }
            ?: throw UnsupportedPlatformException(
                "Physical display token unavailable on API ${Build.VERSION.SDK_INT}"
            )
        return setMode to token
    }

    private fun findSetDisplayPowerMode(cls: Class<*>): Method? = try {
        cls.getDeclaredMethod(
            "setDisplayPowerMode",
            IBinder::class.java,
            Int::class.javaPrimitiveType
        ).apply { isAccessible = true }
    } catch (_: NoSuchMethodException) {
        null
    }

    private fun resolvePhysicalDisplayToken(cls: Class<*>): IBinder? {
        val legacy = ProbeReflection.optionalDeclaredMethod(cls, "getInternalDisplayToken")
        var token: IBinder? = legacy?.invoke(null) as? IBinder
        if (token == null) {
            val idsMethod = ProbeReflection.optionalDeclaredMethod(cls, "getPhysicalDisplayIds")
            val tokenMethod = ProbeReflection.optionalDeclaredMethod(
                cls,
                "getPhysicalDisplayToken",
                Long::class.javaPrimitiveType
            )
            if (idsMethod != null && tokenMethod != null) {
                val ids = idsMethod.invoke(null) as? LongArray
                if (ids != null && ids.isNotEmpty()) {
                    token = tokenMethod.invoke(null, ids[0]) as? IBinder
                }
            }
        }
        return token
    }

    private fun findDisplayControlClass(): Class<*>? {
        val eligible = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            File(SERVICES_JAR_PATH).exists()
        return if (eligible) {
            val loader = PathClassLoader(SERVICES_JAR_PATH, ClassLoader.getSystemClassLoader())
            Class.forName("com.android.server.display.DisplayControl", true, loader)
        } else {
            null
        }
    }
}

private object ProbeClipboardHelpers {
    private const val CLIPBOARD_LABEL = "velocast-probe"
    private const val DEFAULT_DEVICE_ID = 0

    fun probeRoundTrip(clipboard: Any, platformContext: PlatformContext, logger: Logger): String {
        val pkg = platformContext.callerPackage
        val tag = platformContext.callerAttributionTag
        val userId = platformContext.callerUserId
        val initialRead = tryReadPrimaryClip(clipboard, pkg, tag, userId)
        val marker = "velocast-probe-${System.nanoTime()}"
        val probeClip = ClipData.newPlainText(CLIPBOARD_LABEL, marker)
        var wroteProbe = false
        try {
            val setSignature = invokeSetPrimaryClip(clipboard, probeClip, pkg, tag, userId)
            wroteProbe = true
            verifyReadBack(clipboard, pkg, tag, userId, marker)
            return "IClipboard ($setSignature)"
        } finally {
            if (wroteProbe) {
                ProbeReflection.runQuietly(logger, "restoreClipboard") {
                    restoreClipboard(clipboard, initialRead, pkg, tag, userId)
                }
            }
        }
    }

    private fun verifyReadBack(
        clipboard: Any,
        pkg: String,
        tag: String?,
        userId: Int,
        expectedMarker: String
    ) {
        val readBack = invokeGetPrimaryClip(clipboard, pkg, tag, userId)
            ?: throw UnsupportedPlatformException(
                "IClipboard.getPrimaryClip returned null after set"
            )
        val text = if (readBack.itemCount > 0) readBack.getItemAt(0)?.text?.toString() else null
        if (text != expectedMarker) {
            throw UnsupportedPlatformException("Clipboard read-back mismatch")
        }
    }

    private fun restoreClipboard(
        clipboard: Any,
        initialRead: ClipData?,
        pkg: String,
        tag: String?,
        userId: Int
    ) {
        if (initialRead != null) {
            invokeSetPrimaryClip(clipboard, initialRead, pkg, tag, userId)
        } else {
            invokeClearPrimaryClip(clipboard, pkg, tag, userId)
        }
    }

    private fun tryReadPrimaryClip(
        clipboard: Any,
        pkg: String,
        tag: String?,
        userId: Int
    ): ClipData? = try {
        invokeGetPrimaryClip(clipboard, pkg, tag, userId)
    } catch (_: ReflectiveOperationException) {
        null
    } catch (_: SecurityException) {
        null
    } catch (_: UnsupportedPlatformException) {
        null
    }

    private fun invokeSetPrimaryClip(
        clipboard: Any,
        clip: ClipData,
        pkg: String,
        tag: String?,
        userId: Int
    ): String {
        val variants = buildVariants(pkg, tag, userId, clip)
        return invokeFirstMatching(clipboard, "setPrimaryClip", variants)?.first
            ?: throw UnsupportedPlatformException("No matching IClipboard.setPrimaryClip signature")
    }

    private fun invokeGetPrimaryClip(
        clipboard: Any,
        pkg: String,
        tag: String?,
        userId: Int
    ): ClipData? {
        val variants = buildVariants(pkg, tag, userId, null)
        val matched = invokeFirstMatching(clipboard, "getPrimaryClip", variants)
            ?: throw UnsupportedPlatformException("No matching IClipboard.getPrimaryClip signature")
        return matched.second as? ClipData
    }

    private fun invokeClearPrimaryClip(clipboard: Any, pkg: String, tag: String?, userId: Int) {
        val variants = buildVariants(pkg, tag, userId, null)
        invokeFirstMatching(clipboard, "clearPrimaryClip", variants)
    }

    private fun buildVariants(
        pkg: String,
        tag: String?,
        userId: Int,
        clip: ClipData?
    ): List<Pair<Array<Class<*>?>, Array<Any?>>> {
        val intType: Class<*>? = Int::class.javaPrimitiveType
        val strType: Class<*>? = String::class.java
        val prefixTypes: List<Class<*>?> = if (clip != null) {
            listOf(ClipData::class.java)
        } else {
            emptyList()
        }
        val prefixArgs: List<Any?> = if (clip != null) listOf(clip) else emptyList()
        val tailSpecs: List<Pair<List<Class<*>?>, List<Any?>>> = listOf(
            listOf(strType, strType, intType, intType) to
                listOf(pkg, tag, userId, DEFAULT_DEVICE_ID),
            listOf(strType, strType, intType) to listOf(pkg, tag, userId),
            listOf(strType, intType) to listOf(pkg, userId),
            listOf(strType) to listOf(pkg)
        )
        return tailSpecs.map { (tailTypes, tailArgs) ->
            (prefixTypes + tailTypes).toTypedArray() to (prefixArgs + tailArgs).toTypedArray()
        }
    }

    private fun invokeFirstMatching(
        target: Any,
        methodName: String,
        variants: List<Pair<Array<Class<*>?>, Array<Any?>>>
    ): Pair<String, Any?>? {
        var outcome: Pair<String, Any?>? = null
        val cls = target.javaClass
        for ((types, args) in variants) {
            val method = ProbeReflection.optionalMethod(cls, methodName, *types)
            if (method != null) {
                outcome = "$methodName(argCount=${types.size})" to method.invoke(target, *args)
                break
            }
        }
        return outcome
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

    fun optionalMethod(cls: Class<*>, name: String, vararg params: Class<*>?): Method? = try {
        cls.getMethod(name, *params).apply { isAccessible = true }
    } catch (_: NoSuchMethodException) {
        null
    }

    fun optionalDeclaredMethod(cls: Class<*>, name: String, vararg params: Class<*>?): Method? =
        try {
            cls.getDeclaredMethod(name, *params).apply { isAccessible = true }
        } catch (_: NoSuchMethodException) {
            null
        }

    inline fun runQuietly(logger: Logger, label: String, block: () -> Unit) {
        try {
            block()
        } catch (e: ReflectiveOperationException) {
            logger.debug(TAG, "Cleanup step $label failed", e)
        } catch (e: SecurityException) {
            logger.debug(TAG, "Cleanup step $label denied", e)
        } catch (e: IOException) {
            logger.debug(TAG, "Cleanup step $label I/O error", e)
        } catch (e: IllegalStateException) {
            logger.debug(TAG, "Cleanup step $label state error", e)
        } catch (e: UnsupportedPlatformException) {
            logger.debug(TAG, "Cleanup step $label unsupported", e)
        }
    }
}
