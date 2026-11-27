package com.mastersoft.velocast.server

import com.mastersoft.velocast.server.platform.SavedDeviceState
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StateRestoreAndCleanUpTest {
    @Test
    fun normalSessionEndRestoresRecordedStateAndDisarmsCleanUpGuardian() {
        val clock = FakeServerClock(1_000L)
        val logger =
            Logger(minLevel = LogLevel.DEBUG, stderr = PrintStream(ByteArrayOutputStream()))
        val env = FakeSessionEnvironment(clock, logger).apply {
            initialDisplayPowerOn = false
            displayPowerSupported = true
        }
        val socket = FakePeerSocket()
        val session = ServerSession(
            id = 1L,
            peerSocket = socket,
            authenticator = HmacSha256Authenticator(TestClientDriver.DEFAULT_SECRET),
            environment = env,
            clock = clock,
            logger = logger,
            handshakeTimer = FakeHandshakeTimer(),
            handshakeTimeoutMs = 3_000L,
            sessionIdleTimeoutMs = 5_000L,
            random = SecureRandom(),
            onActivate = { it.markActiveFromGuard() },
            onAuthFailed = {},
            onClosed = {}
        )
        val thread = Thread({ session.run() }, "test-state-restore").apply { start() }
        try {
            val (cw, _) = TestClientDriver.authenticate(socket)
            cw.bye()
            socket.clientOut.flush()
            thread.join(2_000L)

            assertEquals(1, env.createdControllers.size)
            assertEquals(1, env.createdGuardians.size)
            val expected = SavedDeviceState(
                accelerometerRotation = 1,
                userRotation = 0,
                displayPowerOn = false
            )
            assertEquals(listOf(expected), env.createdControllers[0].restoredStates)
            assertEquals(expected, env.createdGuardians[0].recordedState)
            assertTrue(env.createdGuardians[0].restoreDisplayPower)
            assertTrue(env.createdGuardians[0].disarmed.get())
            assertTrue(env.createdGuardians[0].closed.get())
        } finally {
            session.closeAndAwait()
            thread.join(2_000L)
            logger.close()
        }
    }

    @Test
    fun guardianBuildsSetsidAppProcessCommand() {
        val stateDir = Paths.get("/data/adb/velocast")
        val cmd = ProcessCleanUpGuardian.buildCommand(
            state = SavedDeviceState(
                accelerometerRotation = 0,
                userRotation = 2,
                displayPowerOn = true
            ),
            restoreDisplayPower = true,
            stateDir = stateDir
        )
        assertEquals("/system/bin/setsid", cmd[0])
        assertEquals("/system/bin/app_process", cmd[1])
        assertEquals("/system/bin", cmd[2])
        assertEquals(CleanUp::class.java.name, cmd[3])
        assertTrue(cmd.contains("--accel-rotation"))
        assertTrue(cmd.contains("--user-rotation"))
        assertTrue(cmd.contains("--display-power"))
        assertTrue(cmd.contains("--restore-display-power"))
        assertTrue(cmd.contains(stateDir.toString()))
    }

    @Test
    fun cleanUpProcessRestoresStateWhenServerPipeClosesWithoutDisarmAndSkipsWhenDisarmed() {
        val tempDir = Files.createTempDirectory("velocast-cleanup-test")
        try {
            val markerOnCrash = tempDir.resolve("restored-on-kill.txt")
            val crashProc = spawnCleanUpProcess(markerOnCrash, accel = 0, userRot = 3, power = 1)
            // Simulate abrupt kill -9 of parent server by closing stdin without sending DISARM.
            crashProc.outputStream.close()
            assertTrue("CleanUp did not exit in time", crashProc.waitFor(10, TimeUnit.SECONDS))
            assertEquals(0, crashProc.exitValue())
            assertTrue(
                "Expected CleanUp to restore state on abrupt EOF",
                Files.exists(markerOnCrash)
            )
            val text = String(Files.readAllBytes(markerOnCrash), StandardCharsets.UTF_8).trim()
            assertEquals("accel=0 user=3 power=1 restorePower=1", text)

            val markerOnDisarm = tempDir.resolve("not-restored-on-disarm.txt")
            val cleanProc = spawnCleanUpProcess(markerOnDisarm, accel = 0, userRot = 3, power = 1)
            cleanProc.outputStream.write("DISARM\n".toByteArray(StandardCharsets.UTF_8))
            cleanProc.outputStream.flush()
            cleanProc.outputStream.close()
            assertTrue("CleanUp did not exit in time", cleanProc.waitFor(10, TimeUnit.SECONDS))
            assertEquals(0, cleanProc.exitValue())
            assertFalse(
                "Expected disarmed CleanUp to skip double-restore",
                Files.exists(markerOnDisarm)
            )
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    private fun spawnCleanUpProcess(
        markerPath: java.nio.file.Path,
        accel: Int,
        userRot: Int,
        power: Int
    ): Process {
        val isWindows = System.getProperty("os.name").orEmpty()
            .lowercase(Locale.ROOT)
            .contains("win")
        val javaBin = Paths.get(
            System.getProperty("java.home"),
            "bin",
            if (isWindows) "java.exe" else "java"
        ).toString()
        val classPath = System.getProperty("java.class.path")
        return ProcessBuilder(
            javaBin,
            "-cp",
            classPath,
            CleanUp::class.java.name,
            "--accel-rotation",
            accel.toString(),
            "--user-rotation",
            userRot.toString(),
            "--display-power",
            power.toString(),
            "--restore-display-power",
            "1",
            "--test-output",
            markerPath.toString()
        ).start()
    }
}
