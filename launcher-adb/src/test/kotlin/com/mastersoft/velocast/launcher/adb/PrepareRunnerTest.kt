package com.mastersoft.velocast.launcher.adb

import java.io.File
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrepareRunnerTest {

    @Test
    fun `runs all 5 steps in order and reports Done then AlreadyDone on second pass`() =
        runBlocking {
            val fakeAdb = FakePrepareAdbClient(hostInstalled = true)
            val runner = PrepareRunner(fakeAdb)

            val firstReport = runner.runAll()
            assertEquals(PrepareStepId.entries, firstReport.results.map { it.id })
            assertTrue(firstReport.isAllSatisfied)
            assertTrue(firstReport.results.all { it.status == PrepareStepStatus.Done })
            assertTrue(firstReport.results.all { it.needsDeviceConfirmation })
            assertEquals(
                setOf(
                    "stay_on_while_plugged_in",
                    "adb_allowed_connection_time",
                    "grant_write_secure_settings",
                    "battery_whitelist",
                    "run_any_in_background"
                ),
                firstReport.preparedItemKeys
            )
            assertTrue(
                fakeAdb.commands.any {
                    it == "settings put global stay_on_while_plugged_in 7"
                }
            )
            assertTrue(
                fakeAdb.commands.any {
                    it == "settings put global adb_allowed_connection_time 0"
                }
            )
            assertTrue(
                fakeAdb.commands.any {
                    it == "pm grant com.mastersoft.velocast.host " +
                        "android.permission.WRITE_SECURE_SETTINGS"
                }
            )
            assertTrue(
                fakeAdb.commands.any {
                    it == "cmd deviceidle whitelist +com.mastersoft.velocast.host"
                }
            )
            assertTrue(
                fakeAdb.commands.any {
                    it == "cmd appops set com.mastersoft.velocast.host " +
                        "RUN_ANY_IN_BACKGROUND allow"
                }
            )

            fakeAdb.commands.clear()
            val secondReport = runner.runAll()
            assertTrue(secondReport.isAllSatisfied)
            assertTrue(secondReport.results.all { it.status == PrepareStepStatus.AlreadyDone })
            assertFalse(fakeAdb.commands.any { it.startsWith("settings put ") })
            assertFalse(fakeAdb.commands.any { it.startsWith("pm grant ") })
            assertFalse(fakeAdb.commands.any { it.contains("whitelist +") })
            assertFalse(fakeAdb.commands.any { it.startsWith("cmd appops set ") })
        }

    @Test
    fun `reports NeedsUser for host steps when Host app is not installed`() = runBlocking {
        val fakeAdb = FakePrepareAdbClient(hostInstalled = false)
        val runner = PrepareRunner(fakeAdb)

        val report = runner.runAll()
        assertFalse(report.isAllSatisfied)
        val byId = report.results.associateBy { it.id }
        assertEquals(PrepareStepStatus.Done, byId[PrepareStepId.STAY_ON_WHILE_PLUGGED_IN]?.status)
        assertEquals(PrepareStepStatus.Done, byId[PrepareStepId.DISABLE_ADB_AUTH_TIMEOUT]?.status)
        assertTrue(
            byId[PrepareStepId.GRANT_WRITE_SECURE_SETTINGS]?.status is PrepareStepStatus.NeedsUser
        )
        assertTrue(byId[PrepareStepId.BATTERY_WHITELIST]?.status is PrepareStepStatus.NeedsUser)
        assertTrue(
            byId[PrepareStepId.ALLOW_RUN_ANY_IN_BACKGROUND]?.status is PrepareStepStatus.NeedsUser
        )
    }

    @Test
    fun `maps OEM SecurityException to NeedsUser and failed verification to Failed`() =
        runBlocking {
            val fakeAdb = FakePrepareAdbClient(
                hostInstalled = true,
                grantSecurityException = true,
                failVerifyWhitelist = true
            )
            val runner = PrepareRunner(fakeAdb)

            val report = runner.runAll()
            val byId = report.results.associateBy { it.id }

            val grantStatus = byId[PrepareStepId.GRANT_WRITE_SECURE_SETTINGS]?.status
            assertTrue(grantStatus is PrepareStepStatus.NeedsUser)
            assertTrue(
                (grantStatus as PrepareStepStatus.NeedsUser).reason.contains("USB debugging")
            )

            val whitelistStatus = byId[PrepareStepId.BATTERY_WHITELIST]?.status
            assertTrue(whitelistStatus is PrepareStepStatus.Failed)

            assertEquals(
                PrepareStepStatus.Done,
                byId[PrepareStepId.ALLOW_RUN_ANY_IN_BACKGROUND]?.status
            )
        }

    @Test
    fun `maps ADB transport error in one step to Failed and continues remaining steps`() =
        runBlocking {
            val fakeAdb = FakePrepareAdbClient(
                hostInstalled = true,
                throwOnCommandPrefix = "settings get global adb_allowed_connection_time"
            )
            val runner = PrepareRunner(fakeAdb)

            val report = runner.runAll()
            assertEquals(5, report.results.size)
            val byId = report.results.associateBy { it.id }
            assertEquals(
                PrepareStepStatus.Done,
                byId[PrepareStepId.STAY_ON_WHILE_PLUGGED_IN]?.status
            )
            assertTrue(
                byId[PrepareStepId.DISABLE_ADB_AUTH_TIMEOUT]?.status is PrepareStepStatus.Failed
            )
            assertEquals(
                PrepareStepStatus.Done,
                byId[PrepareStepId.GRANT_WRITE_SECURE_SETTINGS]?.status
            )
        }
}

