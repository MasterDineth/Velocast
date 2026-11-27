package com.mastersoft.velocast.server

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.UndeclaredThrowableException
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CapabilityProbeTest {

    @Test
    fun runsAllSevenCapabilitiesInOrderAndPrintsOneJsonLinePerCapability() {
        val backend = FakeProbeBackend(
            virtualDisplay = { "DisplayManager (720x1280)" },
            inputInjection = {
                failWith(
                    InvocationTargetException(
                        SecurityException(
                            "Injecting input events requires the caller to have the INJECT_EVENTS permission."
                        )
                    )
                )
            },
            notificationShade = { "IStatusBarService" },
            rotation = { "IWindowManager.freezeRotation" },
            displayPower = {
                failWith(UnsatisfiedLinkError("dlopen failed for DisplayControl"))
            },
            wakeKey = { failWith(ProbeDeniedException("android.permission.INJECT_EVENTS")) },
            clipboard = { "IClipboard (setPrimaryClip(5))" }
        )

        val outBytes = ByteArrayOutputStream()
        val records = CapabilityProbe(backend).runAll(
            PrintStream(outBytes, true, StandardCharsets.UTF_8.name())
        )

        assertEquals(CapabilityId.entries, records.map { it.capability })
        assertEquals(ProbeResult.Supported, records[0].result)
        assertEquals("DisplayManager (720x1280)", records[0].detail)
        assertEquals(ProbeResult.Denied("INJECT_EVENTS"), records[1].result)
        assertEquals(ProbeResult.Supported, records[2].result)
        assertEquals(ProbeResult.Supported, records[3].result)
        assertEquals(
            ProbeResult.Unsupported("UnsatisfiedLinkError: dlopen failed for DisplayControl"),
            records[4].result
        )
        assertEquals(ProbeResult.Denied("android.permission.INJECT_EVENTS"), records[5].result)
        assertEquals(ProbeResult.Supported, records[6].result)

        val lines = outBytes.toString(StandardCharsets.UTF_8.name())
            .trimEnd()
            .split('\n')
        assertEquals(7, lines.size)
        assertTrue(lines[0].contains("\"capability\":\"virtual_display\""))
        assertTrue(lines[0].contains("\"status\":\"supported\""))
        assertTrue(lines[0].contains("\"sdk\":36"))
        assertTrue(lines[0].contains("\"uid\":2000"))
        assertTrue(lines[0].contains("\"package\":\"com.android.shell\""))
        assertTrue(lines[0].contains("\"model\":\"PixelTest\""))

        assertTrue(lines[1].contains("\"capability\":\"input_injection\""))
        assertTrue(lines[1].contains("\"status\":\"denied\""))
        assertTrue(lines[1].contains("\"permission\":\"INJECT_EVENTS\""))

        assertTrue(lines[4].contains("\"capability\":\"display_power\""))
        assertTrue(lines[4].contains("\"status\":\"unsupported\""))
        assertTrue(
            lines[4].contains(
                "\"reason\":\"UnsatisfiedLinkError: dlopen failed for DisplayControl\""
            )
        )
    }

    @Test
    fun mapsReflectionWrappedAndCauseChainedExceptionsToDeniedOrUnsupported() {
        val qualified = InvocationTargetException(
            SecurityException(
                "Permission Denial: requires android.permission.STATUS_BAR_SERVICE from pid=1"
            )
        )
        assertEquals(
            ProbeResult.Denied("android.permission.STATUS_BAR_SERVICE"),
            CapabilityProbe.mapThrowable(qualified)
        )

        val wrappedSecurity = UnsupportedPlatformException(
            "DisplayManager.createVirtualDisplay unsupported on API 36",
            InvocationTargetException(
                SecurityException("Requires android.permission.CAPTURE_VIDEO_OUTPUT")
            )
        )
        assertEquals(
            ProbeResult.Denied("android.permission.CAPTURE_VIDEO_OUTPUT"),
            CapabilityProbe.mapThrowable(wrappedSecurity)
        )

        val undeclared = UndeclaredThrowableException(
            InvocationTargetException(InjectionDeniedException("InputManager returned false"))
        )
        assertEquals(
            ProbeResult.Denied("InputManager returned false"),
            CapabilityProbe.mapThrowable(undeclared)
        )

        val genericSecurity = SecurityException("Caller not allowed")
        assertEquals(
            ProbeResult.Denied("Caller not allowed"),
            CapabilityProbe.mapThrowable(genericSecurity)
        )

        val missingMethod = InvocationTargetException(NoSuchMethodException("createVirtualDisplay"))
        assertEquals(
            ProbeResult.Unsupported("NoSuchMethodException: createVirtualDisplay"),
            CapabilityProbe.mapThrowable(missingMethod)
        )

        val platformUnsupported = UnsupportedPlatformException("DisplayControl absent on API 36")
        assertEquals(
            ProbeResult.Unsupported("DisplayControl absent on API 36"),
            CapabilityProbe.mapThrowable(platformUnsupported)
        )

        val illegalState = IllegalStateException()
        assertEquals(
            ProbeResult.Unsupported("IllegalStateException"),
            CapabilityProbe.mapThrowable(illegalState)
        )
    }

    @Test
    fun escapesControlCharactersAndQuotesInJsonLines() {
        val record = CapabilityProbeRecord(
            capability = CapabilityId.CLIPBOARD,
            result = ProbeResult.Unsupported("bad \"quote\" \\ slash\nsecond line\t\u0007"),
            detail = "line1\r\nline2"
        )
        val env = ProbeEnvironmentInfo(
            sdkInt = 35,
            uid = 2000,
            packageName = "com.android.shell",
            model = "Test\"Model"
        )

        val json = record.toJsonLine(env)
        assertFalse(json.contains('\n'))
        assertFalse(json.contains('\r'))
        assertTrue(json.contains("bad \\\"quote\\\" \\\\ slash\\nsecond line\\t\\u0007"))
        assertTrue(json.contains("\"detail\":\"line1\\r\\nline2\""))
        assertTrue(json.contains("\"model\":\"Test\\\"Model\""))
    }

    @Test
    fun parsesCapabilityProbeMainArgsAndRejectsInvalidFlags() {
        val defaultArgs = CapabilityProbeMain.Args.parse(emptyArray())
        assertEquals(null, defaultArgs.outPath)
        assertEquals(LogLevel.WARN, defaultArgs.logLevel)

        val custom = CapabilityProbeMain.Args.parse(
            arrayOf("--out", "/data/local/tmp/probe.json", "--log-level", "debug")
        )
        assertEquals("probe.json", custom.outPath?.fileName?.toString())
        assertEquals(LogLevel.DEBUG, custom.logLevel)

        for (bad in listOf(
            arrayOf("--out"),
            arrayOf("--out", "  "),
            arrayOf("--log-level"),
            arrayOf("--bogus")
        )) {
            try {
                CapabilityProbeMain.Args.parse(bad)
                fail("Expected IllegalArgumentException for ${bad.joinToString(" ")}")
            } catch (_: IllegalArgumentException) {
                // expected
            }
        }
    }

    private class FakeProbeBackend(
        private val virtualDisplay: () -> String? = { "ok" },
        private val inputInjection: () -> String? = { "ok" },
        private val notificationShade: () -> String? = { "ok" },
        private val rotation: () -> String? = { "ok" },
        private val displayPower: () -> String? = { "ok" },
        private val wakeKey: () -> String? = { "ok" },
        private val clipboard: () -> String? = { "ok" }
    ) : CapabilityProbeBackend {
        override val environmentInfo: ProbeEnvironmentInfo = ProbeEnvironmentInfo(
            sdkInt = 36,
            uid = 2000,
            packageName = "com.android.shell",
            model = "PixelTest"
        )

        override fun probeVirtualDisplay(): String? = virtualDisplay()
        override fun probeInputInjection(): String? = inputInjection()
        override fun probeNotificationShade(): String? = notificationShade()
        override fun probeRotation(): String? = rotation()
        override fun probeDisplayPower(): String? = displayPower()
        override fun probeWakeKey(): String? = wakeKey()
        override fun probeClipboard(): String? = clipboard()
    }

    private fun failWith(t: Throwable): Nothing = throw t
}
