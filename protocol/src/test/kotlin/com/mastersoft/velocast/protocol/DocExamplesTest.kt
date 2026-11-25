package com.mastersoft.velocast.protocol

import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The protocol document is part of the contract: its worked examples and tables must be true. */
class DocExamplesTest {
    private class DocVector(
        val name: String,
        val direction: String,
        val phase: Phase,
        val bytes: ByteArray,
        val expected: String
    )

    private val lines: List<String> = File(
        System.getProperty("velocast.protocolDoc") ?: error("velocast.protocolDoc not set")
    ).readLines(Charsets.UTF_8)

    private fun section(name: String): List<String> {
        val begin = lines.indexOfFirst { it.trim() == "<!-- begin:$name -->" }
        val end = lines.indexOfFirst { it.trim() == "<!-- end:$name -->" }
        assertTrue("section $name missing in docs/protocol.md", begin >= 0 && end > begin)
        return lines.subList(begin + 1, end)
    }

    private fun vectors(): List<DocVector> {
        val marker = Regex("""<!-- vector (\S+) (c2s|s2c) (handshake|session) -->""")
        val result = mutableListOf<DocVector>()
        var i = 0
        while (i < lines.size) {
            val m = marker.matchEntire(lines[i].trim())
            if (m == null) {
                i++
                continue
            }
            while (!lines[i].trim().startsWith("```")) i++
            i++
            val hex = StringBuilder()
            var expected = ""
            while (!lines[i].trim().startsWith("```")) {
                val line = lines[i].trim()
                if (line.startsWith(
                        "="
                    )
                ) {
                    expected = line.removePrefix("=").trim()
                } else {
                    hex.append(line).append(' ')
                }
                i++
            }
            result += DocVector(
                m.groupValues[1],
                m.groupValues[2],
                VectorSupport.phaseOf(m.groupValues[3]),
                VectorSupport.hexToBytes(hex.toString()),
                expected
            )
        }
        return result
    }

    @Test
    fun workedExamplesDecodeToTheDocumentedFields() {
        val docVectors = vectors()
        assertTrue(
            "expected at least 20 worked examples, found ${docVectors.size}",
            docVectors.size >= 20
        )
        for (v in docVectors) {
            assertEquals(v.name, v.expected, VectorSupport.describe(v.direction, v.phase, v.bytes))
        }
    }

    @Test
    fun workedExamplesWriteBackToTheDocumentedBytes() {
        for (v in vectors()) {
            assertArrayEquals(
                v.name,
                v.bytes,
                VectorSupport.reencode(v.direction, v.phase, v.bytes)
            )
        }
    }

    @Test
    fun everyDocumentedExampleIsAGoldenVectorToo() {
        val golden = GoldenVectorTest.load().map {
            it.direction to VectorSupport.toHex(it.bytes)
        }.toSet()
        for (v in vectors()) {
            assertTrue(
                "${v.name} is not in golden-vectors.txt",
                (v.direction to VectorSupport.toHex(v.bytes)) in golden
            )
        }
    }

    @Test
    fun everyMessageTypeAndDirectionHasAWorkedExample() {
        val have = vectors().map { it.direction to it.expected.substringBefore(' ') }.toSet()
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

    private fun rows(name: String, pattern: Regex): List<List<String>> = section(name).mapNotNull {
        pattern.find(it)?.groupValues?.drop(1)
    }

    @Test
    fun messageTypeTableMatchesTheCode() {
        val rows =
            rows(
                "message-types",
                Regex("""^\|\s*0x([0-9A-Fa-f]{2})\s*\|\s*`([A-Z_]+)`\s*\|\s*(c2s|s2c|both)\s*\|""")
            )
        assertEquals(MessageType.entries.size, rows.size)
        for ((id, name, direction) in rows) {
            val type = MessageType.valueOf(name)
            assertEquals(name, type.id, id.toInt(16))
            val expected = when {
                type.clientToServer != null && type.serverToClient != null -> "both"
                type.clientToServer != null -> "c2s"
                else -> "s2c"
            }
            assertEquals(name, expected, direction)
        }
    }

    @Test
    fun errorCodeTableMatchesTheCode() {
        val rows = rows("error-codes", Regex("""^\|\s*(\d+)\s*\|\s*`([A-Z_]+)`\s*\|"""))
        assertEquals(
            ErrorCode.entries.map {
                it.id to it.name
            },
            rows.map { it[0].toInt() to it[1] }
        )
    }

    @Test
    fun flagTableMatchesTheCode() {
        val rows = rows("flags", Regex("""^\|\s*0x([0-9A-Fa-f]{2})\s*\|\s*`([A-Z_]+)`\s*\|"""))
        val actual = mapOf(
            "KEYFRAME" to FrameFlags.KEYFRAME,
            "CONFIG" to FrameFlags.CONFIG,
            "RESET" to FrameFlags.RESET,
            "ERROR" to FrameFlags.ERROR
        )
        assertEquals(actual, rows.associate { it[1] to it[0].toInt(16) })
    }

    @Test
    fun keyAllowListMatchesTheCode() {
        val rows = rows("key-codes", Regex("""^\|\s*(\d+)\s*\|\s*`([A-Z_0-9]+)`\s*\|"""))
        assertEquals(KeyCodes.allKeyCodes, rows.map { it[0].toInt() }.sorted())
    }

    @Test
    fun enumTablesMatchTheCode() {
        val ids = Regex("""^\|\s*(\d+)\s*\|\s*`([A-Z_0-9]+)`\s*\|""")
        assertEquals(
            TouchAction.entries.map { it.id to it.name },
            rows("touch-actions", ids).map {
                it[0].toInt() to
                    it[1]
            }
        )
        assertEquals(
            KeyAction.entries.map { it.id to it.name },
            rows("key-actions", ids).map {
                it[0].toInt() to
                    it[1]
            }
        )
        assertEquals(
            DeviceAction.entries.map {
                it.id to it.name
            },
            rows("device-actions", ids).map {
                it[0].toInt() to
                    it[1]
            }
        )
        assertEquals(
            StatusKind.entries.map { it.id to it.name },
            rows("status-kinds", ids).map {
                it[0].toInt() to
                    it[1]
            }
        )
        assertEquals(
            Codec.entries.map { it.id to it.name },
            rows("codecs", ids).map {
                it[0].toInt() to
                    it[1]
            }
        )
        assertEquals(
            LaunchMode.entries.map { it.id to it.name },
            rows("launch-modes", ids).map {
                it[0].toInt() to
                    it[1]
            }
        )
    }

    @Test
    fun limitsTableMatchesTheCode() {
        val rows = rows("limits", Regex("""^\|\s*`([A-Z_0-9]+)`\s*\|\s*(\d+)\s*\|"""))
        assertTrue(rows.size >= 15)
        for ((name, value) in rows) {
            val actual = ProtocolV2::class.java.getField(name).getInt(null)
            assertEquals(name, value.toInt(), actual)
        }
    }
}
