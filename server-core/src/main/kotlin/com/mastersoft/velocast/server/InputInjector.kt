package com.mastersoft.velocast.server

import android.view.KeyEvent
import android.view.MotionEvent
import com.mastersoft.velocast.protocol.DeviceAction
import com.mastersoft.velocast.protocol.KeyAction
import com.mastersoft.velocast.protocol.KeyCodes
import com.mastersoft.velocast.protocol.PointerStateTracker
import com.mastersoft.velocast.protocol.ProtocolV2
import com.mastersoft.velocast.protocol.TouchAction
import com.mastersoft.velocast.protocol.Validators

/**
 * Thrown when the platform refuses an input injection request (for example Xiaomi
 * "USB debugging (Security settings)" is disabled). Reported to the viewer as `INJECTION_DENIED`.
 */
class InjectionDeniedException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

/**
 * Target sink for translated touch, key, and device actions. Isolated from Android framework
 * constructors so [InputInjector] is testable on the JVM without Android instrumentation.
 */
interface InjectorSink {
    val hasNotificationShade: Boolean
        get() = true

    val hasWakeKeyInjection: Boolean
        get() = true

    fun injectTouch(
        downTimeMs: Long,
        eventTimeMs: Long,
        action: Int,
        pointerCount: Int,
        pointerIds: IntArray,
        xCoords: FloatArray,
        yCoords: FloatArray
    )

    fun injectKey(downTimeMs: Long, eventTimeMs: Long, action: Int, keyCode: Int)

    fun expandNotificationShade()

    fun collapseNotificationShade()

    fun toggleScreenRotation()
}

/**
 * Strict multi-touch, key, and device-action injector (SEC-05, FUN-05, PERF-03).
 *
 * - Rejects `UP` or `MOVE` for unknown pointers and duplicate `DOWN` via [PointerStateTracker].
 * - Cancels all active pointers on `CANCEL`, `PAUSE`, and session teardown.
 * - Pre-allocates fixed 32-slot primitive arrays so hot-path touch handling never allocates.
 */
