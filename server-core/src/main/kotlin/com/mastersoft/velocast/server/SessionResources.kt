package com.mastersoft.velocast.server

import android.os.Build
import android.os.Process
import com.mastersoft.velocast.protocol.DeviceInfo
import com.mastersoft.velocast.protocol.DisplayGeometry
import com.mastersoft.velocast.protocol.FrameHeader
import com.mastersoft.velocast.protocol.FrameWriter
import com.mastersoft.velocast.protocol.LaunchMode
import com.mastersoft.velocast.protocol.Payload
import com.mastersoft.velocast.protocol.Phase
import com.mastersoft.velocast.protocol.ProtocolV2
import com.mastersoft.velocast.server.platform.BinderInjectorSink
import com.mastersoft.velocast.server.platform.VirtualDisplayBridge
import java.io.Closeable
import java.io.OutputStream

/**
 * Serializes all outbound protocol v2 frames on a single connection lock.
 */
class SessionFrameSink(private val writer: FrameWriter, private val out: OutputStream) {
    private val lock = Any()

    var phase: Phase
        get() = synchronized(lock) { writer.phase }
        set(value) {
            synchronized(lock) { writer.phase = value }
        }

    fun writeMessage(msg: Payload, flags: Int? = null) {
        synchronized(lock) {
            if (flags != null) {
                writer.writeMessage(msg, flags)
            } else {
                writer.writeMessage(msg)
            }
            out.flush()
        }
    }

    fun writeVideoInPlace(header: FrameHeader, frameBuffer: ByteArray, payloadLength: Int) {
        synchronized(lock) {
            header.seq = writer.nextSeq
            writer.writeInPlace(header, frameBuffer, payloadLength)
            writer.nextSeq = (writer.nextSeq + 1) and SEQ_MASK
            out.flush()
        }
    }

    private companion object {
        const val SEQ_MASK = 0xFFFF
    }
}

interface MediaPipeline : Closeable {
    fun start()
    fun pause()
    fun resume()
    fun requestKeyframe()
    fun reconfigure(bitrateBps: Int, fps: Int, scale: Float)
}

interface SessionEnvironment {
    val capabilities: Int
    fun queryDeviceInfo(): DeviceInfo
    fun createInjector(display: DisplayGeometry): InputInjector
    fun createMediaPipeline(display: DisplayGeometry, sink: SessionFrameSink): MediaPipeline
}

class AndroidSessionEnvironment(private val clock: ServerClock, private val logger: Logger) :
    SessionEnvironment {
    override val capabilities: Int = 0

    override fun queryDeviceInfo(): DeviceInfo {
        val display = VirtualDisplayBridge(logger).queryDefaultDisplayGeometry()
        val launchMode = if (Process.myUid() == 0) LaunchMode.ROOT else LaunchMode.SHELL
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
        sink = BinderInjectorSink(logger),
        clock = clock,
        logger = logger
    )

    override fun createMediaPipeline(
        display: DisplayGeometry,
        sink: SessionFrameSink
    ): MediaPipeline = ScreenEncoder(
        initialDisplay = display,
        frameSink = sink,
        clock = clock,
        logger = logger
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
