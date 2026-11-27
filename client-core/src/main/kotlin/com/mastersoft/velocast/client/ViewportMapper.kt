package com.mastersoft.velocast.client

import com.mastersoft.velocast.protocol.VideoSize
import kotlin.math.min

/** Rendered video rectangle inside the viewer container in container pixels. */
data class LetterboxRect(val left: Float, val top: Float, val width: Float, val height: Float) {
    val right: Float get() = left + width
    val bottom: Float get() = top + height

    fun contains(x: Float, y: Float): Boolean =
        x.isFinite() && y.isFinite() && width > 0f && height > 0f &&
            x >= left && x <= right && y >= top && y <= bottom
}

/** Normalized `[0.0..1.0]` display coordinates matching protocol v2 `TOUCH` payloads. */
data class NormalizedPoint(val x: Float, val y: Float)

/**
 * Computes the centered aspect-preserving [LetterboxRect] from `VIDEO_SIZE` messages and container
 * dimensions, and maps viewer touch coordinates into canonical `[0.0..1.0]` coordinates without
 * `-0.0f` (FUN-01, FUN-05).
 */
class ViewportMapper(
    initialVideoWidth: Int = DEFAULT_VIDEO_WIDTH,
    initialVideoHeight: Int = DEFAULT_VIDEO_HEIGHT
) {
    private data class ViewportSnapshot(
        val containerWidth: Int,
        val containerHeight: Int,
        val videoWidth: Int,
        val videoHeight: Int,
        val displayWidth: Int,
        val displayHeight: Int,
        val rotation: Int,
        val rect: LetterboxRect
    )

    @Volatile
    private var snapshot = ViewportSnapshot(
        containerWidth = 0,
        containerHeight = 0,
        videoWidth = initialVideoWidth,
        videoHeight = initialVideoHeight,
        displayWidth = initialVideoWidth,
        displayHeight = initialVideoHeight,
        rotation = 0,
        rect = computeRect(0, 0, initialVideoWidth, initialVideoHeight)
    )

    private val updateLock = Any()

    val letterboxRect: LetterboxRect
        get() = snapshot.rect

    val streamWidth: Int
        get() = snapshot.videoWidth

    val streamHeight: Int
        get() = snapshot.videoHeight

    val displayRotation: Int
        get() = snapshot.rotation

    fun updateContainerSize(widthPx: Int, heightPx: Int): LetterboxRect {
        synchronized(updateLock) {
            val prev = snapshot
            val safeW = widthPx.coerceAtLeast(0)
            val safeH = heightPx.coerceAtLeast(0)
            val rect = computeRect(safeW, safeH, prev.videoWidth, prev.videoHeight)
            snapshot = prev.copy(containerWidth = safeW, containerHeight = safeH, rect = rect)
            return rect
        }
    }

    fun updateVideoSize(videoSize: VideoSize): LetterboxRect {
        synchronized(updateLock) {
            val prev = snapshot
            val rect = computeRect(
                prev.containerWidth,
                prev.containerHeight,
                videoSize.streamWidth,
                videoSize.streamHeight
            )
            snapshot = prev.copy(
                videoWidth = videoSize.streamWidth,
                videoHeight = videoSize.streamHeight,
                displayWidth = videoSize.display.width,
                displayHeight = videoSize.display.height,
                rotation = videoSize.display.rotation,
                rect = rect
            )
            return rect
        }
    }

    fun updateStreamDimensions(widthPx: Int, heightPx: Int): LetterboxRect {
        if (widthPx <= 0 || heightPx <= 0) return snapshot.rect
        synchronized(updateLock) {
            val prev = snapshot
            val rect = computeRect(prev.containerWidth, prev.containerHeight, widthPx, heightPx)
            snapshot = prev.copy(videoWidth = widthPx, videoHeight = heightPx, rect = rect)
            return rect
        }
    }

    /**
     * Maps an initial `DOWN` touch at `(rawX, rawY)`. Returns `null` if the touch lands inside a
     * letterbox bar outside the rendered video rectangle or if the viewport is not laid out.
     */
    fun mapTouchDown(rawX: Float, rawY: Float): NormalizedPoint? {
        val rect = snapshot.rect
        if (!rect.contains(rawX, rawY)) return null
        return NormalizedPoint(
            normalizeUnit((rawX - rect.left) / rect.width),
            normalizeUnit((rawY - rect.top) / rect.height)
        )
    }

    /** Allocation-free [mapTouchDown] writing normalized `[x, y]` into [out]. */
    fun mapTouchDown(rawX: Float, rawY: Float, out: FloatArray): Boolean {
        val rect = snapshot.rect
        if (!rect.contains(rawX, rawY)) return false
        out[0] = normalizeUnit((rawX - rect.left) / rect.width)
        out[1] = normalizeUnit((rawY - rect.top) / rect.height)
        return true
    }

    /**
     * Maps `MOVE` or `UP` for an already-down pointer, clamping to `[0.0..1.0]` even if the
     * finger slides into a letterbox bar so `UP` is never dropped.
     */
    fun mapActivePointer(rawX: Float, rawY: Float): NormalizedPoint? {
        val rect = snapshot.rect
        if (!isValidActiveInput(rawX, rawY, rect)) return null
        return NormalizedPoint(
            normalizeUnit((rawX - rect.left) / rect.width),
            normalizeUnit((rawY - rect.top) / rect.height)
        )
    }

    /** Allocation-free [mapActivePointer] writing normalized `[x, y]` into [out]. */
    fun mapActivePointer(rawX: Float, rawY: Float, out: FloatArray): Boolean {
        val rect = snapshot.rect
        if (!isValidActiveInput(rawX, rawY, rect)) return false
        out[0] = normalizeUnit((rawX - rect.left) / rect.width)
        out[1] = normalizeUnit((rawY - rect.top) / rect.height)
        return true
    }

    private fun isValidActiveInput(rawX: Float, rawY: Float, rect: LetterboxRect): Boolean {
        val coordsFinite = rawX.isFinite() && rawY.isFinite()
        val rectPositive = rect.width > 0f && rect.height > 0f
        return coordsFinite && rectPositive
    }

    private companion object {
        const val DEFAULT_VIDEO_WIDTH = 1080
        const val DEFAULT_VIDEO_HEIGHT = 2400

        fun computeRect(containerW: Int, containerH: Int, videoW: Int, videoH: Int): LetterboxRect {
            val positive = minOf(containerW, containerH, videoW, videoH) > 0
            if (!positive) {
                return LetterboxRect(0f, 0f, 0f, 0f)
            }
            val cw = containerW.toFloat()
            val ch = containerH.toFloat()
            val vw = videoW.toFloat()
            val vh = videoH.toFloat()
            val scale = min(cw / vw, ch / vh)
            val renderedW = (vw * scale).coerceAtLeast(1f)
            val renderedH = (vh * scale).coerceAtLeast(1f)
            val left = (cw - renderedW) * 0.5f
            val top = (ch - renderedH) * 0.5f
            return LetterboxRect(left, top, renderedW, renderedH)
        }

        fun normalizeUnit(raw: Float): Float {
            val clamped = raw.coerceIn(0f, 1f)
            return if (clamped == 0f) 0f else clamped
        }
    }
}
