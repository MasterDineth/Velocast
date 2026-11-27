package com.mastersoft.velocast.server.platform

import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import com.mastersoft.velocast.protocol.ProtocolV2
import com.mastersoft.velocast.server.InjectionDeniedException
import com.mastersoft.velocast.server.InjectorSink
import com.mastersoft.velocast.server.Logger
import com.mastersoft.velocast.server.UnsupportedPlatformException
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.concurrent.TimeUnit

/**
 * Android Binder IPC implementation of [InjectorSink] with per-API-level reflection and
 * [PlatformContext] caller identity (`com.android.shell`) (SEC-04, CQ-03, L8).
 *
 * Pre-allocates pointer property and coordinate arrays once and reuses them across all touch
 * events.
 */
class BinderInjectorSink(
    private val logger: Logger,
    private val platformContext: PlatformContext = FakeContext(),
    private val sdkInt: Int = Build.VERSION.SDK_INT
) : InjectorSink {
    private val inputManager: Pair<Any, Method> =
        BinderResolvers.resolveInputManager(sdkInt, logger)
    private val statusBar: StatusBarHandles? = BinderResolvers.resolveStatusBarService(logger)
    private val windowManager: Any? = BinderResolvers.resolveWindowManagerService(logger)
    private val initialInjectionSupported: Boolean =
        BinderResolvers.probeHarmlessKeyInjection(inputManager, logger)

    override val hasInputInjection: Boolean
        get() = initialInjectionSupported

    override val hasNotificationShade: Boolean
        get() = statusBar != null || File(CMD_BIN_PATH).canExecute()

    override val hasWakeKeyInjection: Boolean
        get() = initialInjectionSupported

    private val pointerProps = Array(MAX_SLOTS) { MotionEvent.PointerProperties() }
    private val pointerCoords = Array(MAX_SLOTS) { MotionEvent.PointerCoords() }

    override fun injectTouch(
        downTimeMs: Long,
        eventTimeMs: Long,
        action: Int,
        pointerCount: Int,
        pointerIds: IntArray,
        xCoords: FloatArray,
        yCoords: FloatArray
    ) {
        if (pointerCount <= 0) return
        for (i in 0 until pointerCount) {
            pointerProps[i].id = pointerIds[i]
            pointerProps[i].toolType = MotionEvent.TOOL_TYPE_FINGER
            pointerCoords[i].x = xCoords[i]
            pointerCoords[i].y = yCoords[i]
            pointerCoords[i].pressure = 1.0f
            pointerCoords[i].size = 1.0f
        }
        val event = MotionEvent.obtain(
            downTimeMs,
            eventTimeMs,
            action,
            pointerCount,
            pointerProps,
            pointerCoords,
            0,
            0,
            1.0f,
            1.0f,
            0,
            0,
            InputDevice.SOURCE_TOUCHSCREEN,
            0
        )
        try {
            injectEvent(event)
        } finally {
            event.recycle()
        }
    }

    override fun injectKey(downTimeMs: Long, eventTimeMs: Long, action: Int, keyCode: Int) {
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
        injectEvent(event)
    }

    override fun expandNotificationShade() {
        val invoked = BinderResolvers.invokeStatusBar(
            statusBar?.service,
            statusBar?.expandMethod,
            logger
        )
        if (!invoked) {
            ShellFallback.runBounded(
                logger,
                CMD_BIN_PATH,
                "statusbar",
                "expand-notifications"
            )
        }
    }

    override fun collapseNotificationShade() {
        val invoked = BinderResolvers.invokeStatusBar(
            statusBar?.service,
            statusBar?.collapseMethod,
            logger
        )
        if (!invoked) {
            ShellFallback.runBounded(logger, CMD_BIN_PATH, "statusbar", "collapse")
        }
    }

    override fun toggleScreenRotation() {
        val wm = windowManager
        if (wm != null &&
            BinderResolvers.toggleRotation(
                wm = wm,
                callerPackage = platformContext.callerPackage,
                sdkInt = sdkInt,
                logger = logger
            )
        ) {
            return
        }
        ShellFallback.toggleRotationViaSettings(logger)
    }

    private fun injectEvent(event: InputEvent) {
        try {
            val result = inputManager.second.invoke(
                inputManager.first,
                event,
                INJECT_INPUT_EVENT_MODE_ASYNC
            )
            if (result as? Boolean == false) {
                throw InjectionDeniedException("InputManager.injectInputEvent returned false")
            }
        } catch (e: InvocationTargetException) {
            val target = e.targetException
            if (target is SecurityException) {
                logger.warn(TAG, "injectInputEvent denied", e)
                throw InjectionDeniedException(
                    target.message ?: "Input injection denied by platform",
                    e
                )
            }
            logger.warn(TAG, "injectInputEvent invocation failed", target ?: e)
        } catch (e: ReflectiveOperationException) {
            logger.warn(TAG, "injectInputEvent failed", e)
        } catch (e: SecurityException) {
            logger.warn(TAG, "injectInputEvent denied", e)
            throwDenied(e)
        }
    }

    private fun throwDenied(cause: SecurityException): Nothing = throw InjectionDeniedException(
        cause.message ?: "Input injection denied by platform",
        cause
    )

    private companion object {
        const val TAG = "BinderInjectorSink"
        const val CMD_BIN_PATH = "/system/bin/cmd"
        const val INJECT_INPUT_EVENT_MODE_ASYNC = 0
        const val MAX_SLOTS = ProtocolV2.MAX_POINTER_ID + 1
    }
}

