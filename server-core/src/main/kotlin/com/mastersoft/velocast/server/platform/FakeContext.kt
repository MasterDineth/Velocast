package com.mastersoft.velocast.server.platform

import android.annotation.TargetApi
import android.content.AttributionSource
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.os.Process
import android.os.UserHandle

/**
 * Platform identity abstraction for hidden Android APIs that require a caller package name, UID,
 * user ID, or [Context] without depending on an application [Context] or UI libraries.
 */
interface PlatformContext {
    val callerPackage: String
    val callerAttributionTag: String?
    val callerUid: Int
    val callerUserId: Int
    val isShellUid: Boolean
    val isRootUid: Boolean

    fun asAndroidContext(): Context
}

/**
 * Pure-JVM representation of the caller UID and package identity used by [FakeContext] so
 * UID-aware calculations (`userId`, `isShellUid`, `isRootUid`) are unit-testable without
 * Android framework stubs.
 */
data class ShellIdentity(
    val uid: Int,
    val packageName: String = DEFAULT_PACKAGE_NAME,
    val attributionTag: String? = null
) {
    val opPackageName: String
        get() = packageName

    val userId: Int
        get() = uid / PER_USER_RANGE

    val isShellUid: Boolean
        get() = (uid % PER_USER_RANGE) == SHELL_UID

    val isRootUid: Boolean
        get() = (uid % PER_USER_RANGE) == ROOT_UID

    companion object {
        const val DEFAULT_PACKAGE_NAME = "com.android.shell"
        const val ROOT_UID = 0
        const val SHELL_UID = 2000
        const val PER_USER_RANGE = 100_000
    }
}

/**
 * Lightweight, UID-aware [ContextWrapper] reporting package `com.android.shell` for hidden APIs
 * invoked from `app_process` (uid 2000 or 0) without an application [Context].
 */
class FakeContext(
    private val uidProvider: () -> Int = { Process.myUid() },
    private val requestedPackageName: String = ShellIdentity.DEFAULT_PACKAGE_NAME,
    private val requestedAttributionTag: String? = null
) : ContextWrapper(null),
    PlatformContext {

    override val callerPackage: String
        get() = requestedPackageName

    override val callerAttributionTag: String?
        get() = requestedAttributionTag

    override val callerUid: Int
        get() = uidProvider()

    override val callerUserId: Int
        get() = ShellIdentity(callerUid, requestedPackageName, requestedAttributionTag).userId

    override val isShellUid: Boolean
        get() = ShellIdentity(callerUid, requestedPackageName, requestedAttributionTag).isShellUid

    override val isRootUid: Boolean
        get() = ShellIdentity(callerUid, requestedPackageName, requestedAttributionTag).isRootUid

    override fun asAndroidContext(): Context = this

    override fun getPackageName(): String = requestedPackageName

    override fun getOpPackageName(): String = requestedPackageName

    override fun getAttributionTag(): String? = requestedAttributionTag

    @TargetApi(Build.VERSION_CODES.S)
    override fun getAttributionSource(): AttributionSource = AttributionSource.Builder(callerUid)
        .setPackageName(requestedPackageName)
        .setAttributionTag(requestedAttributionTag)
        .build()

    override fun getApplicationContext(): Context = this

    override fun createPackageContext(packageName: String?, flags: Int): Context = this

    override fun createAttributionContext(attributionTag: String?): Context = FakeContext(
        uidProvider = uidProvider,
        requestedPackageName = requestedPackageName,
        requestedAttributionTag = attributionTag
    )

    override fun createDeviceProtectedStorageContext(): Context = this

    /**
     * Hidden `Context.getUserId()` invoked via reflection or framework helpers.
     */
    @Suppress("unused")
    fun getUserId(): Int = callerUserId

    override fun getSystemService(name: String): Any? = null
}
