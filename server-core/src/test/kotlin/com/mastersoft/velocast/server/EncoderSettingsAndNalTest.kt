package com.mastersoft.velocast.server

import com.mastersoft.velocast.protocol.ProtocolV2
import com.mastersoft.velocast.protocol.Status
import com.mastersoft.velocast.protocol.StatusKind
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class EncoderSettingsAndNalTest {
    @Test
    fun defaultsToCbrAndSupportsVbrSwitchAndBufferSizeCli() {
        assertEquals(BitrateMode.CBR, BitrateMode.DEFAULT)
        assertEquals("CBR", BitrateMode.DEFAULT_MODE_NAME)

        val defaultArgs = ServerArgs.parse(emptyArray())
        assertEquals(BitrateMode.CBR, defaultArgs.bitrateMode)
        assertEquals(64 * 1024, defaultArgs.sendBufferBytes)

        val customArgs = ServerArgs.parse(
            arrayOf("--bitrate-mode", "vbr", "--send-buffer-bytes", "32768")
        )
        assertEquals(BitrateMode.VBR, customArgs.bitrateMode)
        assertEquals(32_768, customArgs.sendBufferBytes)

        try {
            ServerArgs.parse(arrayOf("--bitrate-mode", "CQ"))
            fail("Expected IllegalArgumentException for unsupported bitrate mode")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun selectsConstrainedBaselineAndBitrateModeFromCapabilitiesAndFormatsStatus() {
        val fullCaps = CodecCapabilitiesSnapshot(
            supportsCbr = true,
            supportsVbr = true,
            supportsConstrainedBaseline = true,
            maxConstrainedBaselineLevel = 512
        )
        val cbrSelected = EncoderFormatBuilder.selectPrimarySettings(
            bitrateBps = 8_000_000,
            fps = 60,
            requestedMode = BitrateMode.CBR,
            caps = fullCaps,
            sdkInt = 35
        )
        assertEquals("CBR", cbrSelected.bitrateModeName)
        assertEquals("CB", cbrSelected.profileName)
        assertEquals(0, cbrSelected.maxBFrames)
        assertTrue(cbrSelected.prependHeaderToSyncFrames)
        assertTrue(cbrSelected.lowLatency)
        assertTrue(cbrSelected.realtimePriority)

        val detail = cbrSelected.toStatusDetail()
        assertTrue(detail.toByteArray(Charsets.UTF_8).size <= ProtocolV2.MAX_STATUS_DETAIL_BYTES)
        val status = Status(StatusKind.BITRATE, 8_000_000, detail)
        assertEquals(
            "mode=CBR profile=CB bframes=0 prepend=1 lowlat=1 prio=1",
            status.detail
        )

        // When CBR is unsupported, falls back to VBR; when ConstrainedBaseline is absent, uses DEFAULT.
        val vbrOnlyCaps = CodecCapabilitiesSnapshot(
            supportsCbr = false,
            supportsVbr = true,
            supportsConstrainedBaseline = false
        )
        val fallbackSelected = EncoderFormatBuilder.selectPrimarySettings(
            bitrateBps = 6_000_000,
            fps = 30,
            requestedMode = BitrateMode.CBR,
            caps = vbrOnlyCaps,
            sdkInt = 30
        )
        assertEquals("VBR", fallbackSelected.bitrateModeName)
        assertEquals("DEFAULT", fallbackSelected.profileName)
        assertEquals(0, fallbackSelected.maxBFrames)
    }

    @Test
    fun annexBNalHelperDetectsAndExtractsSpsPpsAndIdr() {
        // 4-byte start codes: SPS (0x67), PPS (0x68), IDR (0x65)
        val spsPpsIdr = byteArrayOf(
            0x00, 0x00, 0x00, 0x01, 0x67, 0x42, 0xC0.toByte(), 0x1E,
            0x00, 0x00, 0x00, 0x01, 0x68, 0xCE.toByte(), 0x3C, 0x80.toByte(),
            0x00, 0x00, 0x00, 0x01, 0x65, 0x88.toByte(), 0x84.toByte()
        )
        assertTrue(H264NalHelper.containsSpsAndPps(spsPpsIdr, 0, spsPpsIdr.size))
        assertTrue(H264NalHelper.containsIdr(spsPpsIdr, 0, spsPpsIdr.size))

        val extracted = H264NalHelper.extractSpsPps(spsPpsIdr, 0, spsPpsIdr.size)
        assertNotNull(extracted)
        assertArrayEquals(spsPpsIdr.copyOfRange(0, 16), extracted)

        // 3-byte start code P-frame slice (0x41)
        val pFrame = byteArrayOf(0x00, 0x00, 0x01, 0x41, 0x9A.toByte(), 0x24)
        assertFalse(H264NalHelper.containsSpsAndPps(pFrame, 0, pFrame.size))
        assertFalse(H264NalHelper.containsIdr(pFrame, 0, pFrame.size))
        assertNull(H264NalHelper.extractSpsPps(pFrame, 0, pFrame.size))

        // 3-byte start code IDR without SPS/PPS
        val idrOnly = byteArrayOf(0x00, 0x00, 0x01, 0x25, 0x88.toByte())
        assertFalse(H264NalHelper.containsSpsAndPps(idrOnly, 0, idrOnly.size))
        assertTrue(H264NalHelper.containsIdr(idrOnly, 0, idrOnly.size))
    }
}
