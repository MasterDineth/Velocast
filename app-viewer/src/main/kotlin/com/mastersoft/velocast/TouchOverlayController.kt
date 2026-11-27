package com.mastersoft.velocast

import android.annotation.SuppressLint
import android.view.Gravity
import android.view.MotionEvent
import android.view.SurfaceView
import android.view.View
import android.widget.FrameLayout
import com.mastersoft.velocast.client.TouchPipeline
import com.mastersoft.velocast.client.ViewportMapper

/**
 * Coordinates letterbox layout on the mirror [SurfaceView] and routes raw Android [MotionEvent]s
 * through [ViewportMapper] and [TouchPipeline].
 */
class TouchOverlayController(
    private val container: FrameLayout,
    private val surfaceView: SurfaceView,
    private val touchPipeline: TouchPipeline,
    private val onTouchActivity: () -> Unit = {}
) : View.OnTouchListener {

    private val viewportMapper: ViewportMapper
        get() = touchPipeline.viewportMapper

    init {
        container.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            recomputeViewport()
        }
        container.setOnTouchListener(this)
    }

    fun updateVideoSize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        viewportMapper.updateStreamDimensions(width, height)
        recomputeViewport()
    }

    fun recomputeViewport() {
        val containerW = container.width
        val containerH = container.height
        if (containerW <= 0 || containerH <= 0) return

        val rect = viewportMapper.updateContainerSize(containerW, containerH)
        val targetW = rect.width.toInt().coerceAtLeast(1)
        val targetH = rect.height.toInt().coerceAtLeast(1)

        val params = surfaceView.layoutParams as? FrameLayout.LayoutParams
            ?: FrameLayout.LayoutParams(targetW, targetH, Gravity.CENTER)

        if (params.width != targetW || params.height != targetH) {
            params.width = targetW
            params.height = targetH
            params.gravity = Gravity.CENTER
            surfaceView.layoutParams = params
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouch(v: View, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val index = event.actionIndex
                val accepted = touchPipeline.onRawDown(
                    event.getPointerId(index),
                    event.getX(index),
                    event.getY(index)
                )
                if (!accepted) return false
                onTouchActivity()
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                val index = event.actionIndex
                if (touchPipeline.onRawDown(
                        event.getPointerId(index),
                        event.getX(index),
                        event.getY(index)
                    )
                ) {
                    onTouchActivity()
                }
            }

            MotionEvent.ACTION_MOVE -> {
                val pointerCount = event.pointerCount
                for (i in 0 until pointerCount) {
                    touchPipeline.onRawMove(
                        event.getPointerId(i),
                        event.getX(i),
                        event.getY(i)
                    )
                }
                onTouchActivity()
            }

            MotionEvent.ACTION_POINTER_UP,
            MotionEvent.ACTION_UP -> {
                val index = event.actionIndex
                touchPipeline.onRawUp(
                    event.getPointerId(index),
                    event.getX(index),
                    event.getY(index)
                )
                onTouchActivity()
            }

            MotionEvent.ACTION_CANCEL -> {
                touchPipeline.cancelAllPointers()
                onTouchActivity()
            }
        }
        return true
    }
}
