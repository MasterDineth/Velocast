package com.mastersoft.velocast

import android.annotation.SuppressLint
import android.content.ContentValues
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

class MirrorActivity : AppCompatActivity(), SurfaceHolder.Callback {

    companion object {
        const val EXTRA_HOST = "extra_host"
        const val EXTRA_PORT = "extra_port"
        const val EXTRA_BITRATE = "extra_bitrate"
        const val EXTRA_FPS = "extra_fps"
        const val EXTRA_SCALE = "extra_scale"
    }

    private lateinit var prefs: MirrorPreferences
    private lateinit var host: String
    private var port: Int = Protocol.DEFAULT_PORT
    private var bitrateBps: Int = 8_000_000
    private var fps: Int = 60
    private var resolutionScale: Float = 1.0f

    private lateinit var mirrorRoot: FrameLayout
    private lateinit var surfaceView: SurfaceView
    private lateinit var hudContainer: LinearLayout
    private lateinit var hudBadge: TextView
    private lateinit var toolboxDock: LinearLayout
    private lateinit var fabToggleBtn: FrameLayout
    private lateinit var fabChevronIcon: ImageView
    private lateinit var touchController: TouchOverlayController

    private var videoDecoder: VideoDecoder? = null
    private var mirrorClient: MirrorClient? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val frameCounter = AtomicInteger(0)
    private var lastFpsSampleMs = 0L

    private var currentFps: Int = 0
    private var currentMbps: Float = 0f
    private var currentPingMs: Int = -1
    private var isConnected: Boolean = false
    private var isToolboxOpen: Boolean = false

    private val hudFadeRunnable = Runnable {
        if (prefs.hudEnabled && prefs.hudAutoFade && prefs.hudFadeDelaySec > 0) {
            hudBadge.animate().alpha(0.20f).setDuration(350L).start()
        }
    }

