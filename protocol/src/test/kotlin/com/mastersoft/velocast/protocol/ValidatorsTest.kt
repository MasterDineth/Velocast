package com.mastersoft.velocast.protocol

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ValidatorsTest {
    private fun expect(code: ErrorCode, block: () -> Unit) {
        try {
            block()
        } catch (e: ProtocolException) {
            assertEquals(code, e.code)
            return
        }
        fail("expected $code")
    }

    @Test
    fun pointerIds() {
        assertEquals(0, Validators.pointerId(0))
        assertEquals(31, Validators.pointerId(31))
        expect(ErrorCode.INVALID_POINTER) { Validators.pointerId(-1) }
        expect(ErrorCode.INVALID_POINTER) { Validators.pointerId(32) }
    }

    @Test
    fun coordinates() {
        val good = floatArrayOf(0f, 1f, 0.5f, Float.MIN_VALUE, Math.nextDown(1f), -0.0f)
        for (ok in good) Validators.coordinate(ok)
        val badValues = floatArrayOf(
            Float.NaN,
            Float.POSITIVE_INFINITY,
            Float.NEGATIVE_INFINITY,
            -1e-6f,
            Math.nextUp(1f),
            2f
        )
        for (bad in badValues) {
            expect(ErrorCode.INVALID_COORDINATE) { Validators.coordinate(bad) }
        }
    }

    @Test
    fun bitrateFpsScale() {
        Validators.bitrateBps(1_000_000)
        Validators.bitrateBps(40_000_000)
        expect(ErrorCode.VALUE_OUT_OF_RANGE) { Validators.bitrateBps(999_999) }
        expect(ErrorCode.VALUE_OUT_OF_RANGE) { Validators.bitrateBps(40_000_001) }
        expect(ErrorCode.VALUE_OUT_OF_RANGE) { Validators.bitrateBps(Int.MIN_VALUE) }
        Validators.fps(15f)
        Validators.fps(120f)
        for (bad in floatArrayOf(14.99f, 120.01f, 59.5f, Float.NaN, Float.POSITIVE_INFINITY, 0f)) {
            expect(ErrorCode.VALUE_OUT_OF_RANGE) { Validators.fps(bad) }
        }
        Validators.scale(0.5f)
        Validators.scale(1.0f)
        for (bad in floatArrayOf(0.4999f, 1.0001f, Float.NaN, Float.NEGATIVE_INFINITY, 0f)) {
            expect(ErrorCode.VALUE_OUT_OF_RANGE) { Validators.scale(bad) }
        }
    }

    @Test
    fun keyAllowList() {
        val required = listOf(
            KeyCodes.BACK, KeyCodes.HOME, KeyCodes.APP_SWITCH,
            KeyCodes.VOLUME_UP, KeyCodes.VOLUME_DOWN,
            KeyCodes.POWER, KeyCodes.WAKEUP, KeyCodes.ENTER, KeyCodes.DEL
        )
        for (code in required) assertTrue(KeyCodes.isAllowed(code))
        for (code in 7..16) {
            assertTrue("digit $code", KeyCodes.isAllowed(code))
        }
        for (code in 29..54) assertTrue("letter $code", KeyCodes.isAllowed(code))
        assertTrue(KeyCodes.isAllowed(62)) // SPACE
        val forbidden = listOf(
            0, 1, 2, 5, 6, 27, 79, 82, 85, 164,
            255, 256, 1000, -1, Int.MAX_VALUE, Int.MIN_VALUE
        )
        for (code in forbidden) {
            assertFalse("key $code", KeyCodes.isAllowed(code))
            expect(ErrorCode.KEY_NOT_ALLOWED) { KeyCodes.require(code) }
        }
        assertEquals(KeyCodes.allKeyCodes.sorted(), KeyCodes.allKeyCodes)
        assertEquals(KeyCodes.allKeyCodes.toSet().size, KeyCodes.allKeyCodes.size)
    }
}

class Utf8Test {
    private fun reference(bytes: ByteArray): Boolean = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
        bytes.none { it.toInt() == 0 }
    } catch (_: CharacterCodingException) {
        false
    }

    @Test
    fun agreesWithTheJdkDecoderOnRandomInput() {
        val rnd = Random(7)
        val interesting = intArrayOf(
            0x00, 0x41, 0x7F, 0x80, 0xBF, 0xC0, 0xC1, 0xC2,
            0xDF, 0xE0, 0xED, 0xEF, 0xF0, 0xF4, 0xF5, 0xFF
        )
        repeat(300_000) {
            val len = rnd.nextInt(0, 9)
            val bytes = ByteArray(len) {
                val pick = if (rnd.nextBoolean()) interesting.random(rnd) else rnd.nextInt(256)
                pick.toByte()
            }
            val expected = reference(bytes)
            val message = "expected $expected for ${VectorSupport.toHex(bytes)}"
            assertEquals(message, expected, Utf8.isValid(bytes, 0, len))
        }
    }

    @Test
    fun acceptsValidTextOfEveryLength() {
        val rnd = Random(8)
        repeat(20_000) {
            val bytes = Gen.string(rnd, 64).toByteArray(Charsets.UTF_8)
            assertTrue(Utf8.isValid(bytes, 0, bytes.size))
        }
    }

    @Test
    fun honoursOffsetAndLength() {
        val bytes = byteArrayOf(0xFF.toByte(), 0x61, 0x62, 0xFF.toByte())
        assertTrue(Utf8.isValid(bytes, 1, 2))
        assertFalse(Utf8.isValid(bytes, 0, 2))
        assertTrue(Utf8.isValid(bytes, 1, 0))
    }
}

class TrackerTest {
    private fun expect(code: ErrorCode, block: () -> Unit) {
        try {
            block()
        } catch (e: ProtocolException) {
            assertEquals(code, e.code)
            return
        }
        fail("expected $code")
    }

    @Test
    fun pointerStateMachine() {
        val t = PointerStateTracker()
        expect(ErrorCode.INVALID_POINTER_STATE) { t.onTouch(0, TouchAction.MOVE) }
        expect(ErrorCode.INVALID_POINTER_STATE) { t.onTouch(0, TouchAction.UP) }
        t.onTouch(0, TouchAction.DOWN)
        expect(ErrorCode.INVALID_POINTER_STATE) { t.onTouch(0, TouchAction.DOWN) }
        t.onTouch(31, TouchAction.DOWN)
        t.onTouch(31, TouchAction.MOVE)
        assertEquals(2, t.activeCount)
        t.onTouch(0, TouchAction.UP)
        assertFalse(t.isDown(0))
        assertTrue(t.isDown(31))
        t.onTouch(0, TouchAction.DOWN)
        t.onTouch(7, TouchAction.CANCEL)
        assertEquals(0, t.activeCount)
        expect(ErrorCode.INVALID_POINTER) { t.onTouch(32, TouchAction.DOWN) }
    }

    @Test
    fun sequenceGaps() {
        val t = SeqTracker()
        assertEquals(0, t.onSeq(100))
        assertEquals(0, t.onSeq(101))
        assertEquals(3, t.onSeq(105))
        assertEquals(0, t.onSeq(106))
        expect(ErrorCode.MALFORMED_MESSAGE) { t.onSeq(106) }
        t.reset()
        assertEquals(0, t.onSeq(65_535))
        assertEquals(0, t.onSeq(0))
        assertEquals(1, t.onSeq(2))
    }
}