private class FakePrepareAdbClient(
    private val hostInstalled: Boolean,
    private val grantSecurityException: Boolean = false,
    private val failVerifyWhitelist: Boolean = false,
    private val throwOnCommandPrefix: String? = null
) : AdbClient {
    val commands = ArrayList<String>()
    private var stayOn = "0"
    private var authTimeout = "604800000"
    private var secureGranted = false
    private var whitelisted = false
    private var runAnyAllowed = false

    override val connectedDevice: AdbConnectedDevice? =
        AdbConnectedDevice("192.168.1.42", 37199, "device::")
    override val discovery: AdbDiscovery
        get() = throw UnsupportedOperationException("Not used by PrepareRunner")

    override fun ensureKeyIdentity(alias: String): AdbKeyIdentity =
        throw UnsupportedOperationException()

    override fun recordKnownPairedHost(host: String) = Unit

    override suspend fun pair(
        host: String,
        port: Int,
        code: String,
        keyAlias: String
    ): AdbPairingResult = throw UnsupportedOperationException()

    override suspend fun pairWithQr(
        keyAlias: String,
        timeoutMs: Long,
        onQrPayload: (AdbQrPayload) -> Unit
    ): AdbPairingResult = throw UnsupportedOperationException()

    override suspend fun connect(host: String?, port: Int?, keyAlias: String): AdbConnectedDevice =
        connectedDevice!!

    override suspend fun shell(command: String): AdbShellResult {
        commands += command
        if (throwOnCommandPrefix != null && command.startsWith(throwOnCommandPrefix)) {
            throw IOException("Simulated ADB disconnect")
        }
        return handleSettingsCommand(command) ?: handleHostPackageCommand(command)
    }

    private fun handleSettingsCommand(command: String): AdbShellResult? = when {
        command == "settings get global stay_on_while_plugged_in" ->
            AdbShellResult("$stayOn\n")

        command.startsWith("settings put global stay_on_while_plugged_in ") -> {
            stayOn = command.substringAfterLast(' ')
            AdbShellResult("")
        }

        command == "settings get global adb_allowed_connection_time" ->
            AdbShellResult("$authTimeout\n")

        command.startsWith("settings put global adb_allowed_connection_time ") -> {
            authTimeout = command.substringAfterLast(' ')
            AdbShellResult("")
        }

        else -> null
    }

    private fun handleHostPackageCommand(command: String): AdbShellResult = when {
        command.startsWith("pm path ") -> if (hostInstalled) {
            AdbShellResult("package:/data/app/com.mastersoft.velocast.host/base.apk\n")
        } else {
            AdbShellResult("", exitCode = 1)
        }

        command.contains("dumpsys package") -> if (secureGranted) {
            AdbShellResult("      android.permission.WRITE_SECURE_SETTINGS: granted=true\n")
        } else {
            AdbShellResult("      android.permission.WRITE_SECURE_SETTINGS: granted=false\n")
        }

        command.startsWith("pm grant ") -> applyPmGrant()

        command == "cmd deviceidle whitelist" -> if (whitelisted) {
            AdbShellResult(
                "system,com.android.shell,2000\nuser,com.mastersoft.velocast.host,10234\n"
            )
        } else {
            AdbShellResult("system,com.android.shell,2000\n")
        }

        command.startsWith("cmd deviceidle whitelist +") -> {
            if (!failVerifyWhitelist) whitelisted = true
            AdbShellResult("Added: com.mastersoft.velocast.host\n")
        }

        command.startsWith("cmd appops get ") -> if (runAnyAllowed) {
            AdbShellResult("RUN_ANY_IN_BACKGROUND: allow\n")
        } else {
            AdbShellResult("RUN_ANY_IN_BACKGROUND: default\n")
        }

        command.startsWith("cmd appops set ") -> {
            runAnyAllowed = true
            AdbShellResult("")
        }

        else -> AdbShellResult("", stderr = "Unexpected command: $command", exitCode = 1)
    }

    private fun applyPmGrant(): AdbShellResult = if (grantSecurityException) {
        AdbShellResult(
            stdout = "",
            stderr = "SecurityException: grantRuntimePermission: " +
                "Neither user 2000 nor current process has GRANT_RUNTIME_PERMISSIONS.",
            exitCode = 255
        )
    } else {
        secureGranted = true
        AdbShellResult("")
    }

    override suspend fun push(file: File, remotePath: String, mode: Int) = Unit

    override suspend fun openStream(service: String): AdbByteStream =
        throw UnsupportedOperationException()

    override suspend fun disconnect() = Unit

    override fun close() = Unit
}