class InputInjector(
    displayWidth: Int,
    displayHeight: Int,
    private val sink: InjectorSink,
    private val clock: ServerClock,
    private val logger: Logger
) {
    private val lock = Any()

    @Volatile
    var displayWidth: Int = Validators.dimension(displayWidth)
        private set

    @Volatile
    var displayHeight: Int = Validators.dimension(displayHeight)
        private set

    private val slotIds = IntArray(MAX_SLOTS)
    private val slotX = FloatArray(MAX_SLOTS)
    private val slotY = FloatArray(MAX_SLOTS)
    private val pointerState = PointerStateTracker()
    private var activeCount = 0
    private var downTimeMs = 0L

    val activePointerCount: Int
        get() = synchronized(lock) { activeCount }

    val hasNotificationShade: Boolean
        get() = sink.hasNotificationShade

    val hasWakeKeyInjection: Boolean
        get() = sink.hasWakeKeyInjection

    fun isPointerDown(pointerId: Int): Boolean = synchronized(lock) {
        Validators.pointerId(pointerId)
        pointerState.isDown(pointerId)
    }

    fun updateDisplaySize(width: Int, height: Int) {
        synchronized(lock) {
            displayWidth = Validators.dimension(width)
            displayHeight = Validators.dimension(height)
        }
    }

    fun handleTouch(pointerId: Int, action: TouchAction, normX: Float, normY: Float) {
        Validators.pointerId(pointerId)
        Validators.coordinate(normX)
        Validators.coordinate(normY)

        synchronized(lock) {
            val now = clock.uptimeMillis()
            val px = normX * displayWidth
            val py = normY * displayHeight
            pointerState.onTouch(pointerId, action)

            when (action) {
                TouchAction.DOWN -> onPointerDownLocked(pointerId, px, py, now)
                TouchAction.MOVE -> onPointerMoveLocked(pointerId, px, py, now)
                TouchAction.UP -> onPointerUpLocked(pointerId, px, py, now)
                TouchAction.CANCEL -> cancelAllLocked(now)
            }
        }
    }

    fun injectKey(keyCode: Int, action: KeyAction) {
        KeyCodes.require(keyCode)
        val now = clock.uptimeMillis()
        val androidAction = if (action == KeyAction.DOWN) {
            KeyEvent.ACTION_DOWN
        } else {
            KeyEvent.ACTION_UP
        }
        sink.injectKey(now, now, androidAction, keyCode)
    }

    fun performAction(action: DeviceAction) {
        when (action) {
            DeviceAction.EXPAND_SHADE -> sink.expandNotificationShade()

            DeviceAction.COLLAPSE_SHADE -> sink.collapseNotificationShade()

            DeviceAction.ROTATE -> sink.toggleScreenRotation()

            DeviceAction.WAKE -> {
                val now = clock.uptimeMillis()
                sink.injectKey(now, now, KeyEvent.ACTION_DOWN, KeyCodes.WAKEUP)
                sink.injectKey(now, now + KEY_TAP_DURATION_MS, KeyEvent.ACTION_UP, KeyCodes.WAKEUP)
            }

            DeviceAction.BACK -> {
                val now = clock.uptimeMillis()
                sink.injectKey(now, now, KeyEvent.ACTION_DOWN, KeyCodes.BACK)
                sink.injectKey(now, now + KEY_TAP_DURATION_MS, KeyEvent.ACTION_UP, KeyCodes.BACK)
            }

            DeviceAction.HOME -> {
                val now = clock.uptimeMillis()
                sink.injectKey(now, now, KeyEvent.ACTION_DOWN, KeyCodes.HOME)
                sink.injectKey(now, now + KEY_TAP_DURATION_MS, KeyEvent.ACTION_UP, KeyCodes.HOME)
            }

            DeviceAction.RECENTS -> {
                val now = clock.uptimeMillis()
                sink.injectKey(now, now, KeyEvent.ACTION_DOWN, KeyCodes.APP_SWITCH)
                sink.injectKey(
                    now,
                    now + KEY_TAP_DURATION_MS,
                    KeyEvent.ACTION_UP,
                    KeyCodes.APP_SWITCH
                )
            }

            DeviceAction.DISPLAY_POWER_ON,
            DeviceAction.DISPLAY_POWER_OFF -> {
                logger.info(
                    TAG,
                    "Device action $action deferred to display power controller (S03B)"
                )
            }
        }
    }

    fun cancelAllPointers() {
        synchronized(lock) {
            pointerState.reset()
            cancelAllLocked(clock.uptimeMillis())
        }
    }

    private fun onPointerDownLocked(pointerId: Int, px: Float, py: Float, now: Long) {
        val index = activeCount
        slotIds[index] = pointerId
        slotX[index] = px
        slotY[index] = py
        activeCount = index + 1
        if (index == 0) {
            downTimeMs = now
            sink.injectTouch(
                downTimeMs = downTimeMs,
                eventTimeMs = now,
                action = MotionEvent.ACTION_DOWN,
                pointerCount = 1,
                pointerIds = slotIds,
                xCoords = slotX,
                yCoords = slotY
            )
        } else {
            val action = MotionEvent.ACTION_POINTER_DOWN or
                (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
            sink.injectTouch(
                downTimeMs = downTimeMs,
                eventTimeMs = now,
                action = action,
                pointerCount = activeCount,
                pointerIds = slotIds,
                xCoords = slotX,
                yCoords = slotY
            )
        }
    }

    private fun onPointerMoveLocked(pointerId: Int, px: Float, py: Float, now: Long) {
        val index = indexOfSlotLocked(pointerId)
        check(index >= 0) { "PointerStateTracker inconsistency for MOVE on pointer $pointerId" }
        slotX[index] = px
        slotY[index] = py
        sink.injectTouch(
            downTimeMs = downTimeMs,
            eventTimeMs = now,
            action = MotionEvent.ACTION_MOVE,
            pointerCount = activeCount,
            pointerIds = slotIds,
            xCoords = slotX,
            yCoords = slotY
        )
    }

    private fun onPointerUpLocked(pointerId: Int, px: Float, py: Float, now: Long) {
        val index = indexOfSlotLocked(pointerId)
        check(index >= 0) { "PointerStateTracker inconsistency for UP on pointer $pointerId" }
        slotX[index] = px
        slotY[index] = py
        if (activeCount == 1) {
            try {
                sink.injectTouch(
                    downTimeMs = downTimeMs,
                    eventTimeMs = now,
                    action = MotionEvent.ACTION_UP,
                    pointerCount = 1,
                    pointerIds = slotIds,
                    xCoords = slotX,
                    yCoords = slotY
                )
            } finally {
                activeCount = 0
            }
        } else {
            val action = MotionEvent.ACTION_POINTER_UP or
                (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
            try {
                sink.injectTouch(
                    downTimeMs = downTimeMs,
                    eventTimeMs = now,
                    action = action,
                    pointerCount = activeCount,
                    pointerIds = slotIds,
                    xCoords = slotX,
                    yCoords = slotY
                )
            } finally {
                val tail = activeCount - 1 - index
                if (tail > 0) {
                    System.arraycopy(slotIds, index + 1, slotIds, index, tail)
                    System.arraycopy(slotX, index + 1, slotX, index, tail)
                    System.arraycopy(slotY, index + 1, slotY, index, tail)
                }
                activeCount--
            }
        }
    }

    private fun cancelAllLocked(now: Long) {
        if (activeCount > 0) {
            try {
                sink.injectTouch(
                    downTimeMs = downTimeMs,
                    eventTimeMs = now,
                    action = MotionEvent.ACTION_CANCEL,
                    pointerCount = activeCount,
                    pointerIds = slotIds,
                    xCoords = slotX,
                    yCoords = slotY
                )
            } finally {
                activeCount = 0
            }
        }
    }

    private fun indexOfSlotLocked(pointerId: Int): Int {
        for (i in 0 until activeCount) {
            if (slotIds[i] == pointerId) return i
        }
        return -1
    }

    companion object {
        private const val TAG = "InputInjector"
        private const val MAX_SLOTS = ProtocolV2.MAX_POINTER_ID + 1
        private const val KEY_TAP_DURATION_MS = 10L
    }
}
