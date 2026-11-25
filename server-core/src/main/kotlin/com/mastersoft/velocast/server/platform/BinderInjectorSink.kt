package com.mastersoft.velocast.server.platform

import android.os.IBinder
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
import java.io.IOException
import java.io.InputStreamReader
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.concurrent.TimeUnit

/**
 * Android Binder IPC implementation of [InjectorSink]. Pre-allocates pointer property and
 * coordinate arrays once and reuses them across all touch events.
 */
class BinderInjectorSink(private val logger: Logger) : InjectorSink {
    private val inputManager: Pair<Any, Method> = BinderResolvers.resolveInputManager(logger)
    private val statusBar: StatusBarHandles? = BinderResolvers.resolveStatusBarService(logger)
    private val windowManager: Any? = BinderResolvers.resolveWindowManagerService(logger)

    override val hasNotificationShade: Boolean
        get() = statusBar != null

    override val hasWakeKeyInjection: Boolean = true

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
        if (!invoked &&
            !ShellFallback.runBounded(
                logger,
                "/system/bin/cmd",
                "statusbar",
                "expand-notifications"
            )
        ) {
            ShellFallback.runBounded(logger, "/system/bin/service", "call", "statusbar", "1")
        }
    }

    override fun collapseNotificationShade() {
        val invoked = BinderResolvers.invokeStatusBar(
            statusBar?.service,
            statusBar?.collapseMethod,
            logger
        )
        if (!invoked &&
            !ShellFallback.runBounded(logger, "/system/bin/cmd", "statusbar", "collapse")
        ) {
            ShellFallback.runBounded(logger, "/system/bin/service", "call", "statusbar", "2")
        }
    }

    override fun toggleScreenRotation() {
        val wm = windowManager
        if (wm != null && BinderResolvers.toggleRotation(wm, logger)) {
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
        const val INJECT_INPUT_EVENT_MODE_ASYNC = 0
        const val MAX_SLOTS = ProtocolV2.MAX_POINTER_ID + 1
    }
}

private data class StatusBarHandles(
    val service: Any,
    val expandMethod: Method,
    val collapseMethod: Method?
)

private object BinderResolvers {
    private const val TAG = "BinderInjectorSink"

    fun resolveInputManager(logger: Logger): Pair<Any, Method> {
        var globalError: Throwable
        try {
            val resolved = reflectInputManager("android.hardware.input.InputManagerGlobal")
            logger.info(TAG, "Using InputManagerGlobal.injectInputEvent")
            return resolved
        } catch (e: ReflectiveOperationException) {
            globalError = e
            logger.warn(TAG, "InputManagerGlobal unavailable; trying InputManager", e)
        } catch (e: SecurityException) {
            globalError = e
            logger.warn(TAG, "InputManagerGlobal denied; trying InputManager", e)
        }

        return try {
            val resolved = reflectInputManager("android.hardware.input.InputManager")
            logger.info(TAG, "Using InputManager.injectInputEvent")
            resolved
        } catch (e: ReflectiveOperationException) {
            e.addSuppressed(globalError)
            throw UnsupportedPlatformException("Unable to bind InputManager.injectInputEvent", e)
        }
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

    fun toggleRotation(wm: Any, logger: Logger): Boolean = try {
        val getRot = wm.javaClass.getMethod("getDefaultDisplayRotation")
        val current = (getRot.invoke(wm) as? Int) ?: 0
        val next = if (current == 0 || current == 2) 1 else 0
        val invoked = tryInvokeFreezeRotation(wm, next, logger)
        if (invoked) {
            logger.info(TAG, "Toggled rotation via IWindowManager: $current -> $next")
        }
        invoked
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

    private fun tryInvokeFreezeRotation(wm: Any, rotation: Int, logger: Logger): Boolean {
        val cls = wm.javaClass
        var invoked = false
        val withCaller = try {
            cls.getMethod("freezeRotation", Int::class.javaPrimitiveType, String::class.java)
        } catch (e: NoSuchMethodException) {
            logger.debug(TAG, "freezeRotation(int, String) absent", e)
            null
        }
        if (withCaller != null) {
            withCaller.invoke(wm, rotation, "velocast")
            invoked = true
        } else {
            val legacy = try {
                cls.getMethod("freezeRotation", Int::class.javaPrimitiveType)
            } catch (e: NoSuchMethodException) {
                logger.debug(TAG, "freezeRotation(int) absent", e)
                null
            }
            if (legacy != null) {
                legacy.invoke(wm, rotation)
                invoked = true
            }
        }
        return invoked
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