internal data class StatusBarHandles(
    val service: Any,
    val expandMethod: Method,
    val collapseMethod: Method?
)

internal object BinderResolvers {
    private const val TAG = "BinderInjectorSink"
    private const val INJECT_INPUT_EVENT_MODE_WAIT_FOR_RESULT = 1

    fun resolveInputManager(sdkInt: Int, logger: Logger): Pair<Any, Method> {
        var lastError: Throwable? = null
        for (candidate in PlatformApiPolicy.inputManagerClasses(sdkInt)) {
            try {
                val resolved = reflectInputManager(candidate.className)
                logger.info(TAG, "Using ${candidate.className}.injectInputEvent")
                return resolved
            } catch (e: ReflectiveOperationException) {
                if (lastError == null) {
                    lastError = e
                } else {
                    e.addSuppressed(lastError)
                    lastError = e
                }
                logger.debug(TAG, "${candidate.className} unavailable on API $sdkInt", e)
            } catch (e: SecurityException) {
                lastError = e
                logger.warn(TAG, "${candidate.className} denied on API $sdkInt", e)
            } catch (e: LinkageError) {
                lastError = e
                logger.debug(TAG, "${candidate.className} linkage error on API $sdkInt", e)
            }
        }
        throw UnsupportedPlatformException(
            "Unable to bind InputManager.injectInputEvent on API $sdkInt",
            lastError
        )
    }

    fun probeHarmlessKeyInjection(inputManager: Pair<Any, Method>, logger: Logger): Boolean = try {
        val now = SystemClock.uptimeMillis()
        val event = KeyEvent(
            now,
            now,
            KeyEvent.ACTION_UP,
            KeyEvent.KEYCODE_UNKNOWN,
            0,
            0,
            KeyCharacterMap.VIRTUAL_KEYBOARD,
            0,
            0,
            InputDevice.SOURCE_KEYBOARD
        )
        val ok = inputManager.second.invoke(
            inputManager.first,
            event,
            INJECT_INPUT_EVENT_MODE_WAIT_FOR_RESULT
        ) as? Boolean
        if (ok == false) {
            logger.warn(TAG, "Input injection probe returned false (check OEM security settings)")
        }
        ok != false
    } catch (e: InvocationTargetException) {
        logger.warn(TAG, "Input injection probe denied or failed", e.targetException ?: e)
        false
    } catch (e: ReflectiveOperationException) {
        logger.warn(TAG, "Input injection probe reflection failed", e)
        false
    } catch (e: SecurityException) {
        logger.warn(TAG, "Input injection probe denied", e)
        false
    }

