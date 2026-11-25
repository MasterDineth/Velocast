// TODO(S03): Refactor daemon classes and package structure in S03.
package com.mastersoft.velocast.daemon

import android.graphics.PointF
import android.os.IBinder
import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import java.io.BufferedReader
import java.io.InputStreamReader
import java.lang.reflect.Method
import java.util.concurrent.TimeUnit

/**
 * Reconstructs multi-touch MotionEvents and executes system actions (Power, Volume, Back,
 * Notification Shade, Screen Rotation) directly via Android Binder IPC to eliminate
 * shell subprocess overhead and ensure Android 16 compatibility.
 */
class InputInjector(@Volatile var displayWidth: Int, @Volatile var displayHeight: Int) {
    companion object {
        private const val INJECT_INPUT_EVENT_MODE_ASYNC = 0
        private const val MAX_POINTERS = 10
    }

    private val inputManagerInstance: Any
    private val injectInputEventMethod: Method

    private var statusBarService: Any? = null
    private var expandNotificationsMethod: Method? = null
    private var windowManagerService: Any? = null

    private val activePointers = LinkedHashMap<Int, PointF>()
    private var downTimeMs: Long = 0L

    init {
        val resolved = resolveInputManager()
        inputManagerInstance = resolved.first
        injectInputEventMethod = resolved.second
        resolveStatusBarService()
        resolveWindowManagerService()
    }

    private fun resolveInputManager(): Pair<Any, Method> {
        try {
            val globalClass = Class.forName("android.hardware.input.InputManagerGlobal")
            val getInst = globalClass.getDeclaredMethod("getInstance").apply { isAccessible = true }
            val instance = getInst.invoke(null)
            val method = globalClass.getDeclaredMethod(
                "injectInputEvent",
                InputEvent::class.java,
                Int::class.javaPrimitiveType
            ).apply { isAccessible = true }
            println("[InputInjector] Using InputManagerGlobal.injectInputEvent")
            return Pair(instance!!, method)
        } catch (t: Throwable) {
            System.err.println(
                "[InputInjector] InputManagerGlobal unavailable (${t.message}); trying InputManager"
            )
        }

        val imClass = Class.forName("android.hardware.input.InputManager")
        val getInst = imClass.getDeclaredMethod("getInstance").apply { isAccessible = true }
        val instance = getInst.invoke(null)
        val method = imClass.getDeclaredMethod(
            "injectInputEvent",
            InputEvent::class.java,
            Int::class.javaPrimitiveType
        ).apply { isAccessible = true }
        println("[InputInjector] Using InputManager.injectInputEvent")
        return Pair(instance!!, method)
    }

    private fun resolveStatusBarService() {
        try {
            val smClass = Class.forName("android.os.ServiceManager")
            val binder = smClass.getDeclaredMethod("getService", String::class.java)
                .apply { isAccessible = true }
                .invoke(null, "statusbar") as? IBinder ?: return

            val stubClass = Class.forName("com.android.internal.statusbar.IStatusBarService\$Stub")
            val service = stubClass.getDeclaredMethod("asInterface", IBinder::class.java)
                .apply { isAccessible = true }
                .invoke(null, binder) ?: return

            val expand = service.javaClass.getMethod("expandNotificationsPanel").apply {
                isAccessible =
                    true
            }
            statusBarService = service
            expandNotificationsMethod = expand
            println("[InputInjector] Bound IStatusBarService.expandNotificationsPanel")
        } catch (t: Throwable) {
            System.err.println(
                "[InputInjector] IStatusBarService binder unavailable (${t.message}); will use cmd fallback"
            )
        }
    }

    private fun resolveWindowManagerService() {
        try {
            val smClass = Class.forName("android.os.ServiceManager")
            val binder = smClass.getDeclaredMethod("getService", String::class.java)
                .apply { isAccessible = true }
                .invoke(null, "window") as? IBinder ?: return

            val stubClass = Class.forName("android.view.IWindowManager\$Stub")
            val service = stubClass.getDeclaredMethod("asInterface", IBinder::class.java)
                .apply { isAccessible = true }
                .invoke(null, binder) ?: return

            windowManagerService = service
            println("[InputInjector] Bound IWindowManager for zero-fork rotation")
        } catch (t: Throwable) {
            System.err.println(
                "[InputInjector] IWindowManager binder unavailable (${t.message}); will use settings fallback"
            )
        }
    }

