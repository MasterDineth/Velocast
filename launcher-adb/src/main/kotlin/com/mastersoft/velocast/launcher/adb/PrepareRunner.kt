package com.mastersoft.velocast.launcher.adb

import java.io.IOException
import java.util.Locale

/** Ordered one-time Prepare shell steps executed over ADB (`S07B`). */
enum class PrepareStepId(
    val storageKey: String,
    val needsDeviceConfirmation: Boolean,
    val confirmationNote: String
) {
    STAY_ON_WHILE_PLUGGED_IN(
        storageKey = "stay_on_while_plugged_in",
        needsDeviceConfirmation = true,
        confirmationNote = "Confirm `stay_on_while_plugged_in=7` keeps screen awake on " +
            "AC/USB/Wireless charge on target Android 11-17 devices."
    ),
    DISABLE_ADB_AUTH_TIMEOUT(
        storageKey = "adb_allowed_connection_time",
        needsDeviceConfirmation = true,
        confirmationNote = "Confirm `adb_allowed_connection_time=0` prevents 7-day ADB " +
            "authorisation expiry on target ROMs."
    ),
    GRANT_WRITE_SECURE_SETTINGS(
        storageKey = "grant_write_secure_settings",
        needsDeviceConfirmation = true,
        confirmationNote = "Confirm `pm grant com.mastersoft.velocast.host " +
            "android.permission.WRITE_SECURE_SETTINGS` succeeds as uid 2000 on target OEM " +
            "ROMs (MIUI/HyperOS/ColorOS may require extra developer toggles)."
    ),
    BATTERY_WHITELIST(
        storageKey = "battery_whitelist",
        needsDeviceConfirmation = true,
        confirmationNote = "Confirm `cmd deviceidle whitelist +com.mastersoft.velocast.host` " +
            "exempts Host from Doze on target ROMs."
    ),
    ALLOW_RUN_ANY_IN_BACKGROUND(
        storageKey = "run_any_in_background",
        needsDeviceConfirmation = true,
        confirmationNote = "Confirm `cmd appops set com.mastersoft.velocast.host " +
            "RUN_ANY_IN_BACKGROUND allow` succeeds on Android 9+ and verify API 26-28 fallback."
    )
}

/** Per-step outcome reported by [PrepareRunner]. */
sealed interface PrepareStepStatus {
    data object Done : PrepareStepStatus

    data object AlreadyDone : PrepareStepStatus

    data class NeedsUser(val reason: String) : PrepareStepStatus

    data class Failed(val reason: String) : PrepareStepStatus

    val isSatisfied: Boolean
        get() = this is Done || this is AlreadyDone
}

/** Outcome of one [PrepareStepId] execution, including device-confirmation flag. */
data class PrepareStepResult(
    val id: PrepareStepId,
    val status: PrepareStepStatus,
    val needsDeviceConfirmation: Boolean = id.needsDeviceConfirmation,
    val confirmationNote: String = id.confirmationNote
)

/** Aggregate report of all ordered [PrepareStepId] steps run by [PrepareRunner]. */
data class PrepareReport(val results: List<PrepareStepResult>) {
    val isAllSatisfied: Boolean
        get() = results.isNotEmpty() && results.all { it.status.isSatisfied }

    val preparedItemKeys: Set<String>
        get() = results.filter { it.status.isSatisfied }.mapTo(LinkedHashSet()) { it.id.storageKey }
}

/**
 * Runs the ordered, idempotent Prepare automation commands over an already-connected [AdbClient],
 * verifying every step before and after applying it (`S07B`).
 */
