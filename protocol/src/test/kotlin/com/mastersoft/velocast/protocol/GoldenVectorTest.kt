package com.mastersoft.velocast.protocol

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GoldenVectorTest {
    class Vector(
        val name: String,
        val direction: String,
        val phase: Phase,
        val bytes: ByteArray,
        val expected: String
    ) {
        val typeName: String get() = expected.substringBefore(' ')
    }

    companion object {
        fun load(): List<Vector> {
            val stream = GoldenVectorTest::class.java.getResourceAsStream("/golden-vectors.txt")
                ?: error("golden-vectors.txt missing")
            return stream.bufferedReader().readLines()
                .filter { it.isNotBlank() && !it.startsWith("#") }
                .map { line ->
                    val parts = line.split(" | ")
                    check(parts.size == 5) { "bad vector line: $line" }
                    Vector(
                        parts[0],
                        parts[1],
                        VectorSupport.phaseOf(parts[2]),
                        VectorSupport.hexToBytes(parts[3]),
                        parts[4]
                    )
                }
        }
    }

    private val vectors = load()

    @Test
    fun everyVectorDecodesToItsDescription() {
        for (v in vectors) {
            assertEquals(v.name, v.expected, VectorSupport.describe(v.direction, v.phase, v.bytes))
        }
    }

    @Test
    fun everyVectorWritesBackToTheSameBytes() {
        for (v in vectors) {
            val again = VectorSupport.reencode(v.direction, v.phase, v.bytes)
            assertArrayEquals(v.name, v.bytes, again)
        }
    }

    @Test
    fun everyMessageTypeAndDirectionHasAVector() {
        val have = vectors.map { it.direction to it.typeName }.toSet()
        for (type in MessageType.entries) {
            if (type.clientToServer !=
                null
            ) {
                assertTrue("c2s $type", (VectorSupport.C2S to type.name) in have)
            }
            if (type.serverToClient !=
                null
            ) {
                assertTrue("s2c $type", (VectorSupport.S2C to type.name) in have)
            }
        }
    }

    @Test
    fun vectorNamesAreUnique() {
        assertEquals(vectors.size, vectors.map { it.name }.toSet().size)
    }

    private fun vector(name: String): ByteArray = vectors.first { it.name == name }.bytes

    private fun written(seq: Int, block: (ControlWriter) -> Unit): ByteArray {
        val out = ByteArrayOutputStream()
        val writer = ControlWriter(out)
        writer.phase = Phase.SESSION
        writer.nextSeq = seq
        block(writer)
        return out.toByteArray()
    }

    @Test
    fun convenienceWritersProduceTheGoldenBytes() {
        assertArrayEquals(
            vector("touch-down"),
            written(1) { it.touch(0, TouchAction.DOWN, 0.5f, 0.25f) }
        )
        assertArrayEquals(
            vector("key-back-down"),
            written(5) {
                it.key(KeyCodes.BACK, KeyAction.DOWN)
            }
        )
        assertArrayEquals(
            vector("config-stream"),
            written(7) { it.configStream(8_000_000, 60f, 0.75f) }
        )
        assertArrayEquals(
            vector("keyframe-req"),
            written(8) {
                it.request(MessageType.KEYFRAME_REQ)
            }
        )
        assertArrayEquals(vector("action-rotate"), written(11) { it.action(DeviceAction.ROTATE) })
        assertArrayEquals(vector("ping"), written(12) { it.ping(0x01020304) })
        assertArrayEquals(vector("bye-link-lost"), written(14) { it.bye(ErrorCode.LINK_LOST) })
        assertArrayEquals(vector("text-hi"), written(15) { it.writePayload(TextInput("Hi")) })
        assertArrayEquals(
            vector("clipboard-paste"),
            written(16) { it.writePayload(ClipboardMessage(true, "ok")) }
        )
    }
}
