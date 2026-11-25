package com.mastersoft.velocast.server

import android.view.MotionEvent
import com.mastersoft.velocast.protocol.ErrorCode
import com.mastersoft.velocast.protocol.ProtocolException
import com.mastersoft.velocast.protocol.TouchAction
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.lang.management.ManagementFactory
import kotlin.random.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TouchStateMachineTest {
    private fun newInjector(): Triple<InputInjector, FakeInjectorSink, FakeServerClock> {
        val clock = FakeServerClock(100L)
        val logger = Logger(stderr = PrintStream(ByteArrayOutputStream()))
        val sink = FakeInjectorSink()
        val injector = InputInjector(1000, 2000, sink, clock, logger)
        return Triple(injector, sink, clock)
    }

    @Test
    fun rejectsUpAndMoveForUnknownPointersAndDuplicateDown() {
        val (injector, sink, _) = newInjector()

        expectProtocolError(ErrorCode.INVALID_POINTER_STATE) {
            injector.handleTouch(0, TouchAction.UP, 0.5f, 0.5f)
        }
        expectProtocolError(ErrorCode.INVALID_POINTER_STATE) {
            injector.handleTouch(1, TouchAction.MOVE, 0.5f, 0.5f)
        }
        assertEquals(0, sink.touchCalls.size)

        injector.handleTouch(0, TouchAction.DOWN, 0.25f, 0.5f)
        expectProtocolError(ErrorCode.INVALID_POINTER_STATE) {
            injector.handleTouch(0, TouchAction.DOWN, 0.3f, 0.5f)
        }
        expectProtocolError(ErrorCode.INVALID_POINTER_STATE) {
            injector.handleTouch(2, TouchAction.UP, 0.5f, 0.5f)
        }
        expectProtocolError(ErrorCode.INVALID_POINTER_STATE) {
            injector.handleTouch(2, TouchAction.MOVE, 0.5f, 0.5f)
        }
        assertEquals(1, sink.touchCalls.size)
        assertEquals(1, injector.activePointerCount)
    }

    @Test
    fun multiTouchTransitionsCompactSlotsAndEmitCorrectPointerIndices() {
        val (injector, sink, clock) = newInjector()

        injector.handleTouch(2, TouchAction.DOWN, 0.1f, 0.2f)
        clock.advanceBy(10L)
        injector.handleTouch(5, TouchAction.DOWN, 0.3f, 0.4f)
        clock.advanceBy(10L)
        injector.handleTouch(2, TouchAction.MOVE, 0.15f, 0.25f)
        clock.advanceBy(10L)
        // Pointer 2 is at index 0; lifting it emits POINTER_UP with index 0 and compacts slot 0 <- 5
        injector.handleTouch(2, TouchAction.UP, 0.15f, 0.25f)
        clock.advanceBy(10L)
        injector.handleTouch(5, TouchAction.UP, 0.35f, 0.45f)

        assertEquals(0, injector.activePointerCount)
        assertEquals(5, sink.touchCalls.size)

        assertEquals(MotionEvent.ACTION_DOWN, sink.touchCalls[0].action)
        assertArrayEquals(intArrayOf(2), sink.touchCalls[0].idsSnapshot)

        val expectedPointerDown = MotionEvent.ACTION_POINTER_DOWN or
            (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        assertEquals(expectedPointerDown, sink.touchCalls[1].action)
        assertArrayEquals(intArrayOf(2, 5), sink.touchCalls[1].idsSnapshot)

        assertEquals(MotionEvent.ACTION_MOVE, sink.touchCalls[2].action)
        assertEquals(150f, sink.touchCalls[2].xSnapshot[0], 0.01f)
        assertEquals(500f, sink.touchCalls[2].ySnapshot[0], 0.01f)

        val expectedPointerUp = MotionEvent.ACTION_POINTER_UP or
            (0 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        assertEquals(expectedPointerUp, sink.touchCalls[3].action)
        assertArrayEquals(intArrayOf(2, 5), sink.touchCalls[3].idsSnapshot)

        assertEquals(MotionEvent.ACTION_UP, sink.touchCalls[4].action)
        assertArrayEquals(intArrayOf(5), sink.touchCalls[4].idsSnapshot)
    }

    @Test
    fun cancelAllPointersIsIdempotentAndClearsActivePointers() {
        val (injector, sink, _) = newInjector()
        injector.cancelAllPointers()
        assertEquals(0, sink.touchCalls.size)

        injector.handleTouch(0, TouchAction.DOWN, 0.5f, 0.5f)
        injector.handleTouch(3, TouchAction.DOWN, 0.6f, 0.6f)
        assertEquals(2, injector.activePointerCount)

        injector.cancelAllPointers()
        assertEquals(0, injector.activePointerCount)
        assertEquals(MotionEvent.ACTION_CANCEL, sink.touchCalls.last().action)
        assertEquals(2, sink.touchCalls.last().pointerCount)

        val countAfterCancel = sink.touchCalls.size
        injector.cancelAllPointers()
        assertEquals(countAfterCancel, sink.touchCalls.size)
    }

    @Test
    fun randomizedPropertyTestMatchesReferencePointerModel() {
        val (injector, sink, _) = newInjector()
        val rng = Random(42)
        val model = LinkedHashMap<Int, Pair<Float, Float>>()

        repeat(10_000) {
            val pointer = rng.nextInt(32)
            val action = TouchAction.entries[rng.nextInt(TouchAction.entries.size)]
            val x = rng.nextFloat()
            val y = rng.nextFloat()

            val expectedValid = when (action) {
                TouchAction.DOWN -> !model.containsKey(pointer)
                TouchAction.MOVE, TouchAction.UP -> model.containsKey(pointer)
                TouchAction.CANCEL -> true
            }

            if (expectedValid) {
                injector.handleTouch(pointer, action, x, y)
                when (action) {
                    TouchAction.DOWN, TouchAction.MOVE -> model[pointer] =
                        Pair(x * 1000f, y * 2000f)

                    TouchAction.UP -> model.remove(pointer)

                    TouchAction.CANCEL -> model.clear()
                }
            } else {
                expectProtocolError(ErrorCode.INVALID_POINTER_STATE) {
                    injector.handleTouch(pointer, action, x, y)
                }
            }

            assertEquals(model.size, injector.activePointerCount)
            for (p in 0..31) {
                assertEquals(model.containsKey(p), injector.isPointerDown(p))
            }
            if (model.isNotEmpty() && sink.touchCalls.isNotEmpty()) {
                val last = sink.touchCalls.last()
                val masked = last.action and MotionEvent.ACTION_MASK
                if (masked == MotionEvent.ACTION_POINTER_UP) {
                    assertEquals(model.size + 1, last.pointerCount)
                } else if (masked != MotionEvent.ACTION_CANCEL) {
                    assertEquals(model.size, last.pointerCount)
                }
            }
        }
    }

    @Test
    fun reusesPrimitiveArraysWithZeroPerEventAllocation() {
        var firstIdsRef: IntArray? = null
        var firstXRef: FloatArray? = null
        var firstYRef: FloatArray? = null

        val countingSink = object : InjectorSink {
            var count = 0
            override fun injectTouch(
                downTimeMs: Long,
                eventTimeMs: Long,
                action: Int,
                pointerCount: Int,
                pointerIds: IntArray,
                xCoords: FloatArray,
                yCoords: FloatArray
            ) {
                if (firstIdsRef == null) {
                    firstIdsRef = pointerIds
                    firstXRef = xCoords
                    firstYRef = yCoords
                } else {
                    assertSame(firstIdsRef, pointerIds)
                    assertSame(firstXRef, xCoords)
                    assertSame(firstYRef, yCoords)
                }
                count++
            }

            override fun injectKey(downTimeMs: Long, eventTimeMs: Long, action: Int, keyCode: Int) =
                Unit

            override fun expandNotificationShade() = Unit
            override fun collapseNotificationShade() = Unit
            override fun toggleScreenRotation() = Unit
        }

        val zeroAllocInjector = InputInjector(
            1000,
            2000,
            countingSink,
            FakeServerClock(100L),
            Logger(stderr = PrintStream(ByteArrayOutputStream()))
        )
        zeroAllocInjector.handleTouch(0, TouchAction.DOWN, 0.1f, 0.1f)
        zeroAllocInjector.handleTouch(1, TouchAction.DOWN, 0.2f, 0.2f)
        repeat(1_000) {
            zeroAllocInjector.handleTouch(0, TouchAction.MOVE, 0.2f, 0.3f)
        }

        val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean

        @Suppress("DEPRECATION")
        fun allocatedNow() = threads.getThreadAllocatedBytes(Thread.currentThread().id)

        val before = allocatedNow()
        repeat(50_000) { i ->
            val coord = (i % 100) / 100f
            zeroAllocInjector.handleTouch(0, TouchAction.MOVE, coord, coord)
        }
        val allocated = allocatedNow() - before
        assertTrue("Allocated $allocated bytes across 50k MOVE events", allocated < 64 * 1024L)
        assertEquals(51_002, countingSink.count)
    }

    private fun expectProtocolError(expected: ErrorCode, block: () -> Unit) {
        try {
            block()
            fail("Expected ProtocolException($expected)")
        } catch (e: ProtocolException) {
            assertEquals(expected, e.code)
        }
    }
}
