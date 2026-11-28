package com.mastersoft.velocast.server

import android.os.Build
import com.mastersoft.velocast.protocol.DeviceInfo
import com.mastersoft.velocast.protocol.DisplayGeometry
import com.mastersoft.velocast.protocol.Features
import com.mastersoft.velocast.protocol.LaunchMode
import com.mastersoft.velocast.protocol.ProtocolV2
import com.mastersoft.velocast.server.platform.AndroidDeviceStateController
import com.mastersoft.velocast.server.platform.BinderInjectorSink
import com.mastersoft.velocast.server.platform.ClipboardBackend
import com.mastersoft.velocast.server.platform.DeviceStateController
import com.mastersoft.velocast.server.platform.FakeContext
import com.mastersoft.velocast.server.platform.PlatformContext
import com.mastersoft.velocast.server.platform.ReflectionClipboardBackend
import com.mastersoft.velocast.server.platform.RotationWatcherShim
import com.mastersoft.velocast.server.platform.SavedDeviceState
import com.mastersoft.velocast.server.platform.ServerCapabilities
import com.mastersoft.velocast.server.platform.VirtualDisplayBridge
import java.io.Closeable
import java.nio.file.Path

interface MediaPipeline : Closeable {
    fun start()
    fun pause()
    fun resume()
    fun requestKeyframe()
    fun reconfigure(bitrateBps: Int, fps: Int, scale: Float)
    fun notifyRotationChanged() = Unit
}

interface SessionEnvironment {
    val capabilities: Int
    fun queryDeviceInfo(): DeviceInfo
    fun createInjector(display: DisplayGeometry): InputInjector
    fun createMediaPipeline(
        display: DisplayGeometry,
        sink: SessionFrameSink,
        onGeometryChanged: (DisplayGeometry) -> Unit = {}
    ): MediaPipeline

    fun createStateController(): DeviceStateController? = null

    fun createClipboardBackend(): ClipboardBackend? = null

    fun startCleanUpGuardian(
        state: SavedDeviceState,
        restoreDisplayPower: Boolean
    ): CleanUpGuardian? = null

    fun registerRotationWatcher(onRotationChanged: () -> Unit): Closeable? = null

    fun probeCapabilities(
        injector: InputInjector,
        hasRotationWatcher: Boolean
    ): ServerCapabilities = ServerCapabilities(
        inputInjection = injector.hasInputInjection,
        rotationWatcher = hasRotationWatcher,
        displayPowerControl = false,
        notificationShade = injector.hasNotificationShade,
        wake = injector.hasWakeKeyInjection,
        clipboard = true
    )
}

class AndroidSessionEnvironment(
    private val clock: ServerClock,
    private val logger: Logger,
    private val stateDir: Path? = null,
    private val bitrateMode: BitrateMode = BitrateMode.DEFAULT,
    private val platformContext: PlatformContext = FakeContext()
) : SessionEnvironment {
    private val stateController = AndroidDeviceStateController(
        logger = logger,
        platformContext = platformContext
    )
    private val clipboardBackend = ReflectionClipboardBackend(
        platformContext = platformContext,
        logger = logger
    )

    override val capabilities: Int
        get() {
            var mask = ServerCapabilities.FEATURE_ROTATION_EVENTS or Features.SECURE_CHANNEL
            if (stateController.displayPowerSupported) {
                mask = mask or ServerCapabilities.FEATURE_DISPLAY_POWER
            }
            if (clipboardBackend.isSupported) {
                mask = mask or ServerCapabilities.FEATURE_CLIPBOARD
            }
            return mask
        }

    override fun queryDeviceInfo(): DeviceInfo {
        val display = VirtualDisplayBridge(logger, platformContext).queryDefaultDisplayGeometry()
        val launchMode = if (platformContext.isRootUid) LaunchMode.ROOT else LaunchMode.SHELL
        return DeviceInfo(
            sdkInt = Build.VERSION.SDK_INT,
            launchMode = launchMode,
            display = display,
            manufacturer = truncateUtf8(Build.MANUFACTURER.orEmpty(), ProtocolV2.MAX_NAME_BYTES),
            model = truncateUtf8(Build.MODEL.orEmpty(), ProtocolV2.MAX_NAME_BYTES)
        )
    }

    override fun createInjector(display: DisplayGeometry): InputInjector = InputInjector(
        displayWidth = display.width,
        displayHeight = display.height,
        sink = BinderInjectorSink(logger, platformContext),
        clock = clock,
        logger = logger
    )

    override fun createMediaPipeline(
        display: DisplayGeometry,
        sink: SessionFrameSink,
        onGeometryChanged: (DisplayGeometry) -> Unit
    ): MediaPipeline = ScreenEncoder(
        initialDisplay = display,
        frameSink = sink,
        clock = clock,
        logger = logger,
        options = StreamOptions(bitrateMode = bitrateMode),
        captureBackend = VirtualDisplayCaptureBackend(logger, platformContext),
        onGeometryChanged = onGeometryChanged
    )

    override fun createStateController(): DeviceStateController = stateController

    override fun createClipboardBackend(): ClipboardBackend = clipboardBackend

    override fun startCleanUpGuardian(
        state: SavedDeviceState,
        restoreDisplayPower: Boolean
    ): CleanUpGuardian? = ProcessCleanUpGuardian.start(
        state = state,
        restoreDisplayPower = restoreDisplayPower,
        stateDir = stateDir,
        logger = logger
    )

    override fun registerRotationWatcher(onRotationChanged: () -> Unit): Closeable? =
        RotationWatcherShim.register(logger) { onRotationChanged() }

    override fun probeCapabilities(
        injector: InputInjector,
        hasRotationWatcher: Boolean
    ): ServerCapabilities = ServerCapabilities(
        inputInjection = injector.hasInputInjection,
        rotationWatcher = hasRotationWatcher,
        displayPowerControl = stateController.displayPowerSupported,
        notificationShade = injector.hasNotificationShade,
        wake = injector.hasWakeKeyInjection,
        clipboard = clipboardBackend.isSupported
    )

    private fun truncateUtf8(text: String, maxBytes: Int): String {
        val sanitized = text.replace("\u0000", "")
        if (sanitized.toByteArray(Charsets.UTF_8).size <= maxBytes) return sanitized
        val sb = StringBuilder()
        var bytes = 0
        for (ch in sanitized) {
            val charBytes = ch.toString().toByteArray(Charsets.UTF_8).size
            if (bytes + charBytes > maxBytes) break
            sb.append(ch)
            bytes += charBytes
        }
        return sb.toString()
    }
}
