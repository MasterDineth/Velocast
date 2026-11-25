package com.mastersoft.velocast.server

import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import com.mastersoft.velocast.protocol.ProtocolV2
import java.util.Locale

/**
 * Rate-control mode for the video encoder (PERF-04).
 *
 * Defaults to [CBR] for predictable latency; [VBR] is kept as a benchmark switch (`--bitrate-mode`).
 */
enum class BitrateMode(val mediaCodecMode: Int, val wireName: String) {
    CBR(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR, "CBR"),
    VBR(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR, "VBR")
    ;

    companion object {
        const val DEFAULT_MODE_NAME = "CBR"
        val DEFAULT: BitrateMode = CBR

        fun parse(raw: String): BitrateMode = when (raw.trim().uppercase(Locale.ROOT)) {
            "CBR" -> CBR
            "VBR" -> VBR
            else -> throw IllegalArgumentException("Unknown bitrate mode: $raw")
        }
    }
}

/** Snapshot of H.264 encoder capabilities queried from `MediaCodecInfo`. */
data class CodecCapabilitiesSnapshot(
    val supportsCbr: Boolean,
    val supportsVbr: Boolean,
    val supportsConstrainedBaseline: Boolean,
    val maxConstrainedBaselineLevel: Int? = null
) {
    fun supports(mode: BitrateMode): Boolean = when (mode) {
        BitrateMode.CBR -> supportsCbr
        BitrateMode.VBR -> supportsVbr
    }

    companion object {
        fun fromMediaCodecInfo(info: MediaCodecInfo): CodecCapabilitiesSnapshot =
            fromCodecCapabilities(info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC))

        fun fromCodecCapabilities(
            caps: MediaCodecInfo.CodecCapabilities
        ): CodecCapabilitiesSnapshot {
            val encCaps = caps.encoderCapabilities
            val supportsCbr =
                encCaps?.isBitrateModeSupported(
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
                ) == true
            val supportsVbr =
                encCaps?.isBitrateModeSupported(
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
                ) == true
            val baselineProfile = MediaCodecInfo.CodecProfileLevel.AVCProfileConstrainedBaseline
            val matchingLevels = caps.profileLevels
                ?.filter { it.profile == baselineProfile }
                .orEmpty()
            return CodecCapabilitiesSnapshot(
                supportsCbr = supportsCbr,
                supportsVbr = supportsVbr,
                supportsConstrainedBaseline = matchingLevels.isNotEmpty(),
                maxConstrainedBaselineLevel = matchingLevels.maxOfOrNull { it.level }
            )
        }
    }
}

/** Chosen encoder parameters reported to the viewer in `STATUS` (`BITRATE`). */
data class SelectedEncoderSettings(
    val bitrateBps: Int,
    val fps: Int,
    val bitrateModeName: String,
    val profileName: String,
    val maxBFrames: Int,
    val prependHeaderToSyncFrames: Boolean,
    val lowLatency: Boolean,
    val realtimePriority: Boolean
) {
    fun toStatusDetail(): String {
        val prepend = if (prependHeaderToSyncFrames) 1 else 0
        val lowLat = if (lowLatency) 1 else 0
        val prio = if (realtimePriority) 1 else 0
        val raw =
            "mode=$bitrateModeName profile=$profileName bframes=$maxBFrames " +
                "prepend=$prepend lowlat=$lowLat prio=$prio"
        return truncateUtf8(raw, ProtocolV2.MAX_STATUS_DETAIL_BYTES)
    }

    private fun truncateUtf8(text: String, maxBytes: Int): String {
        if (text.toByteArray(Charsets.UTF_8).size <= maxBytes) return text
        val sb = StringBuilder()
        var bytes = 0
        for (ch in text) {
            val charBytes = ch.toString().toByteArray(Charsets.UTF_8).size
            if (bytes + charBytes > maxBytes) break
            sb.append(ch)
            bytes += charBytes
        }
        return sb.toString()
    }
}

internal data class FormatCandidate(val format: MediaFormat, val settings: SelectedEncoderSettings)

internal object EncoderFormatBuilder {
    const val I_FRAME_INTERVAL_SEC = 5
    const val IDLE_REPEAT_US = 250_000L

    fun selectMode(requested: BitrateMode, caps: CodecCapabilitiesSnapshot?): BitrateMode? {
        if (caps == null) return requested
        return when {
            caps.supports(requested) -> requested
            caps.supports(BitrateMode.VBR) -> BitrateMode.VBR
            caps.supports(BitrateMode.CBR) -> BitrateMode.CBR
            else -> null
        }
    }

