package com.mastersoft.velocast.server.platform

import android.content.ClipData
import android.os.Build
import android.os.IBinder
import com.mastersoft.velocast.server.Logger
import com.mastersoft.velocast.server.UnsupportedPlatformException
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/**
 * Abstraction over Android clipboard access so session clipboard handling and capability reporting
 * are unit-testable on the JVM without Android framework stubs.
 */
interface ClipboardBackend {
    val isSupported: Boolean
    fun getText(): String?
    fun setText(text: String): Boolean
}

/**
 * Per-API-level `IClipboard` Binder shim using [PlatformContext] (`com.android.shell`, UID-derived
 * `userId`, and `attributionTag`) so background clipboard reads and writes succeed as `uid 2000`
 * across Android 8.0-17+ and OEM variants (CQ-03, SEC-04).
 */
internal class ReflectionClipboardBackend(
    private val platformContext: PlatformContext = FakeContext(),
    private val logger: Logger,
    private val sdkInt: Int = Build.VERSION.SDK_INT
) : ClipboardBackend {
    private val service: Any? = resolveClipboardService(logger)
    private val setMethodPresent: Boolean = service != null && hasAnySetSignature(service, sdkInt)

    @Volatile
    private var dynamicallyDenied: Boolean = false

    override val isSupported: Boolean
        get() = setMethodPresent && !dynamicallyDenied

    override fun getText(): String? {
        val clipboard = service
        if (clipboard == null || dynamicallyDenied) return null
        return try {
            val clip = ClipboardBinderInvoker.getPrimaryClip(clipboard, platformContext, sdkInt)
            if (clip != null && clip.itemCount > 0) {
                clip.getItemAt(0)?.text?.toString()
            } else {
                null
            }
        } catch (e: InvocationTargetException) {
            handleFailure("getPrimaryClip", e.targetException ?: e)
            null
        } catch (e: ReflectiveOperationException) {
            handleFailure("getPrimaryClip", e)
            null
        } catch (e: SecurityException) {
            dynamicallyDenied = true
            handleFailure("getPrimaryClip", e)
            null
        } catch (e: UnsupportedPlatformException) {
            handleFailure("getPrimaryClip", e)
            null
        }
    }

    override fun setText(text: String): Boolean {
        val clipboard = service
        if (clipboard == null || dynamicallyDenied) return false
        return try {
            val clip = ClipData.newPlainText(CLIP_LABEL, text)
            ClipboardBinderInvoker.setPrimaryClip(clipboard, clip, platformContext, sdkInt)
            true
        } catch (e: InvocationTargetException) {
            val cause = e.targetException ?: e
            if (cause is SecurityException) dynamicallyDenied = true
            handleFailure("setPrimaryClip", cause)
            false
        } catch (e: ReflectiveOperationException) {
            handleFailure("setPrimaryClip", e)
            false
        } catch (e: SecurityException) {
            dynamicallyDenied = true
            handleFailure("setPrimaryClip", e)
            false
        } catch (e: UnsupportedPlatformException) {
            handleFailure("setPrimaryClip", e)
            false
        }
    }

    private fun handleFailure(op: String, error: Throwable) {
        logger.warn(TAG, "IClipboard.$op failed on API $sdkInt", error)
    }

    companion object {
        private const val TAG = "ClipboardShim"
        private const val CLIP_LABEL = "velocast"

        internal fun resolveClipboardService(logger: Logger): Any? = try {
            val smClass = Class.forName("android.os.ServiceManager")
            val binder = smClass.getDeclaredMethod("getService", String::class.java)
                .apply { isAccessible = true }
                .invoke(null, "clipboard") as? IBinder
            if (binder == null) {
                null
            } else {
                val stubClass = Class.forName("android.content.IClipboard\$Stub")
                stubClass.getDeclaredMethod("asInterface", IBinder::class.java)
                    .apply { isAccessible = true }
                    .invoke(null, binder)
            }
        } catch (e: ReflectiveOperationException) {
            logger.debug(TAG, "IClipboard service unavailable", e)
            null
        } catch (e: SecurityException) {
            logger.debug(TAG, "IClipboard service denied", e)
            null
        }

        private fun hasAnySetSignature(clipboard: Any, sdkInt: Int): Boolean {
            val variants = ClipboardBinderInvoker.buildCandidateSpecs(
                platformContext = ShellOnlySpec,
                sdkInt = sdkInt,
                includeClipPrefix = true,
                clip = null
            )
            return variants.any { (paramTypes, _) ->
                ClipboardBinderInvoker.optionalMethod(
                    clipboard.javaClass,
                    "setPrimaryClip",
                    *paramTypes
                ) != null
            }
        }
    }
}

