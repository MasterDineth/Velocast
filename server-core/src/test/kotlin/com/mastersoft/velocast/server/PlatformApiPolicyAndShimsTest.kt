package com.mastersoft.velocast.server

import android.content.ClipData
import android.content.Context
import com.mastersoft.velocast.protocol.Status
import com.mastersoft.velocast.protocol.StatusKind
import com.mastersoft.velocast.server.platform.ClipboardBinderInvoker
import com.mastersoft.velocast.server.platform.PlatformApiPolicy
import com.mastersoft.velocast.server.platform.PlatformApiPolicy.ClipboardSignature
import com.mastersoft.velocast.server.platform.PlatformApiPolicy.DisplayPowerStrategy
import com.mastersoft.velocast.server.platform.PlatformApiPolicy.InputManagerClass
import com.mastersoft.velocast.server.platform.PlatformApiPolicy.RotationFreezeStrategy
import com.mastersoft.velocast.server.platform.PlatformApiPolicy.VirtualDisplayStrategy
import com.mastersoft.velocast.server.platform.PlatformContext
import com.mastersoft.velocast.server.platform.ServerCapabilities
import com.mastersoft.velocast.server.platform.ShellIdentity
import com.mastersoft.velocast.server.platform.WindowManagerRotationHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlatformApiPolicyAndShimsTest {

    @Test
    fun selectsDisplayPowerAndVirtualDisplayStrategiesPerApi() {
        assertEquals(
            DisplayPowerStrategy.SURFACE_CONTROL_BUILT_IN_DISPLAY,
            PlatformApiPolicy.displayPowerStrategies(28).first()
        )
        assertEquals(
            DisplayPowerStrategy.SURFACE_CONTROL_INTERNAL_TOKEN,
            PlatformApiPolicy.displayPowerStrategies(29).first()
        )
        assertEquals(
            listOf(
                DisplayPowerStrategy.SURFACE_CONTROL_INTERNAL_TOKEN,
                DisplayPowerStrategy.SURFACE_CONTROL_PHYSICAL_IDS
            ),
            PlatformApiPolicy.displayPowerStrategies(30)
        )
        assertEquals(
            listOf(
                DisplayPowerStrategy.SURFACE_CONTROL_INTERNAL_TOKEN,
                DisplayPowerStrategy.SURFACE_CONTROL_PHYSICAL_IDS
            ),
            PlatformApiPolicy.displayPowerStrategies(33)
        )
        assertEquals(
            DisplayPowerStrategy.DISPLAY_CONTROL_SERVICES_JAR,
            PlatformApiPolicy.displayPowerStrategies(34).first()
        )
        assertEquals(
            DisplayPowerStrategy.DISPLAY_CONTROL_SERVICES_JAR,
            PlatformApiPolicy.displayPowerStrategies(36).first()
        )
        assertEquals(
            DisplayPowerStrategy.DISPLAY_CONTROL_SERVICES_JAR,
            PlatformApiPolicy.displayPowerStrategies(37).first()
        )

        assertEquals(
            VirtualDisplayStrategy.INSTANCE_DISPLAY_MANAGER_WITH_CONTEXT,
            PlatformApiPolicy.virtualDisplayStrategies(29).first()
        )
        assertEquals(
            listOf(
                VirtualDisplayStrategy.STATIC_DISPLAY_MANAGER_MIRROR,
                VirtualDisplayStrategy.GLOBAL_VIRTUAL_DISPLAY_CONFIG_WITH_CONTEXT,
                VirtualDisplayStrategy.INSTANCE_DISPLAY_MANAGER_WITH_CONTEXT
            ),
            PlatformApiPolicy.virtualDisplayStrategies(30)
        )
    }

    @Test
    fun selectsInputRotationAndClipboardStrategiesPerApi() {
        assertEquals(
            InputManagerClass.INPUT_MANAGER,
            PlatformApiPolicy.inputManagerClasses(33).first()
        )
        assertEquals(
            InputManagerClass.INPUT_MANAGER_GLOBAL,
            PlatformApiPolicy.inputManagerClasses(34).first()
        )

        assertEquals(
            RotationFreezeStrategy.WITHOUT_CALLER_PACKAGE,
            PlatformApiPolicy.rotationFreezeStrategies(33).first()
        )
        assertEquals(
            RotationFreezeStrategy.WITH_CALLER_PACKAGE,
            PlatformApiPolicy.rotationFreezeStrategies(34).first()
        )

        assertEquals(ClipboardSignature.PKG_ONLY, PlatformApiPolicy.clipboardSignatures(28).first())
        assertEquals(ClipboardSignature.PKG_USER, PlatformApiPolicy.clipboardSignatures(29).first())
        assertEquals(
            ClipboardSignature.PKG_TAG_USER,
            PlatformApiPolicy.clipboardSignatures(30).first()
        )
        assertEquals(
            ClipboardSignature.PKG_TAG_USER,
            PlatformApiPolicy.clipboardSignatures(33).first()
        )
        assertEquals(
            listOf(
                ClipboardSignature.PKG_TAG_USER_DEVICE,
                ClipboardSignature.PKG_TAG_USER_DEVICE_USEROP,
                ClipboardSignature.PKG_TAG_USER,
                ClipboardSignature.PKG_USER,
                ClipboardSignature.PKG_ONLY
            ),
            PlatformApiPolicy.clipboardSignatures(36)
        )
    }

    @Test
    fun windowManagerRotationShimDispatchesPerApiLevelWithCallerPackage() {
        val dualWm = DualSignatureWindowManager()

        assertEquals(
            "freezeRotation(int)",
            WindowManagerRotationHelper.freezeRotation(
                wm = dualWm,
                rotation = 1,
                callerPackage = ShellIdentity.DEFAULT_PACKAGE_NAME,
                sdkInt = 30
            )
        )
        assertEquals(listOf("freeze1:1"), dualWm.calls)

        assertEquals(
            "thawRotation()",
            WindowManagerRotationHelper.thawRotation(
                wm = dualWm,
                callerPackage = ShellIdentity.DEFAULT_PACKAGE_NAME,
                sdkInt = 30
            )
        )
        assertEquals(listOf("freeze1:1", "thaw0"), dualWm.calls)

        dualWm.calls.clear()
        assertEquals(
            "freezeRotation(int, String)",
            WindowManagerRotationHelper.freezeRotation(
                wm = dualWm,
                rotation = 3,
                callerPackage = ShellIdentity.DEFAULT_PACKAGE_NAME,
                sdkInt = 36
            )
        )
        assertEquals(
            "thawRotation(String)",
            WindowManagerRotationHelper.thawRotation(
                wm = dualWm,
                callerPackage = ShellIdentity.DEFAULT_PACKAGE_NAME,
                sdkInt = 36
            )
        )
        assertEquals(
            listOf("freeze2:3:com.android.shell", "thaw1:com.android.shell"),
            dualWm.calls
        )
    }

    @Test
    fun clipboardShimResolvesAospAndOemSignaturesWithShellIdentity() {
        val ctx = TestShellPlatformContext(
            callerUid = 10 * ShellIdentity.PER_USER_RANGE + ShellIdentity.SHELL_UID,
            callerAttributionTag = "clip-tag"
        )
        val dummyClip = unsafeDummyClipData()

        val api30Clipboard = Api30ClipboardStub()
        assertEquals(
            "setPrimaryClip(argCount=4)",
            ClipboardBinderInvoker.setPrimaryClip(api30Clipboard, dummyClip, ctx, sdkInt = 30)
        )
        assertEquals("com.android.shell:clip-tag:10", api30Clipboard.lastCall)

        val oemHyperOsClipboard = OemSixArgClipboardStub()
        assertEquals(
            "setPrimaryClip(argCount=6)",
            ClipboardBinderInvoker.setPrimaryClip(oemHyperOsClipboard, dummyClip, ctx, sdkInt = 36)
        )
        assertEquals("com.android.shell:clip-tag:10:0:true", oemHyperOsClipboard.lastCall)
        assertNull(ClipboardBinderInvoker.getPrimaryClip(oemHyperOsClipboard, ctx, sdkInt = 36))
    }

    @Test
    fun serverCapabilitiesFormatsFeatureBitsDetailAndDegradedStatuses() {
        val full = ServerCapabilities(
            inputInjection = true,
            rotationWatcher = true,
            displayPowerControl = true,
            notificationShade = true,
            wake = true,
            clipboard = true
        )
        assertEquals(
            ServerCapabilities.FEATURE_CLIPBOARD or
                ServerCapabilities.FEATURE_DISPLAY_POWER or
                ServerCapabilities.FEATURE_ROTATION_EVENTS,
            full.toFeatureMask()
        )
        assertEquals(emptyList<Status>(), full.degradedStatuses())

        val partial = ServerCapabilities(
            inputInjection = false,
            rotationWatcher = false,
            displayPowerControl = false,
            notificationShade = false,
            wake = false,
            clipboard = false
        )
        assertEquals(ServerCapabilities.FEATURE_ROTATION_EVENTS, partial.toFeatureMask())
        assertEquals(
            listOf(
                Status(StatusKind.DEGRADED, 0, "input_injection"),
                Status(StatusKind.DEGRADED, 4, "rotation_watcher"),
                Status(StatusKind.DEGRADED, 3, "display_power"),
                Status(StatusKind.DEGRADED, 0, "notification_shade"),
                Status(StatusKind.DEGRADED, 0, "wake"),
                Status(StatusKind.DEGRADED, 1, "clipboard")
            ),
            partial.degradedStatuses()
        )
    }

    class DualSignatureWindowManager {
        val calls = mutableListOf<String>()

        fun freezeRotation(rotation: Int) {
            calls += "freeze1:$rotation"
        }

        fun freezeRotation(rotation: Int, caller: String) {
            calls += "freeze2:$rotation:$caller"
        }

        fun thawRotation() {
            calls += "thaw0"
        }

        fun thawRotation(caller: String) {
            calls += "thaw1:$caller"
        }
    }

    class Api30ClipboardStub {
        var lastCall: String? = null

        fun setPrimaryClip(clip: ClipData, pkg: String, attributionTag: String?, userId: Int) {
            check(clip === clip)
            lastCall = "$pkg:$attributionTag:$userId"
        }
    }

    class OemSixArgClipboardStub {
        var lastCall: String? = null

        @Suppress("LongParameterList")
        fun setPrimaryClip(
            clip: ClipData,
            pkg: String,
            attributionTag: String?,
            userId: Int,
            deviceId: Int,
            userOperate: Boolean
        ) {
            check(clip === clip)
            lastCall = "$pkg:$attributionTag:$userId:$deviceId:$userOperate"
        }

        fun getPrimaryClip(
            pkg: String,
            attributionTag: String?,
            userId: Int,
            deviceId: Int,
            userOperate: Boolean
        ): ClipData? {
            lastCall = "get:$pkg:$attributionTag:$userId:$deviceId:$userOperate"
            return null
        }
    }

    private class TestShellPlatformContext(
        override val callerUid: Int,
        override val callerAttributionTag: String? = null,
        override val callerPackage: String = ShellIdentity.DEFAULT_PACKAGE_NAME
    ) : PlatformContext {
        private val id = ShellIdentity(callerUid, callerPackage, callerAttributionTag)
        override val callerUserId: Int get() = id.userId
        override val isShellUid: Boolean get() = id.isShellUid
        override val isRootUid: Boolean get() = id.isRootUid
        override fun asAndroidContext(): Context =
            throw UnsupportedOperationException("JVM unit test stub")
    }

    private fun unsafeDummyClipData(): ClipData {
        val unsafeField = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe")
        unsafeField.isAccessible = true
        val unsafe = unsafeField.get(null)
        val allocate = unsafe.javaClass.getMethod("allocateInstance", Class::class.java)
        return allocate.invoke(unsafe, ClipData::class.java) as ClipData
    }
}