class PrepareRunner(
    private val adbClient: AdbClient,
    private val hostPackageName: String = DEFAULT_HOST_PACKAGE,
    private val scrubber: AdbSecretScrubber = AdbSecretScrubber.defaultInstance
) {
    init {
        require(PACKAGE_NAME_REGEX.matches(hostPackageName)) {
            "Invalid hostPackageName: '$hostPackageName'"
        }
    }

    suspend fun runAll(onStepComplete: (PrepareStepResult) -> Unit = {}): PrepareReport {
        val results = ArrayList<PrepareStepResult>(PrepareStepId.entries.size)
        for (stepId in PrepareStepId.entries) {
            val status = executeStepSafely(stepId)
            val result = PrepareStepResult(id = stepId, status = status)
            results += result
            onStepComplete(result)
        }
        return PrepareReport(results)
    }

    private suspend fun executeStepSafely(stepId: PrepareStepId): PrepareStepStatus = try {
        when (stepId) {
            PrepareStepId.STAY_ON_WHILE_PLUGGED_IN -> runGlobalSettingStep(
                key = "stay_on_while_plugged_in",
                expectedValue = STAY_ON_AC_USB_WIRELESS
            )

            PrepareStepId.DISABLE_ADB_AUTH_TIMEOUT -> runGlobalSettingStep(
                key = "adb_allowed_connection_time",
                expectedValue = DISABLE_AUTH_TIMEOUT
            )

            PrepareStepId.GRANT_WRITE_SECURE_SETTINGS -> runGrantWriteSecureSettings()

            PrepareStepId.BATTERY_WHITELIST -> runBatteryWhitelist()

            PrepareStepId.ALLOW_RUN_ANY_IN_BACKGROUND -> runAppOpsBackground()
        }
    } catch (e: AdbClientException) {
        PrepareStepStatus.Failed(
            scrubber.redact(e.failure.detail ?: e.message ?: "ADB command failed")
        )
    } catch (e: IOException) {
        PrepareStepStatus.Failed(scrubber.redact(e.message ?: "ADB transport I/O error"))
    }

    private suspend fun runGlobalSettingStep(
        key: String,
        expectedValue: String
    ): PrepareStepStatus {
        val outcome: PrepareStepStatus
        if (readGlobalSetting(key) == expectedValue) {
            outcome = PrepareStepStatus.AlreadyDone
        } else {
            val putRes = adbClient.shell("settings put global $key $expectedValue")
            val putFailure = PrepareShellClassifier.classifyShellFailure(
                result = putRes,
                commandLabel = "settings put global $key $expectedValue",
                hostPackageName = hostPackageName,
                scrubber = scrubber
            )
            outcome = if (putFailure != null) {
                putFailure
            } else {
                val verified = readGlobalSetting(key)
                if (verified == expectedValue) {
                    PrepareStepStatus.Done
                } else {
                    PrepareStepStatus.Failed(
                        "Verification failed for $key: expected '$expectedValue', " +
                            "got '${verified ?: "null"}'"
                    )
                }
            }
        }
        return outcome
    }

    private suspend fun readGlobalSetting(key: String): String? {
        val res = adbClient.shell("settings get global $key")
        if (res.exitCode != 0) return null
        val line = res.stdout.lineSequence().map(String::trim).firstOrNull(String::isNotEmpty)
        return line?.takeIf { it != "null" }
    }

    private suspend fun runGrantWriteSecureSettings(): PrepareStepStatus = when {
        !isHostAppInstalled() -> PrepareStepStatus.NeedsUser(
            "Install the VeloCast Host app ($hostPackageName) on the old phone first."
        )

        isWriteSecureSettingsGranted() -> PrepareStepStatus.AlreadyDone

        else -> {
            val grantRes = adbClient.shell(
                "pm grant $hostPackageName $PERMISSION_WRITE_SECURE_SETTINGS"
            )
            val failure = PrepareShellClassifier.classifyShellFailure(
                result = grantRes,
                commandLabel = "pm grant $PERMISSION_WRITE_SECURE_SETTINGS",
                hostPackageName = hostPackageName,
                scrubber = scrubber
            )
            when {
                failure != null -> failure

                isWriteSecureSettingsGranted() -> PrepareStepStatus.Done

                else -> PrepareStepStatus.Failed(
                    "Verification failed: $PERMISSION_WRITE_SECURE_SETTINGS not granted to " +
                        hostPackageName
                )
            }
        }
    }

    private suspend fun isWriteSecureSettingsGranted(): Boolean {
        val res = adbClient.shell(
            "dumpsys package $hostPackageName | grep $PERMISSION_WRITE_SECURE_SETTINGS"
        )
        return res.exitCode == 0 &&
            res.stdout.contains("$PERMISSION_WRITE_SECURE_SETTINGS: granted=true")
    }

    private suspend fun runBatteryWhitelist(): PrepareStepStatus = when {
        !isHostAppInstalled() -> PrepareStepStatus.NeedsUser(
            "Install the VeloCast Host app ($hostPackageName) on the old phone first."
        )

        isBatteryWhitelisted() -> PrepareStepStatus.AlreadyDone

        else -> {
            val addRes = adbClient.shell("cmd deviceidle whitelist +$hostPackageName")
            val failure = PrepareShellClassifier.classifyShellFailure(
                result = addRes,
                commandLabel = "cmd deviceidle whitelist +$hostPackageName",
                hostPackageName = hostPackageName,
                scrubber = scrubber
            )
            when {
                failure != null -> failure

                isBatteryWhitelisted() -> PrepareStepStatus.Done

                else -> PrepareStepStatus.Failed(
                    "Verification failed: $hostPackageName missing from deviceidle whitelist"
                )
            }
        }
    }

    private suspend fun isBatteryWhitelisted(): Boolean {
        val res = adbClient.shell("cmd deviceidle whitelist")
        if (res.exitCode != 0) return false
        return res.stdout.lineSequence().any { rawLine ->
            val parts = rawLine.trim().split(',')
            parts.size >= 2 && parts[1].trim() == hostPackageName
        }
    }

    private suspend fun runAppOpsBackground(): PrepareStepStatus = when {
        !isHostAppInstalled() -> PrepareStepStatus.NeedsUser(
            "Install the VeloCast Host app ($hostPackageName) on the old phone first."
        )

        isRunAnyInBackgroundAllowed() -> PrepareStepStatus.AlreadyDone

        else -> {
            val setRes = adbClient.shell(
                "cmd appops set $hostPackageName $APPOP_RUN_ANY_IN_BACKGROUND allow"
            )
            val failure = PrepareShellClassifier.classifyAppOpsFailure(
                result = setRes,
                hostPackageName = hostPackageName,
                scrubber = scrubber
            )
            when {
                failure != null -> failure

                isRunAnyInBackgroundAllowed() -> PrepareStepStatus.Done

                else -> PrepareStepStatus.Failed(
                    "Verification failed: $APPOP_RUN_ANY_IN_BACKGROUND is not 'allow' for " +
                        hostPackageName
                )
            }
        }
    }

    private suspend fun isRunAnyInBackgroundAllowed(): Boolean {
        val res = adbClient.shell("cmd appops get $hostPackageName $APPOP_RUN_ANY_IN_BACKGROUND")
        if (res.exitCode != 0) return false
        val normalized = res.stdout.lowercase(Locale.ROOT)
        return normalized.contains("${APPOP_RUN_ANY_IN_BACKGROUND.lowercase(Locale.ROOT)}: allow")
    }

    private suspend fun isHostAppInstalled(): Boolean {
        val res = adbClient.shell("pm path $hostPackageName")
        return res.exitCode == 0 && res.stdout.lineSequence().any { line ->
            line.trim().startsWith("package:")
        }
    }

    companion object {
        const val DEFAULT_HOST_PACKAGE = "com.mastersoft.velocast.host"
        const val STAY_ON_AC_USB_WIRELESS = "7"
        const val DISABLE_AUTH_TIMEOUT = "0"
        const val PERMISSION_WRITE_SECURE_SETTINGS = "android.permission.WRITE_SECURE_SETTINGS"
        const val APPOP_RUN_ANY_IN_BACKGROUND = "RUN_ANY_IN_BACKGROUND"
        private val PACKAGE_NAME_REGEX = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")
    }
}

