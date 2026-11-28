package com.mastersoft.velocast.launcher.adb

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * Outcome of checking local-network permission readiness.
 *
 * Per `FUN-13` (`docs/findings.md`), apps targeting below API 37 receive an implicit local-network
 * grant from Android and must NOT request `android.permission.ACCESS_LOCAL_NETWORK` at runtime.
 */
enum class LocalNetworkPermissionStatus {
    /** App targets `< 37` or runs on Android `< 37`; local network access is implicitly granted. */
    IMPLICITLY_GRANTED_PRE_API_37,

    /** App targets `>= 37` on Android `>= 37` and the user has granted `ACCESS_LOCAL_NETWORK`. */
    GRANTED,

    /** App targets `>= 37` on Android `>= 37` and must request `ACCESS_LOCAL_NETWORK` at runtime. */
    REQUIRED
}

/**
 * Single centralized policy and gate for `android.permission.ACCESS_LOCAL_NETWORK` (`FUN-13`).
 *
 * Keep all local-network permission checks routed through this class so raising `targetSdk` to 37
 * later requires wiring only the UI prompt for [LocalNetworkPermissionStatus.REQUIRED].
 */
object LocalNetworkPermissionGate {
    const val PERMISSION_ACCESS_LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"
    const val ENFORCEMENT_SDK_INT = 37

    /**
     * Returns `true` only when both the running Android OS and the app's `targetSdkVersion` are at
     * or above API [ENFORCEMENT_SDK_INT] (Android 17 / API 37).
     */
    fun requiresRuntimePermission(deviceSdkInt: Int, targetSdkInt: Int): Boolean =
        deviceSdkInt >= ENFORCEMENT_SDK_INT && targetSdkInt >= ENFORCEMENT_SDK_INT

    /**
     * Pure, JVM-testable evaluation of local-network permission status.
     */
    fun evaluate(
        deviceSdkInt: Int,
        targetSdkInt: Int,
        isPermissionGranted: () -> Boolean
    ): LocalNetworkPermissionStatus = if (!requiresRuntimePermission(deviceSdkInt, targetSdkInt)) {
        LocalNetworkPermissionStatus.IMPLICITLY_GRANTED_PRE_API_37
    } else if (isPermissionGranted()) {
        LocalNetworkPermissionStatus.GRANTED
    } else {
        LocalNetworkPermissionStatus.REQUIRED
    }

    /**
     * Evaluates local-network permission status for [context] without triggering any premature
     * runtime prompt before `targetSdk` reaches API 37.
     */
    fun status(context: Context): LocalNetworkPermissionStatus {
        val targetSdk = context.applicationInfo.targetSdkVersion
        return evaluate(
            deviceSdkInt = Build.VERSION.SDK_INT,
            targetSdkInt = targetSdk,
            isPermissionGranted = {
                context.checkSelfPermission(PERMISSION_ACCESS_LOCAL_NETWORK) ==
                    PackageManager.PERMISSION_GRANTED
            }
        )
    }

    /** Returns `true` when local-network sockets and `NsdManager` operations may proceed. */
    fun canAccessLocalNetwork(context: Context): Boolean =
        status(context) != LocalNetworkPermissionStatus.REQUIRED
}