    fun selectPrimarySettings(
        bitrateBps: Int,
        fps: Int,
        requestedMode: BitrateMode,
        caps: CodecCapabilitiesSnapshot?,
        sdkInt: Int = Build.VERSION.SDK_INT
    ): SelectedEncoderSettings {
        val chosenMode = selectMode(requestedMode, caps)
        val useConstrainedBaseline = caps?.supportsConstrainedBaseline == true
        return SelectedEncoderSettings(
            bitrateBps = bitrateBps,
            fps = fps,
            bitrateModeName = chosenMode?.wireName ?: "DEFAULT",
            profileName = if (useConstrainedBaseline) "CB" else "DEFAULT",
            maxBFrames = 0,
            prependHeaderToSyncFrames = true,
            lowLatency = sdkInt >= Build.VERSION_CODES.R,
            realtimePriority = true
        )
    }

    fun buildCandidates(
        encodeWidth: Int,
        encodeHeight: Int,
        bitrateBps: Int,
        fps: Int,
        requestedMode: BitrateMode,
        caps: CodecCapabilitiesSnapshot?
    ): List<FormatCandidate> {
        val candidates = ArrayList<FormatCandidate>(MAX_CANDIDATES)
        val chosenMode = selectMode(requestedMode, caps)
        val useConstrainedBaseline = caps?.supportsConstrainedBaseline == true

        if (useConstrainedBaseline && caps?.maxConstrainedBaselineLevel != null) {
            candidates += buildCandidate(
                encodeWidth = encodeWidth,
                encodeHeight = encodeHeight,
                bitrateBps = bitrateBps,
                fps = fps,
                chosenMode = chosenMode,
                constrainedBaselineLevel = caps.maxConstrainedBaselineLevel,
                setConstrainedBaseline = true,
                includeOptionalKeys = true
            )
        }
        candidates += buildCandidate(
            encodeWidth = encodeWidth,
            encodeHeight = encodeHeight,
            bitrateBps = bitrateBps,
            fps = fps,
            chosenMode = chosenMode,
            constrainedBaselineLevel = null,
            setConstrainedBaseline = useConstrainedBaseline,
            includeOptionalKeys = true
        )
        candidates += buildCandidate(
            encodeWidth = encodeWidth,
            encodeHeight = encodeHeight,
            bitrateBps = bitrateBps,
            fps = fps,
            chosenMode = null,
            constrainedBaselineLevel = null,
            setConstrainedBaseline = false,
            includeOptionalKeys = false
        )
        return candidates
    }

    private fun buildCandidate(
        encodeWidth: Int,
        encodeHeight: Int,
        bitrateBps: Int,
        fps: Int,
        chosenMode: BitrateMode?,
        constrainedBaselineLevel: Int?,
        setConstrainedBaseline: Boolean,
        includeOptionalKeys: Boolean
    ): FormatCandidate {
        val format = MediaFormat.createVideoFormat(
            MediaFormat.MIMETYPE_VIDEO_AVC,
            encodeWidth,
            encodeHeight
        )
        format.setInteger(
            MediaFormat.KEY_COLOR_FORMAT,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
        )
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitrateBps)
        format.setInteger(MediaFormat.KEY_FRAME_RATE, fps)
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL_SEC)
        format.setInteger(MediaFormat.KEY_PRIORITY, 0)
        format.setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, IDLE_REPEAT_US)

        if (chosenMode != null) {
            format.setInteger(MediaFormat.KEY_BITRATE_MODE, chosenMode.mediaCodecMode)
        }

        val prepend = includeOptionalKeys
        val lowLatency = includeOptionalKeys && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
        if (includeOptionalKeys) {
            applyOptionalKeys(
                format = format,
                fps = fps,
                setConstrainedBaseline = setConstrainedBaseline,
                constrainedBaselineLevel = constrainedBaselineLevel
            )
        }

        val settings = SelectedEncoderSettings(
            bitrateBps = bitrateBps,
            fps = fps,
            bitrateModeName = chosenMode?.wireName ?: "DEFAULT",
            profileName = if (includeOptionalKeys && setConstrainedBaseline) "CB" else "DEFAULT",
            maxBFrames = if (includeOptionalKeys) 0 else -1,
            prependHeaderToSyncFrames = prepend,
            lowLatency = lowLatency,
            realtimePriority = true
        )
        return FormatCandidate(format, settings)
    }

    private fun applyOptionalKeys(
        format: MediaFormat,
        fps: Int,
        setConstrainedBaseline: Boolean,
        constrainedBaselineLevel: Int?
    ) {
        format.setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
        format.setInteger(MediaFormat.KEY_PREPEND_HEADER_TO_SYNC_FRAMES, 1)
        if (setConstrainedBaseline) {
            format.setInteger(
                MediaFormat.KEY_PROFILE,
                MediaCodecInfo.CodecProfileLevel.AVCProfileConstrainedBaseline
            )
            if (constrainedBaselineLevel != null) {
                format.setInteger(MediaFormat.KEY_LEVEL, constrainedBaselineLevel)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            format.setFloat(MediaFormat.KEY_MAX_FPS_TO_ENCODER, fps.toFloat())
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
        }
    }

    private const val MAX_CANDIDATES = 3
}