    fun resolveStatusBarService(logger: Logger): StatusBarHandles? = try {
        val binder = getServiceBinder("statusbar")
        if (binder == null) {
            null
        } else {
            val stubClass = Class.forName("com.android.internal.statusbar.IStatusBarService\$Stub")
            val service = stubClass.getDeclaredMethod("asInterface", IBinder::class.java)
                .apply { isAccessible = true }
                .invoke(null, binder)
            if (service == null) {
                null
            } else {
                val expand = service.javaClass.getMethod("expandNotificationsPanel")
                    .apply { isAccessible = true }
                val collapse = optionalMethod(service.javaClass, "collapsePanels", logger)
                logger.info(TAG, "Bound IStatusBarService")
                StatusBarHandles(service, expand, collapse)
            }
        }
    } catch (e: ReflectiveOperationException) {
        logger.warn(TAG, "IStatusBarService unavailable; will use cmd fallback", e)
        null
    } catch (e: SecurityException) {
        logger.warn(TAG, "IStatusBarService denied; will use cmd fallback", e)
        null
    }

    fun resolveWindowManagerService(logger: Logger): Any? = try {
        val binder = getServiceBinder("window")
        val service = if (binder != null) {
            val stubClass = Class.forName("android.view.IWindowManager\$Stub")
            stubClass.getDeclaredMethod("asInterface", IBinder::class.java)
                .apply { isAccessible = true }
                .invoke(null, binder)
        } else {
            null
        }
        if (service != null) logger.info(TAG, "Bound IWindowManager")
        service
    } catch (e: ReflectiveOperationException) {
        logger.warn(TAG, "IWindowManager unavailable; will use settings fallback", e)
        null
    } catch (e: SecurityException) {
        logger.warn(TAG, "IWindowManager denied; will use settings fallback", e)
        null
    }

    fun invokeStatusBar(service: Any?, method: Method?, logger: Logger): Boolean {
        if (service == null || method == null) return false
        return try {
            method.invoke(service)
            true
        } catch (e: ReflectiveOperationException) {
            logger.warn(TAG, "IStatusBarService binder call failed", e)
            false
        } catch (e: SecurityException) {
            logger.warn(TAG, "IStatusBarService binder call denied", e)
            false
        }
    }

    fun toggleRotation(wm: Any, callerPackage: String, sdkInt: Int, logger: Logger): Boolean = try {
        val getRot = wm.javaClass.getMethod("getDefaultDisplayRotation")
        val current = (getRot.invoke(wm) as? Int) ?: 0
        val next = if (current == 0 || current == 2) 1 else 0
        val invoked = WindowManagerRotationHelper.freezeRotation(wm, next, callerPackage, sdkInt)
        if (invoked != null) {
            logger.info(TAG, "Toggled rotation via IWindowManager.$invoked: $current -> $next")
        }
        invoked != null
    } catch (e: ReflectiveOperationException) {
        logger.warn(TAG, "IWindowManager rotation failed", e)
        false
    } catch (e: SecurityException) {
        logger.warn(TAG, "IWindowManager rotation denied", e)
        false
    }

    private fun reflectInputManager(className: String): Pair<Any, Method> {
        val cls = Class.forName(className)
        val getInst = cls.getDeclaredMethod("getInstance").apply { isAccessible = true }
        val instance = getInst.invoke(null)
            ?: throw UnsupportedPlatformException("$className.getInstance() returned null")
        val method = cls.getDeclaredMethod(
            "injectInputEvent",
            InputEvent::class.java,
            Int::class.javaPrimitiveType
        ).apply { isAccessible = true }
        return Pair(instance, method)
    }

    private fun getServiceBinder(name: String): IBinder? {
        val smClass = Class.forName("android.os.ServiceManager")
        return smClass.getDeclaredMethod("getService", String::class.java)
            .apply { isAccessible = true }
            .invoke(null, name) as? IBinder
    }

    private fun optionalMethod(cls: Class<*>, name: String, logger: Logger): Method? = try {
        cls.getMethod(name).apply { isAccessible = true }
    } catch (e: NoSuchMethodException) {
        logger.debug(TAG, "Method $name not exposed on ${cls.name}", e)
        null
    }
}