    private val fabFadeRunnable = Runnable {
        if (prefs.fabEnabled && prefs.fabAutoFade && !isToolboxOpen) {
            fabToggleBtn.animate().alpha(0.20f).setDuration(350L).start()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = MirrorPreferences(this)
        enableImmersiveFullscreen()
        applyKeepScreenAwake()
        setContentView(R.layout.activity_mirror)

        host = intent.getStringExtra(EXTRA_HOST) ?: prefs.hostIp
        port = intent.getIntExtra(EXTRA_PORT, prefs.port)
        bitrateBps = intent.getIntExtra(EXTRA_BITRATE, 8_000_000)
        fps = intent.getIntExtra(EXTRA_FPS, 60)
        resolutionScale = intent.getFloatExtra(EXTRA_SCALE, prefs.resolutionScalePercent / 100f)

        mirrorRoot = findViewById(R.id.mirrorRoot)
        surfaceView = findViewById(R.id.mirrorSurfaceView)
        hudContainer = findViewById(R.id.hudContainer)
        hudBadge = findViewById(R.id.hudBadge)
        toolboxDock = findViewById(R.id.toolboxDock)
        fabToggleBtn = findViewById(R.id.fabToggleBtn)
        fabChevronIcon = findViewById(R.id.fabChevronIcon)

        touchController = TouchOverlayController(
            container = mirrorRoot,
            surfaceView = surfaceView
        ) { action, pointerId, normX, normY ->
            mirrorClient?.sendTouch(action, pointerId, normX, normY)
            wakeHudFromIdle()
        }

        surfaceView.holder.addCallback(this)

        configureHudFromPrefs()
        configureFabFromPrefs()
        setupFabInteraction()

        mirrorRoot.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (prefs.fabEnabled) {
                applyFabPosition()
                if (isToolboxOpen) {
                    positionToolboxRelativeToFab()
                }
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            enableImmersiveFullscreen()
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun applyKeepScreenAwake() {
        if (prefs.keepScreenAwake) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun configureHudFromPrefs() {
        if (!prefs.hudEnabled) {
            hudContainer.visibility = View.GONE
            return
        }
        hudContainer.visibility = View.VISIBLE
        hudContainer.gravity = when (prefs.hudAlignment) {
            HudAlignment.LEFT -> Gravity.START or Gravity.CENTER_VERTICAL
            HudAlignment.CENTER -> Gravity.CENTER
            HudAlignment.RIGHT -> Gravity.END or Gravity.CENTER_VERTICAL
        }

        val soloFps = prefs.showFps && !prefs.showBitrate && !prefs.showHostAddress && !prefs.showPing
        if (soloFps) {
            hudBadge.setBackgroundResource(R.drawable.bg_hud_circle)
            hudBadge.minWidth = dp(40)
            hudBadge.minHeight = dp(40)
            hudBadge.setPadding(0, 0, 0, 0)
        } else {
            hudBadge.setBackgroundResource(R.drawable.bg_hud_pill)
            hudBadge.minWidth = 0
            hudBadge.minHeight = 0
            hudBadge.setPadding(dp(12), dp(6), dp(12), dp(6))
        }

        hudBadge.alpha = prefs.hudRestingOpacity / 100f
        renderHudText()
        scheduleHudAutoFade()
    }

    private fun renderHudText() {
        if (!prefs.hudEnabled) return
        if (!isConnected) {
            hudBadge.text = "..."
            return
        }

        val soloFps = prefs.showFps && !prefs.showBitrate && !prefs.showHostAddress && !prefs.showPing
        if (soloFps) {
            hudBadge.text = if (currentFps > 0) "$currentFps" else "--"
            return
        }

        val parts = mutableListOf<String>()
        if (prefs.showFps) {
            parts.add(if (currentFps > 0) "$currentFps FPS" else "-- FPS")
        }
        if (prefs.showBitrate) {
            val bwText = when (prefs.bitrateUnit) {
                BitrateUnit.MBPS -> String.format(Locale.US, "%.1f Mbps", currentMbps)
                BitrateUnit.MB_S -> String.format(Locale.US, "%.2f MB/s", currentMbps / 8f)
            }
            parts.add(bwText)
        }
        if (prefs.showHostAddress) {
            parts.add(host)
        }
        if (prefs.showPing) {
            parts.add(if (currentPingMs > 0) "${currentPingMs}ms" else "--ms")
        }

        hudBadge.text = if (parts.isEmpty()) "LIVE" else parts.joinToString(" • ")
    }

    private fun wakeHudFromIdle() {
        if (!prefs.hudEnabled) return
        mainHandler.removeCallbacks(hudFadeRunnable)
        hudBadge.animate().alpha(prefs.hudRestingOpacity / 100f).setDuration(150L).start()
        scheduleHudAutoFade()
    }

    private fun scheduleHudAutoFade() {
        mainHandler.removeCallbacks(hudFadeRunnable)
        if (prefs.hudEnabled && prefs.hudAutoFade && prefs.hudFadeDelaySec > 0) {
            mainHandler.postDelayed(hudFadeRunnable, prefs.hudFadeDelaySec * 1000L)
        }
    }

    private fun configureFabFromPrefs() {
        if (!prefs.fabEnabled) {
            fabToggleBtn.visibility = View.GONE
            toolboxDock.visibility = View.GONE
            return
        }
        fabToggleBtn.visibility = View.VISIBLE
        fabToggleBtn.alpha = prefs.fabRestingOpacity / 100f
        toolboxDock.alpha = prefs.fabRestingOpacity / 100f

        val isBottomCenter = prefs.fabAnchor == FabAnchor.BOTTOM_CENTER
        toolboxDock.orientation = if (isBottomCenter) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        populateToolboxButtons(isBottomCenter)

        mirrorRoot.post {
            applyFabPosition()
            setToolboxOpen(false, animate = false)
        }
        scheduleFabAutoFade()
    }

    /**
     * Positions the fixed-size 44dp fabToggleBtn strictly within screen margins.
     * Because fabToggleBtn is decoupled from toolboxDock, expanding/collapsing never alters its layout.
     */
    private fun applyFabPosition() {
        val rootW = mirrorRoot.width
        val rootH = mirrorRoot.height
        val fabW = fabToggleBtn.width.takeIf { it > 0 } ?: dp(44)
        val fabH = fabToggleBtn.height.takeIf { it > 0 } ?: dp(44)
        if (rootW <= 0 || rootH <= 0) return

        val margin = dp(18).toFloat()
        val maxX = (rootW - fabW - margin).coerceAtLeast(margin)
        val maxY = (rootH - fabH - margin).coerceAtLeast(margin)

        val targetX: Float
        val targetY: Float

        if (prefs.fabDraggable && prefs.fabCustomXRatio >= 0f && prefs.fabCustomYRatio >= 0f) {
            targetX = (prefs.fabCustomXRatio * (rootW - fabW)).coerceIn(margin, maxX)
            targetY = (prefs.fabCustomYRatio * (rootH - fabH)).coerceIn(margin, maxY)
        } else {
            when (prefs.fabAnchor) {
                FabAnchor.BOTTOM_RIGHT -> {
                    targetX = maxX
                    targetY = maxY
                }
                FabAnchor.BOTTOM_CENTER -> {
                    targetX = (rootW - fabW) * 0.5f
                    targetY = maxY
                }
                FabAnchor.BOTTOM_LEFT -> {
                    targetX = margin
                    targetY = maxY
                }
                FabAnchor.RIGHT_MID -> {
                    targetX = maxX
                    targetY = (rootH - fabH) * 0.5f
                }
            }
        }

        fabToggleBtn.x = targetX
        fabToggleBtn.y = targetY
    }

    /**
     * Positions toolboxDock cleanly above or below fabToggleBtn (auto-flipping near top edge)
     * and clamps both X and Y strictly inside the visible screen bounds.
     */
    private fun positionToolboxRelativeToFab() {
        val rootW = mirrorRoot.width
        val rootH = mirrorRoot.height
        if (rootW <= 0 || rootH <= 0) return

        toolboxDock.measure(
            View.MeasureSpec.makeMeasureSpec(rootW, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(rootH, View.MeasureSpec.AT_MOST)
        )
        val dockW = toolboxDock.measuredWidth.takeIf { it > 0 } ?: toolboxDock.width
        val dockH = toolboxDock.measuredHeight.takeIf { it > 0 } ?: toolboxDock.height
        val fabW = fabToggleBtn.width.takeIf { it > 0 } ?: dp(44)
        val fabH = fabToggleBtn.height.takeIf { it > 0 } ?: dp(44)
        val gap = dp(10).toFloat()
        val pad = dp(8).toFloat()

        val fabCenterX = fabToggleBtn.x + fabW * 0.5f
        val desiredX = fabCenterX - dockW * 0.5f
        val clampedX = desiredX.coerceIn(pad, (rootW - dockW - pad).coerceAtLeast(pad))

        val aboveY = fabToggleBtn.y - dockH - gap
        val belowY = fabToggleBtn.y + fabH + gap
        val desiredY = if (aboveY >= pad) aboveY else belowY
        val clampedY = desiredY.coerceIn(pad, (rootH - dockH - pad).coerceAtLeast(pad))

        toolboxDock.x = clampedX
        toolboxDock.y = clampedY
    }

    private fun populateToolboxButtons(horizontal: Boolean) {
        toolboxDock.removeAllViews()
        val activeTools = prefs.getActiveTools()

        activeTools.forEachIndexed { idx, action ->
            if (idx > 0 && (action == ToolboxAction.SHADE || action == ToolboxAction.BACK)) {
                val divider = View(this).apply {
                    setBackgroundColor(0x1AFFFFFF)
                    layoutParams = if (horizontal) {
                        LinearLayout.LayoutParams(dp(1), dp(26)).apply {
                            marginStart = dp(4)
                            marginEnd = dp(4)
                            gravity = Gravity.CENTER_VERTICAL
                        }
                    } else {
                        LinearLayout.LayoutParams(dp(28), dp(1)).apply {
                            topMargin = dp(4)
                            bottomMargin = dp(4)
                            gravity = Gravity.CENTER_HORIZONTAL
                        }
                    }
                }
                toolboxDock.addView(divider)
            }

            val btn = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setBackgroundResource(R.drawable.bg_toolbox_item_ripple)
                isClickable = true
                isFocusable = true
                layoutParams = LinearLayout.LayoutParams(dp(52), dp(46)).apply {
                    gravity = Gravity.CENTER
                    if (horizontal) {
                        marginStart = dp(2)
                        marginEnd = dp(2)
                    } else {
                        topMargin = dp(2)
                        bottomMargin = dp(2)
                    }
                }
            }

            val icon = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(18), dp(18)).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                }
                setImageResource(action.iconRes)
                setColorFilter(0xFFE4E4E7.toInt())
            }

            val label = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    topMargin = dp(3)
                }
                gravity = Gravity.CENTER
                maxLines = 1
                text = action.shortLabel
                textSize = 9.5f
                setTextColor(0xFFA1A1AA.toInt())
            }

            btn.addView(icon)
            btn.addView(label)

            btn.setOnClickListener {
                performFabHaptic(it)
                wakeFabFromIdle()
                executeToolboxAction(action)
            }

            toolboxDock.addView(btn)
        }
    }

    private fun executeToolboxAction(action: ToolboxAction) {
        when (action) {
            ToolboxAction.POWER -> mirrorClient?.sendKeyPress(KeyEvent.KEYCODE_POWER)
            ToolboxAction.VOL_UP -> mirrorClient?.sendKeyPress(KeyEvent.KEYCODE_VOLUME_UP)
            ToolboxAction.VOL_DOWN -> mirrorClient?.sendKeyPress(KeyEvent.KEYCODE_VOLUME_DOWN)
            ToolboxAction.SHADE -> mirrorClient?.sendExpandNotifications()
            ToolboxAction.ROTATE -> mirrorClient?.sendRotateScreen()
            ToolboxAction.BACK -> mirrorClient?.sendKeyPress(KeyEvent.KEYCODE_BACK)
            ToolboxAction.SCREENSHOT -> captureSurfaceScreenshot()
        }
    }

    private fun captureSurfaceScreenshot() {
        val w = surfaceView.width
        val h = surfaceView.height
        if (w <= 0 || h <= 0 || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            Toast.makeText(this, "Screenshot unavailable", Toast.LENGTH_SHORT).show()
            return
        }

        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        try {
            PixelCopy.request(surfaceView, bitmap, { copyResult ->
                if (copyResult == PixelCopy.SUCCESS) {
                    saveBitmapToGallery(bitmap)
                } else {
                    bitmap.recycle()
                    runOnUiThread {
                        Toast.makeText(this, "Capture failed (code $copyResult)", Toast.LENGTH_SHORT).show()
                    }
                }
            }, mainHandler)
        } catch (t: Throwable) {
            bitmap.recycle()
            Toast.makeText(this, "Capture error: ${t.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveBitmapToGallery(bitmap: Bitmap) {
        Thread {
            try {
                val filename = "VeloCast_${System.currentTimeMillis()}.png"
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/VeloCast")
                    }
                }
                val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    contentResolver.openOutputStream(uri)?.use { out ->
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                    }
                    runOnUiThread {
                        Toast.makeText(this, "Saved $filename", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (t: Throwable) {
                Log.e("MirrorActivity", "Failed to save screenshot", t)
                runOnUiThread {
                    Toast.makeText(this, "Save failed: ${t.message}", Toast.LENGTH_SHORT).show()
                }
            } finally {
                bitmap.recycle()
            }
        }.start()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupFabInteraction() {
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        var downRawX = 0f
        var downRawY = 0f
        var startX = 0f
        var startY = 0f
        var isDragging = false

        fabToggleBtn.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    wakeFabFromIdle()
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = fabToggleBtn.x
                    startY = fabToggleBtn.y
                    isDragging = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    if (prefs.fabDraggable) {
                        val dx = event.rawX - downRawX
                        val dy = event.rawY - downRawY
                        if (!isDragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                            isDragging = true
                        }
                        if (isDragging) {
                            val margin = dp(8).toFloat()
                            val maxX = (mirrorRoot.width - fabToggleBtn.width - margin).coerceAtLeast(margin)
                            val maxY = (mirrorRoot.height - fabToggleBtn.height - margin).coerceAtLeast(margin)
                            fabToggleBtn.x = (startX + dx).coerceIn(margin, maxX)
                            fabToggleBtn.y = (startY + dy).coerceIn(margin, maxY)
                            if (isToolboxOpen) {
                                positionToolboxRelativeToFab()
                            }
                        }
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    if (isDragging) {
                        val availW = (mirrorRoot.width - fabToggleBtn.width).coerceAtLeast(1)
                        val availH = (mirrorRoot.height - fabToggleBtn.height).coerceAtLeast(1)
                        prefs.fabCustomXRatio = (fabToggleBtn.x / availW).coerceIn(0f, 1f)
                        prefs.fabCustomYRatio = (fabToggleBtn.y / availH).coerceIn(0f, 1f)
                    } else {
                        performFabHaptic(v)
                        setToolboxOpen(!isToolboxOpen, animate = true)
                    }
                    scheduleFabAutoFade()
                    true
                }

                MotionEvent.ACTION_CANCEL -> {
                    scheduleFabAutoFade()
                    true
                }

                else -> false
            }
        }
    }

    private fun setToolboxOpen(open: Boolean, animate: Boolean) {
        isToolboxOpen = open
        val targetAlpha = prefs.fabRestingOpacity / 100f
        if (open) {
            positionToolboxRelativeToFab()
        }
        if (animate) {
            fabChevronIcon.animate().rotation(if (open) 180f else 0f).setDuration(240L).start()
            if (open) {
                toolboxDock.visibility = View.VISIBLE
                toolboxDock.alpha = 0f
                toolboxDock.scaleX = 0.90f
                toolboxDock.scaleY = 0.90f
                toolboxDock.animate()
                    .alpha(targetAlpha)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(220L)
                    .start()
            } else {
                toolboxDock.animate()
                    .alpha(0f)
                    .scaleX(0.90f)
                    .scaleY(0.90f)
                    .setDuration(180L)
                    .withEndAction { toolboxDock.visibility = View.GONE }
                    .start()
            }
        } else {
            fabChevronIcon.rotation = if (open) 180f else 0f
            toolboxDock.alpha = targetAlpha
            toolboxDock.visibility = if (open) View.VISIBLE else View.GONE
        }
        scheduleFabAutoFade()
    }

    private fun wakeFabFromIdle() {
        if (!prefs.fabEnabled) return
        mainHandler.removeCallbacks(fabFadeRunnable)
        fabToggleBtn.animate().alpha(prefs.fabRestingOpacity / 100f).setDuration(150L).start()
    }

    private fun scheduleFabAutoFade() {
        mainHandler.removeCallbacks(fabFadeRunnable)
        if (prefs.fabEnabled && prefs.fabAutoFade && !isToolboxOpen) {
            mainHandler.postDelayed(fabFadeRunnable, prefs.fabFadeDelaySec * 1000L)
        }
    }

    private fun performFabHaptic(view: View) {
        if (prefs.hapticOnFabTap) {
            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        Log.d("MirrorActivity", "surfaceCreated: surface=${holder.surface}")
        val decoder = videoDecoder
        if (decoder == null) {
            isConnected = false
            renderHudText()
            lastFpsSampleMs = System.currentTimeMillis()
            frameCounter.set(0)

            val newDecoder = VideoDecoder(
                surface = holder.surface,
                initialWidth = 1080,
                initialHeight = 2400,
                onVideoSizeChanged = { w, h ->
                    runOnUiThread { touchController.updateVideoSize(w, h) }
                },
                onFrameRendered = {
                    val count = frameCounter.incrementAndGet()
                    val now = System.currentTimeMillis()
                    val elapsed = now - lastFpsSampleMs
                    if (elapsed >= 1000L) {
                        val measuredFps = (count * 1000L / elapsed).toInt()
                        frameCounter.set(0)
                        lastFpsSampleMs = now
                        currentFps = measuredFps
                        TelemetryState.lastFps = measuredFps
                        runOnUiThread { renderHudText() }
                    }
                }
            )
            newDecoder.start()
            videoDecoder = newDecoder

            val client = MirrorClient(
                host = host,
                port = port,
                bitrateBps = bitrateBps,
                fps = fps,
                resolutionScale = resolutionScale,
                decoder = newDecoder,
                onConnected = {
                    isConnected = true
                    runOnUiThread { renderHudText() }
                },
                onDisconnected = { reason ->
                    isConnected = false
                    runOnUiThread {
                        if (!isFinishing && !isDestroyed) {
                            Log.e("MirrorActivity", "Disconnected: $reason")
                            Toast.makeText(this, "Disconnected: $reason", Toast.LENGTH_LONG).show()
                            finish()
                        }
                    }
                },
                onThroughputSample = { bytesPerSec ->
                    val mbps = (bytesPerSec * 8f) / 1_000_000f
                    currentMbps = mbps
                    TelemetryState.lastBitrateMbps = mbps
                    runOnUiThread { renderHudText() }
                },
                onPingMeasured = { rttMs ->
                    currentPingMs = rttMs
                    TelemetryState.lastPingMs = rttMs
                    runOnUiThread { renderHudText() }
                }
            )
            mirrorClient = client
            client.connect()
        } else {
            decoder.setOutputSurface(holder.surface)
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        touchController.recomputeViewport()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        videoDecoder?.surfaceValid = false
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(hudFadeRunnable)
        mainHandler.removeCallbacks(fabFadeRunnable)
        stopSession()
        super.onDestroy()
    }

    private fun stopSession() {
        mirrorClient?.disconnect()
        mirrorClient = null
        videoDecoder?.stop()
        videoDecoder = null
    }

    private fun enableImmersiveFullscreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }
}