private object ShellOnlySpec : PlatformContext {
    override val callerPackage: String = ShellIdentity.DEFAULT_PACKAGE_NAME
    override val callerAttributionTag: String? = null
    override val callerUid: Int = ShellIdentity.SHELL_UID
    override val callerUserId: Int = 0
    override val isShellUid: Boolean = true
    override val isRootUid: Boolean = false
    override fun asAndroidContext(): android.content.Context =
        throw UnsupportedOperationException("Spec-only context")
}

internal object ClipboardBinderInvoker {
    private const val TAG = "ClipboardShim"
    private const val PROBE_LABEL = "velocast-probe"
    private const val DEFAULT_DEVICE_ID = 0

    fun probeRoundTrip(
        clipboard: Any,
        platformContext: PlatformContext,
        sdkInt: Int,
        logger: Logger
    ): String {
        val initialClip = tryReadPrimaryClip(clipboard, platformContext, sdkInt)
        val marker = "velocast-probe-${System.nanoTime()}"
        val probeClip = ClipData.newPlainText(PROBE_LABEL, marker)
        var wroteProbe = false
        try {
            val setSig = setPrimaryClip(clipboard, probeClip, platformContext, sdkInt)
            wroteProbe = true
            val readBack = getPrimaryClip(clipboard, platformContext, sdkInt)
                ?: throw UnsupportedPlatformException(
                    "IClipboard.getPrimaryClip returned null after set"
                )
            val text = if (readBack.itemCount > 0) readBack.getItemAt(0)?.text?.toString() else null
            if (text != marker) {
                throw UnsupportedPlatformException("Clipboard read-back mismatch")
            }
            return "IClipboard ($setSig)"
        } finally {
            if (wroteProbe) {
                restoreClipboardQuietly(clipboard, initialClip, platformContext, sdkInt, logger)
            }
        }
    }

    fun setPrimaryClip(
        clipboard: Any,
        clip: ClipData,
        platformContext: PlatformContext,
        sdkInt: Int
    ): String {
        val specs = buildCandidateSpecs(platformContext, sdkInt, includeClipPrefix = true, clip)
        return invokeFirstMatching(clipboard, "setPrimaryClip", specs)?.first
            ?: throw UnsupportedPlatformException(
                "No matching IClipboard.setPrimaryClip signature on API $sdkInt"
            )
    }

    fun getPrimaryClip(clipboard: Any, platformContext: PlatformContext, sdkInt: Int): ClipData? {
        val specs = buildCandidateSpecs(platformContext, sdkInt, includeClipPrefix = false, null)
        val matched = invokeFirstMatching(clipboard, "getPrimaryClip", specs)
            ?: throw UnsupportedPlatformException(
                "No matching IClipboard.getPrimaryClip signature on API $sdkInt"
            )
        return matched.second as? ClipData
    }

    fun clearPrimaryClip(clipboard: Any, platformContext: PlatformContext, sdkInt: Int) {
        val specs = buildCandidateSpecs(platformContext, sdkInt, includeClipPrefix = false, null)
        val cleared = invokeFirstMatching(clipboard, "clearPrimaryClip", specs)
        if (cleared == null) {
            val emptyClip = ClipData.newPlainText("", "")
            setPrimaryClip(clipboard, emptyClip, platformContext, sdkInt)
        }
    }