    @Synchronized
    fun handleTouchPacket(packet: Protocol.ControlPacket) {
        val x = (packet.normX.coerceIn(0f, 1f)) * displayWidth
        val y = (packet.normY.coerceIn(0f, 1f)) * displayHeight
        val pointerId = packet.param
        val now = SystemClock.uptimeMillis()

        when (packet.action) {
            Protocol.ACTION_DOWN -> {
                activePointers.clear()
                downTimeMs = now
                activePointers[pointerId] = PointF(x, y)
                injectCurrentMotion(MotionEvent.ACTION_DOWN, now)
            }

            Protocol.ACTION_POINTER_DOWN -> {
                if (activePointers.isEmpty()) {
                    downTimeMs = now
                    activePointers[pointerId] = PointF(x, y)
                    injectCurrentMotion(MotionEvent.ACTION_DOWN, now)
                    return
                }
                if (activePointers.size < MAX_POINTERS) {
                    activePointers[pointerId] = PointF(x, y)
                    val index = indexOfPointer(pointerId)
                    if (index >= 0) {
                        val action = MotionEvent.ACTION_POINTER_DOWN or
                            (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
                        injectCurrentMotion(action, now)
                    }
                }
            }

            Protocol.ACTION_MOVE -> {
                if (activePointers.isEmpty()) {
                    downTimeMs = now
                    activePointers[pointerId] = PointF(x, y)
                    injectCurrentMotion(MotionEvent.ACTION_DOWN, now)
                } else {
                    activePointers[pointerId] = PointF(x, y)
                    injectCurrentMotion(MotionEvent.ACTION_MOVE, now)
                }
            }

            Protocol.ACTION_POINTER_UP -> {
                if (activePointers.containsKey(pointerId)) {
                    activePointers[pointerId] = PointF(x, y)
                    val index = indexOfPointer(pointerId)
                    val action = if (activePointers.size == 1) {
                        MotionEvent.ACTION_UP
                    } else {
                        MotionEvent.ACTION_POINTER_UP or
                            (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
                    }
                    injectCurrentMotion(action, now)
                    activePointers.remove(pointerId)
                }
            }

            Protocol.ACTION_UP -> {
                if (activePointers.isEmpty()) return
                activePointers[pointerId] = PointF(x, y)
                if (activePointers.size > 1) {
                    val index = indexOfPointer(pointerId)
                    val action = MotionEvent.ACTION_POINTER_UP or
                        (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
                    injectCurrentMotion(action, now)
                    activePointers.remove(pointerId)
                } else {
                    injectCurrentMotion(MotionEvent.ACTION_UP, now)
                    activePointers.clear()
                }
            }

            Protocol.ACTION_CANCEL -> {
                if (activePointers.isNotEmpty()) {
                    injectCurrentMotion(MotionEvent.ACTION_CANCEL, now)
                    activePointers.clear()
                }
            }
        }
    }

    @Synchronized
    fun injectKeyPress(keyCode: Int) {
        val now = SystemClock.uptimeMillis()
        val downEvent = KeyEvent(
            now, now, KeyEvent.ACTION_DOWN, keyCode, 0, 0,
            KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD
        )
        val upEvent = KeyEvent(
            now, now + 10L, KeyEvent.ACTION_UP, keyCode, 0, 0,
            KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD
        )
        injectEvent(downEvent)
        injectEvent(upEvent)
    }

    /**
     * Pulls down the host notification shade via IStatusBarService binder IPC (primary)
     * with bounded-timeout /system/bin/cmd fallback.
     */
    fun expandNotificationShade() {
        val service = statusBarService
        val method = expandNotificationsMethod
        if (service != null && method != null) {
            try {
                method.invoke(service)
                return
            } catch (t: Throwable) {
                System.err.println(
                    "[InputInjector] Binder expandNotificationsPanel failed: ${t.cause?.message ?: t.message}"
                )
            }
        }

        // Fallback: absolute binary paths with strict 1500ms timeout
        if (!runCommandBounded("/system/bin/cmd", "statusbar", "expand-notifications")) {
            runCommandBounded("/system/bin/service", "call", "statusbar", "1")
        }
    }

    /**
     * Toggles portrait/landscape via IWindowManager binder IPC (primary) with settings fallback.
     */
    fun toggleScreenRotation() {
        val wm = windowManagerService
        if (wm != null) {
            try {
                val getRot = wm.javaClass.getMethod("getDefaultDisplayRotation")
                val current = (getRot.invoke(wm) as? Int) ?: 0
                val next = if (current == 0 || current == 2) 1 else 0

                // Android 14–16 freezeDisplayRotation(int displayId, int rotation, String caller) or freezeRotation(int)
                val invoked = tryInvokeFreezeRotation(wm, next)
                if (invoked) {
                    println(
                        "[InputInjector] Toggled rotation via IWindowManager: $current -> $next"
                    )
                    return
                }
            } catch (t: Throwable) {
                System.err.println(
                    "[InputInjector] IWindowManager rotation failed: ${t.cause?.message ?: t.message}"
                )
            }
        }

        try {
            val proc = Runtime.getRuntime().exec(
                arrayOf("/system/bin/settings", "get", "system", "user_rotation")
            )
            val current =
                BufferedReader(
                    InputStreamReader(proc.inputStream)
                ).readLine()?.trim()?.toIntOrNull()
                    ?: 0
            proc.waitFor(1500L, TimeUnit.MILLISECONDS)
            val next = if (current == 0 || current == 2) 1 else 0
            runCommandBounded(
                "/system/bin/settings",
                "put",
                "system",
                "accelerometer_rotation",
                "0"
            )
            runCommandBounded(
                "/system/bin/settings",
                "put",
                "system",
                "user_rotation",
                next.toString()
            )
        } catch (t: Throwable) {
            System.err.println("[InputInjector] settings rotation fallback failed: ${t.message}")
        }
    }

    private fun tryInvokeFreezeRotation(wm: Any, rotation: Int): Boolean {
        val cls = wm.javaClass
        try {
            cls.getMethod("freezeRotation", Int::class.javaPrimitiveType, String::class.java)
                .invoke(wm, rotation, "velocast")
            return true
        } catch (_: Throwable) {}
        try {
            cls.getMethod("freezeRotation", Int::class.javaPrimitiveType)
                .invoke(wm, rotation)
            return true
        } catch (_: Throwable) {}
        return false
    }

    private fun runCommandBounded(vararg cmd: String): Boolean = try {
        val proc = Runtime.getRuntime().exec(cmd)
        val finished = proc.waitFor(1500L, TimeUnit.MILLISECONDS)
        if (!finished) {
            proc.destroyForcibly()
            false
        } else {
            proc.exitValue() == 0
        }
    } catch (t: Throwable) {
        System.err.println("[InputInjector] Command ${cmd.firstOrNull()} failed: ${t.message}")
        false
    }

    @Synchronized
    fun resetPointers() {
        if (activePointers.isNotEmpty()) {
            injectCurrentMotion(MotionEvent.ACTION_CANCEL, SystemClock.uptimeMillis())
            activePointers.clear()
        }
    }

    private fun indexOfPointer(pointerId: Int): Int {
        var i = 0
        for (id in activePointers.keys) {
            if (id == pointerId) return i
            i++
        }
        return -1
    }

    private fun injectCurrentMotion(action: Int, eventTime: Long) {
        val count = activePointers.size
        if (count == 0) return

        val props = Array(count) { MotionEvent.PointerProperties() }
        val coords = Array(count) { MotionEvent.PointerCoords() }

        var idx = 0
        for ((id, point) in activePointers) {
            props[idx].id = id
            props[idx].toolType = MotionEvent.TOOL_TYPE_FINGER
            coords[idx].x = point.x
            coords[idx].y = point.y
            coords[idx].pressure = 1.0f
            coords[idx].size = 1.0f
            idx++
        }

        val event = MotionEvent.obtain(
            downTimeMs, eventTime, action, count, props, coords,
            0, 0, 1.0f, 1.0f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0
        )
        try {
            injectEvent(event)
        } finally {
            event.recycle()
        }
    }

    private fun injectEvent(event: InputEvent) {
        try {
            injectInputEventMethod.invoke(
                inputManagerInstance,
                event,
                INJECT_INPUT_EVENT_MODE_ASYNC
            )
        } catch (t: Throwable) {
            System.err.println(
                "[InputInjector] injectInputEvent failed: ${t.cause?.message ?: t.message}"
            )
        }
    }
}