internal object WindowManagerRotationHelper {
    fun freezeRotation(wm: Any, rotation: Int, callerPackage: String, sdkInt: Int): String? {
        val cls = wm.javaClass
        var methodUsed: String? = null
        for (strategy in PlatformApiPolicy.rotationFreezeStrategies(sdkInt)) {
            if (methodUsed != null) break
            when (strategy) {
                PlatformApiPolicy.RotationFreezeStrategy.WITH_CALLER_PACKAGE -> {
                    val m = optionalMethod(
                        cls,
                        "freezeRotation",
                        Int::class.javaPrimitiveType,
                        String::class.java
                    )
                    if (m != null) {
                        m.invoke(wm, rotation, callerPackage)
                        methodUsed = "freezeRotation(int, String)"
                    }
                }

                PlatformApiPolicy.RotationFreezeStrategy.WITHOUT_CALLER_PACKAGE -> {
                    val m = optionalMethod(cls, "freezeRotation", Int::class.javaPrimitiveType)
                    if (m != null) {
                        m.invoke(wm, rotation)
                        methodUsed = "freezeRotation(int)"
                    }
                }
            }
        }
        return methodUsed
    }

    fun thawRotation(wm: Any, callerPackage: String, sdkInt: Int): String? {
        val cls = wm.javaClass
        var methodUsed: String? = null
        for (strategy in PlatformApiPolicy.rotationFreezeStrategies(sdkInt)) {
            if (methodUsed != null) break
            when (strategy) {
                PlatformApiPolicy.RotationFreezeStrategy.WITH_CALLER_PACKAGE -> {
                    val m = optionalMethod(cls, "thawRotation", String::class.java)
                    if (m != null) {
                        m.invoke(wm, callerPackage)
                        methodUsed = "thawRotation(String)"
                    }
                }

                PlatformApiPolicy.RotationFreezeStrategy.WITHOUT_CALLER_PACKAGE -> {
                    val m = optionalMethod(cls, "thawRotation")
                    if (m != null) {
                        m.invoke(wm)
                        methodUsed = "thawRotation()"
                    }
                }
            }
        }
        return methodUsed
    }

    private fun optionalMethod(cls: Class<*>, name: String, vararg params: Class<*>?): Method? =
        try {
            cls.getMethod(name, *params).apply { isAccessible = true }
        } catch (_: NoSuchMethodException) {
            null
        }
}

private object ShellFallback {
    private const val TAG = "BinderInjectorSink"
    private const val COMMAND_TIMEOUT_MS = 1_500L

    fun toggleRotationViaSettings(logger: Logger) {
        try {
            val proc = Runtime.getRuntime().exec(
                arrayOf("/system/bin/settings", "get", "system", "user_rotation")
            )
            val current = BufferedReader(InputStreamReader(proc.inputStream)).use { reader ->
                reader.readLine()?.trim()?.toIntOrNull() ?: 0
            }
            if (!proc.waitFor(COMMAND_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                proc.destroyForcibly()
            }
            val next = if (current == 0 || current == 2) 1 else 0
            runBounded(
                logger,
                "/system/bin/settings",
                "put",
                "system",
                "accelerometer_rotation",
                "0"
            )
            runBounded(
                logger,
                "/system/bin/settings",
                "put",
                "system",
                "user_rotation",
                next.toString()
            )
        } catch (e: IOException) {
            logger.warn(TAG, "settings rotation fallback failed", e)
        } catch (e: SecurityException) {
            logger.warn(TAG, "settings rotation fallback denied", e)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            logger.warn(TAG, "settings rotation fallback interrupted", e)
        }
    }

    fun runBounded(logger: Logger, vararg cmd: String): Boolean = try {
        val proc = Runtime.getRuntime().exec(cmd)
        val finished = proc.waitFor(COMMAND_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        if (!finished) {
            proc.destroyForcibly()
            logger.warn(TAG, "Command timed out: ${cmd.firstOrNull()}")
            false
        } else {
            proc.exitValue() == 0
        }
    } catch (e: IOException) {
        logger.warn(TAG, "Command failed: ${cmd.firstOrNull()}", e)
        false
    } catch (e: SecurityException) {
        logger.warn(TAG, "Command denied: ${cmd.firstOrNull()}", e)
        false
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        logger.warn(TAG, "Command interrupted: ${cmd.firstOrNull()}", e)
        false
    }
}
