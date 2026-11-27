package com.mastersoft.velocast.server.platform

/**
 * Pure-JVM per-API-level strategy selectors for Android hidden-API shims in `:server-core` (CQ-03).
 *
 * Isolating version thresholds here makes every API-level dispatch decision unit-testable on the
 * JVM without Android framework mocks, while keeping reflection call sites inside `platform/`.
 */
internal object PlatformApiPolicy {
    const val API_ANDROID_10 = 29
    const val API_ANDROID_11 = 30
    const val API_ANDROID_14 = 34

    enum class DisplayPowerStrategy {
        /** API 34+: `com.android.server.display.DisplayControl` from `services.jar` + `libandroid_servers.so`. */
        DISPLAY_CONTROL_SERVICES_JAR,

        /** API 30-33: `SurfaceControl.getPhysicalDisplayIds()` + `getPhysicalDisplayToken(long)`. */
        SURFACE_CONTROL_PHYSICAL_IDS,

        /** API 29-33: `SurfaceControl.getInternalDisplayToken()`. */
        SURFACE_CONTROL_INTERNAL_TOKEN,

        /** API 26-28: `SurfaceControl.getBuiltInDisplay(0)`. */
        SURFACE_CONTROL_BUILT_IN_DISPLAY
    }

    enum class VirtualDisplayStrategy {
        /** API 30+: static `@SystemApi` `DisplayManager.createVirtualDisplay(String, int, int, int, Surface)`. */
        STATIC_DISPLAY_MANAGER_MIRROR,

        /** API 30+: `DisplayManagerGlobal.createVirtualDisplay` with `VirtualDisplayConfig` + `FakeContext`. */
        GLOBAL_VIRTUAL_DISPLAY_CONFIG_WITH_CONTEXT,

        /** API 26+: `DisplayManager(FakeContext).createVirtualDisplay(..., VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR)`. */
        INSTANCE_DISPLAY_MANAGER_WITH_CONTEXT
    }

    enum class InputManagerClass(val className: String) {
        /** API 34+: `android.hardware.input.InputManagerGlobal`. */
        INPUT_MANAGER_GLOBAL("android.hardware.input.InputManagerGlobal"),

        /** API 26-33 (and fallback): `android.hardware.input.InputManager`. */
        INPUT_MANAGER("android.hardware.input.InputManager")
    }

    enum class RotationFreezeStrategy {
        /** API 34+: `freezeRotation(int, String)` and `thawRotation(String)` with caller package. */
        WITH_CALLER_PACKAGE,

        /** API 26-33: `freezeRotation(int)` and `thawRotation()`. */
        WITHOUT_CALLER_PACKAGE
    }

    enum class ClipboardSignature {
        /** API 34+: `(..., String pkg, String attributionTag, int userId, int deviceId)`. */
        PKG_TAG_USER_DEVICE,

        /** OEM Android 14+ (HyperOS / One UI): `(..., String pkg, String tag, int userId, int deviceId, boolean)`. */
        PKG_TAG_USER_DEVICE_USEROP,

        /** API 30-33: `(..., String pkg, String attributionTag, int userId)`. */
        PKG_TAG_USER,

        /** OEM Android 11-13: `(..., String pkg, String attributionTag, int userId, boolean)`. */
        PKG_TAG_USER_USEROP,

        /** API 29: `(..., String pkg, int userId)`. */
        PKG_USER,

        /** API 26-28: `(..., String pkg)`. */
        PKG_ONLY
    }

    fun displayPowerStrategies(sdkInt: Int): List<DisplayPowerStrategy> = when {
        sdkInt >= API_ANDROID_14 -> listOf(
            DisplayPowerStrategy.DISPLAY_CONTROL_SERVICES_JAR,
            DisplayPowerStrategy.SURFACE_CONTROL_PHYSICAL_IDS,
            DisplayPowerStrategy.SURFACE_CONTROL_INTERNAL_TOKEN
        )

        sdkInt >= API_ANDROID_11 -> listOf(
            DisplayPowerStrategy.SURFACE_CONTROL_INTERNAL_TOKEN,
            DisplayPowerStrategy.SURFACE_CONTROL_PHYSICAL_IDS
        )

        sdkInt == API_ANDROID_10 -> listOf(
            DisplayPowerStrategy.SURFACE_CONTROL_INTERNAL_TOKEN,
            DisplayPowerStrategy.SURFACE_CONTROL_BUILT_IN_DISPLAY
        )

        else -> listOf(
            DisplayPowerStrategy.SURFACE_CONTROL_BUILT_IN_DISPLAY,
            DisplayPowerStrategy.SURFACE_CONTROL_INTERNAL_TOKEN
        )
    }

    fun virtualDisplayStrategies(sdkInt: Int): List<VirtualDisplayStrategy> =
        if (sdkInt >= API_ANDROID_11) {
            listOf(
                VirtualDisplayStrategy.STATIC_DISPLAY_MANAGER_MIRROR,
                VirtualDisplayStrategy.GLOBAL_VIRTUAL_DISPLAY_CONFIG_WITH_CONTEXT,
                VirtualDisplayStrategy.INSTANCE_DISPLAY_MANAGER_WITH_CONTEXT
            )
        } else {
            listOf(
                VirtualDisplayStrategy.INSTANCE_DISPLAY_MANAGER_WITH_CONTEXT,
                VirtualDisplayStrategy.STATIC_DISPLAY_MANAGER_MIRROR
            )
        }

    fun inputManagerClasses(sdkInt: Int): List<InputManagerClass> = if (sdkInt >= API_ANDROID_14) {
        listOf(InputManagerClass.INPUT_MANAGER_GLOBAL, InputManagerClass.INPUT_MANAGER)
    } else {
        listOf(InputManagerClass.INPUT_MANAGER, InputManagerClass.INPUT_MANAGER_GLOBAL)
    }

    fun rotationFreezeStrategies(sdkInt: Int): List<RotationFreezeStrategy> =
        if (sdkInt >= API_ANDROID_14) {
            listOf(
                RotationFreezeStrategy.WITH_CALLER_PACKAGE,
                RotationFreezeStrategy.WITHOUT_CALLER_PACKAGE
            )
        } else {
            listOf(
                RotationFreezeStrategy.WITHOUT_CALLER_PACKAGE,
                RotationFreezeStrategy.WITH_CALLER_PACKAGE
            )
        }

    fun clipboardSignatures(sdkInt: Int): List<ClipboardSignature> = when {
        sdkInt >= API_ANDROID_14 -> listOf(
            ClipboardSignature.PKG_TAG_USER_DEVICE,
            ClipboardSignature.PKG_TAG_USER_DEVICE_USEROP,
            ClipboardSignature.PKG_TAG_USER,
            ClipboardSignature.PKG_USER,
            ClipboardSignature.PKG_ONLY
        )

        sdkInt >= API_ANDROID_11 -> listOf(
            ClipboardSignature.PKG_TAG_USER,
            ClipboardSignature.PKG_TAG_USER_DEVICE,
            ClipboardSignature.PKG_TAG_USER_USEROP,
            ClipboardSignature.PKG_USER,
            ClipboardSignature.PKG_ONLY
        )

        sdkInt == API_ANDROID_10 -> listOf(
            ClipboardSignature.PKG_USER,
            ClipboardSignature.PKG_TAG_USER,
            ClipboardSignature.PKG_ONLY
        )

        else -> listOf(
            ClipboardSignature.PKG_ONLY,
            ClipboardSignature.PKG_USER
        )
    }
}