    fun buildCandidateSpecs(
        platformContext: PlatformContext,
        sdkInt: Int,
        includeClipPrefix: Boolean,
        clip: ClipData?
    ): List<Pair<Array<Class<*>?>, Array<Any?>>> {
        val pkg = platformContext.callerPackage
        val tag = platformContext.callerAttributionTag
        val userId = platformContext.callerUserId
        val strType: Class<*>? = String::class.java
        val intType: Class<*>? = Int::class.javaPrimitiveType
        val boolType: Class<*>? = Boolean::class.javaPrimitiveType

        val prefixTypes: List<Class<*>?> =
            if (includeClipPrefix) listOf(ClipData::class.java) else emptyList()
        val prefixArgs: List<Any?> = if (includeClipPrefix) listOf(clip) else emptyList()

        return PlatformApiPolicy.clipboardSignatures(sdkInt).map { sig ->
            val (tailTypes, tailArgs) = when (sig) {
                PlatformApiPolicy.ClipboardSignature.PKG_TAG_USER_DEVICE ->
                    listOf(strType, strType, intType, intType) to
                        listOf(pkg, tag, userId, DEFAULT_DEVICE_ID)

                PlatformApiPolicy.ClipboardSignature.PKG_TAG_USER_DEVICE_USEROP ->
                    listOf(strType, strType, intType, intType, boolType) to
                        listOf(pkg, tag, userId, DEFAULT_DEVICE_ID, true)

                PlatformApiPolicy.ClipboardSignature.PKG_TAG_USER ->
                    listOf(strType, strType, intType) to listOf(pkg, tag, userId)

                PlatformApiPolicy.ClipboardSignature.PKG_TAG_USER_USEROP ->
                    listOf(strType, strType, intType, boolType) to listOf(pkg, tag, userId, true)

                PlatformApiPolicy.ClipboardSignature.PKG_USER ->
                    listOf(strType, intType) to listOf(pkg, userId)

                PlatformApiPolicy.ClipboardSignature.PKG_ONLY ->
                    listOf(strType) to listOf(pkg)
            }
            (prefixTypes + tailTypes).toTypedArray() to (prefixArgs + tailArgs).toTypedArray()
        }
    }

    fun optionalMethod(cls: Class<*>, name: String, vararg params: Class<*>?): Method? = try {
        cls.getMethod(name, *params).apply { isAccessible = true }
    } catch (_: NoSuchMethodException) {
        null
    }

    private fun invokeFirstMatching(
        target: Any,
        methodName: String,
        specs: List<Pair<Array<Class<*>?>, Array<Any?>>>
    ): Pair<String, Any?>? {
        var result: Pair<String, Any?>? = null
        val cls = target.javaClass
        for ((types, args) in specs) {
            val method = optionalMethod(cls, methodName, *types)
            if (method != null) {
                result = "$methodName(argCount=${types.size})" to method.invoke(target, *args)
                break
            }
        }
        return result
    }

    private fun tryReadPrimaryClip(
        clipboard: Any,
        platformContext: PlatformContext,
        sdkInt: Int
    ): ClipData? = try {
        getPrimaryClip(clipboard, platformContext, sdkInt)
    } catch (_: ReflectiveOperationException) {
        null
    } catch (_: SecurityException) {
        null
    } catch (_: UnsupportedPlatformException) {
        null
    }

    private fun restoreClipboardQuietly(
        clipboard: Any,
        initialClip: ClipData?,
        platformContext: PlatformContext,
        sdkInt: Int,
        logger: Logger
    ) {
        try {
            if (initialClip != null) {
                setPrimaryClip(clipboard, initialClip, platformContext, sdkInt)
            } else {
                clearPrimaryClip(clipboard, platformContext, sdkInt)
            }
        } catch (e: ReflectiveOperationException) {
            logger.debug(TAG, "Clipboard restore failed", e)
        } catch (e: SecurityException) {
            logger.debug(TAG, "Clipboard restore denied", e)
        } catch (e: UnsupportedPlatformException) {
            logger.debug(TAG, "Clipboard restore unsupported", e)
        }
    }
}