private object PrepareShellClassifier {
    fun classifyShellFailure(
        result: AdbShellResult,
        commandLabel: String,
        hostPackageName: String,
        scrubber: AdbSecretScrubber
    ): PrepareStepStatus? {
        val output = scrubber.redact(result.allOutput.trim())
        val lower = output.lowercase(Locale.ROOT)
        val hasError = result.exitCode != 0 ||
            "exception" in lower ||
            "permission denial" in lower ||
            "unknown package" in lower
        return when {
            !hasError -> null

            "unknown package" in lower || "package $hostPackageName not found" in lower ->
                PrepareStepStatus.NeedsUser(
                    "Install the VeloCast Host app ($hostPackageName) on the old phone first."
                )

            isOemOrPermissionDenial(lower) -> PrepareStepStatus.NeedsUser(
                "Permission denied running '$commandLabel'. On Xiaomi MIUI/HyperOS enable " +
                    "'USB debugging (Security settings)'; on ColorOS/OxygenOS enable " +
                    "'Disable permission monitoring' ($output)."
            )

            else -> PrepareStepStatus.Failed(
                output.ifEmpty { "'$commandLabel' exited with code ${result.exitCode}" }
            )
        }
    }

    fun classifyAppOpsFailure(
        result: AdbShellResult,
        hostPackageName: String,
        scrubber: AdbSecretScrubber
    ): PrepareStepStatus? {
        val output = scrubber.redact(result.allOutput.trim())
        val lower = output.lowercase(Locale.ROOT)
        return if ("unknown operation" in lower || "no such operation" in lower) {
            PrepareStepStatus.NeedsUser(
                "Disable battery optimisation for $hostPackageName manually in Settings " +
                    "(RUN_ANY_IN_BACKGROUND unavailable on this Android build)."
            )
        } else {
            classifyShellFailure(
                result = result,
                commandLabel = "cmd appops set ${PrepareRunner.APPOP_RUN_ANY_IN_BACKGROUND}",
                hostPackageName = hostPackageName,
                scrubber = scrubber
            )
        }
    }

    private fun isOemOrPermissionDenial(lowerOutput: String): Boolean =
        "securityexception" in lowerOutput ||
            "permission denial" in lowerOutput ||
            "grant_runtime_permissions" in lowerOutput ||
            "not allowed to" in lowerOutput
}
