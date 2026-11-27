package com.mastersoft.velocast.server.platform

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.os.Build
import android.view.Display
import android.view.Surface
import com.mastersoft.velocast.protocol.DisplayGeometry
import com.mastersoft.velocast.protocol.ProtocolException
import com.mastersoft.velocast.server.Logger
import com.mastersoft.velocast.server.UnsupportedPlatformException
import java.io.Closeable
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.Executor
import org.lsposed.hiddenapibypass.HiddenApiBypass

/**
 * Abstraction over Android display query and mirror virtual-display creation so display-bridge
 * error handling can be verified cleanly in JVM unit tests.
 */
interface DisplayBridgeBackend {
    fun queryDefaultDisplayGeometry(): DisplayGeometry

    fun createMirrorVirtualDisplay(
        name: String,
        width: Int,
        height: Int,
        surface: Surface
    ): AutoCloseable
}

/**
 * Mirrors `Display.DEFAULT_DISPLAY` onto a `MediaCodec` input [Surface] via `DisplayManager`
 * per-API-level strategies and [PlatformContext] (`com.android.shell`) fallbacks (CQ-03, CQ-07).
 */
class VirtualDisplayBridge(
    private val logger: Logger,
    private val backend: DisplayBridgeBackend = ReflectionDisplayBridgeBackend(logger)
) : Closeable {
    constructor(
        logger: Logger,
        platformContext: PlatformContext,
        sdkInt: Int = Build.VERSION.SDK_INT
    ) : this(
        logger = logger,
        backend = ReflectionDisplayBridgeBackend(logger, platformContext, sdkInt)
    )

    private var activeDisplay: AutoCloseable? = null

    fun queryDefaultDisplayGeometry(): DisplayGeometry = backend.queryDefaultDisplayGeometry()

    fun startMirroring(targetSurface: Surface, videoWidth: Int, videoHeight: Int) {
        close()
        activeDisplay = backend.createMirrorVirtualDisplay(
            name = DISPLAY_NAME,
            width = videoWidth,
            height = videoHeight,
            surface = targetSurface
        )
        logger.info(TAG, "Mirroring active via DisplayManager (${videoWidth}x$videoHeight)")
    }

    override fun close() {
        val current = activeDisplay ?: return
        activeDisplay = null
        try {
            current.close()
        } catch (e: ReflectiveOperationException) {
            logger.warn(TAG, "Error releasing virtual display", e)
        } catch (e: IllegalStateException) {
            logger.warn(TAG, "Error releasing virtual display", e)
        }
    }

    companion object {
        private const val TAG = "VirtualDisplayBridge"
        private const val DISPLAY_NAME = "velocast-mirror"

        fun bypassHiddenApis(logger: Logger) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                try {
                    HiddenApiBypass.addHiddenApiExemptions("L")
                } catch (e: ReflectiveOperationException) {
                    logger.warn(TAG, "HiddenApiBypass reflection exemption failed", e)
                } catch (e: LinkageError) {
                    logger.warn(TAG, "HiddenApiBypass linkage failed", e)
                } catch (e: SecurityException) {
                    logger.warn(TAG, "HiddenApiBypass permission denied", e)
                }
            }
        }
    }
}

internal class ReflectionDisplayBridgeBackend(
    private val logger: Logger,
    private val platformContext: PlatformContext = FakeContext(),
    private val sdkInt: Int = Build.VERSION.SDK_INT
) : DisplayBridgeBackend {
    override fun queryDefaultDisplayGeometry(): DisplayGeometry = try {
        readDefaultDisplayGeometry()
    } catch (e: ReflectiveOperationException) {
        failDisplayInfo("Failed to query DisplayInfo", e)
    } catch (e: ProtocolException) {
        failDisplayInfo("Invalid DisplayInfo geometry", e)
    } catch (e: SecurityException) {
        failDisplayInfo("Security restriction querying DisplayInfo", e)
    }

    private fun readDefaultDisplayGeometry(): DisplayGeometry {
        val dmgClass = Class.forName("android.hardware.display.DisplayManagerGlobal")
        val dmg = dmgClass.getMethod("getInstance").invoke(null)
        val displayInfo = dmgClass
            .getMethod("getDisplayInfo", Int::class.javaPrimitiveType)
            .invoke(dmg, Display.DEFAULT_DISPLAY)
            ?: throw UnsupportedPlatformException(
                "DisplayManagerGlobal.getDisplayInfo returned null"
            )

        val infoClass = displayInfo.javaClass
        val w = infoClass.getField("logicalWidth").getInt(displayInfo)
        val h = infoClass.getField("logicalHeight").getInt(displayInfo)
        val rot = infoClass.getField("rotation").getInt(displayInfo)
        return DisplayGeometry(w, h, rot)
    }

    private fun failDisplayInfo(detail: String, cause: Throwable): Nothing =
        throw UnsupportedPlatformException("$detail on API $sdkInt", cause)

    override fun createMirrorVirtualDisplay(
        name: String,
        width: Int,
        height: Int,
        surface: Surface
    ): AutoCloseable {
        var lastError: Throwable? = null
        var created: VirtualDisplay? = null

        for (strategy in PlatformApiPolicy.virtualDisplayStrategies(sdkInt)) {
            if (created != null) break
            try {
                created = invokeStrategy(strategy, name, width, height, surface)
            } catch (e: ReflectiveOperationException) {
                lastError = e
                logger.debug(TAG, "VirtualDisplay strategy $strategy unavailable", e)
            } catch (e: SecurityException) {
                lastError = e
                logger.warn(TAG, "VirtualDisplay strategy $strategy denied", e)
            } catch (e: LinkageError) {
                lastError = e
                logger.debug(TAG, "VirtualDisplay strategy $strategy linkage failed", e)
            }
        }

        val vd = created ?: throw UnsupportedPlatformException(
            "DisplayManager.createVirtualDisplay unsupported on API $sdkInt",
            lastError
        )
        return AutoCloseable { vd.release() }
    }

    private fun invokeStrategy(
        strategy: PlatformApiPolicy.VirtualDisplayStrategy,
        name: String,
        width: Int,
        height: Int,
        surface: Surface
    ): VirtualDisplay? = when (strategy) {
        PlatformApiPolicy.VirtualDisplayStrategy.STATIC_DISPLAY_MANAGER_MIRROR ->
            VirtualDisplayReflector.invokeStaticMirror(name, width, height, surface)

        PlatformApiPolicy.VirtualDisplayStrategy.GLOBAL_VIRTUAL_DISPLAY_CONFIG_WITH_CONTEXT ->
            VirtualDisplayReflector.invokeGlobalWithConfig(
                platformContext.asAndroidContext(),
                name,
                width,
                height,
                surface
            )

        PlatformApiPolicy.VirtualDisplayStrategy.INSTANCE_DISPLAY_MANAGER_WITH_CONTEXT ->
            VirtualDisplayReflector.invokeInstanceWithContext(
                platformContext.asAndroidContext(),
                name,
                width,
                height,
                surface
            )
    }

    private companion object {
        const val TAG = "VirtualDisplayBridge"
    }
}

private object VirtualDisplayReflector {
    private const val FIVE_ARGS = 5
    private const val SIX_ARGS = 6
    private const val MIRROR_DENSITY_DPI = 1

    fun invokeStaticMirror(
        name: String,
        width: Int,
        height: Int,
        surface: Surface
    ): VirtualDisplay? {
        val exact = try {
            DisplayManager::class.java.getMethod(
                "createVirtualDisplay",
                String::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Surface::class.java
            ).apply { isAccessible = true }
        } catch (_: NoSuchMethodException) {
            null
        }
        if (exact != null) {
            val vd = exact.invoke(
                null,
                name,
                width,
                height,
                Display.DEFAULT_DISPLAY,
                surface
            ) as? VirtualDisplay
            if (vd != null) return vd
        }
        return invokeStaticSearch(name, width, height, surface)
    }

    fun invokeGlobalWithConfig(
        context: Context,
        name: String,
        width: Int,
        height: Int,
        surface: Surface
    ): VirtualDisplay? {
        val builderClass = Class.forName(
            "android.hardware.display.VirtualDisplayConfig\$Builder"
        )
        val ctor = builderClass.getConstructor(
            String::class.java,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType
        )
        val builder = ctor.newInstance(name, width, height, MIRROR_DENSITY_DPI)
        builderClass.getMethod("setFlags", Int::class.javaPrimitiveType)
            .invoke(builder, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR)
        builderClass.getMethod("setSurface", Surface::class.java).invoke(builder, surface)
        try {
            HiddenApiBypass.invoke(
                builderClass,
                builder,
                "setDisplayIdToMirror",
                Display.DEFAULT_DISPLAY
            )
        } catch (_: ReflectiveOperationException) {
            // Optional hidden setter on early API 30 builds where AUTO_MIRROR alone suffices.
        }
        val config = builderClass.getMethod("build").invoke(builder)
        val configClass = Class.forName("android.hardware.display.VirtualDisplayConfig")
        val dmgClass = Class.forName("android.hardware.display.DisplayManagerGlobal")
        val dmg = dmgClass.getMethod("getInstance").invoke(null)
        val createMethod = dmgClass.getMethod(
            "createVirtualDisplay",
            Context::class.java,
            MediaProjection::class.java,
            configClass,
            VirtualDisplay.Callback::class.java,
            Executor::class.java
        ).apply { isAccessible = true }
        return createMethod.invoke(dmg, context, null, config, null, null) as? VirtualDisplay
    }

    fun invokeInstanceWithContext(
        context: Context,
        name: String,
        width: Int,
        height: Int,
        surface: Surface
    ): VirtualDisplay? {
        val ctor = DisplayManager::class.java.getDeclaredConstructor(Context::class.java)
            .apply { isAccessible = true }
        val dm = ctor.newInstance(context)
        return dm.createVirtualDisplay(
            name,
            width,
            height,
            MIRROR_DENSITY_DPI,
            surface,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR
        )
    }

    private fun invokeStaticSearch(
        name: String,
        width: Int,
        height: Int,
        surface: Surface
    ): VirtualDisplay? {
        for (method in DisplayManager::class.java.methods) {
            val vd = tryStaticCandidate(method, name, width, height, surface)
            if (vd != null) return vd
        }
        return null
    }

    private fun tryStaticCandidate(
        method: Method,
        name: String,
        width: Int,
        height: Int,
        surface: Surface
    ): VirtualDisplay? {
        var vd: VirtualDisplay? = null
        if (method.name == "createVirtualDisplay" && Modifier.isStatic(method.modifiers)) {
            val pts = method.parameterTypes
            if (pts.isNotEmpty() && pts[0] == String::class.java) {
                val args = when (pts.size) {
                    FIVE_ARGS -> arrayOf(name, width, height, Display.DEFAULT_DISPLAY, surface)
                    SIX_ARGS -> arrayOf(name, width, height, Display.DEFAULT_DISPLAY, surface, 0)
                    else -> null
                }
                if (args != null) {
                    method.isAccessible = true
                    vd = method.invoke(null, *args) as? VirtualDisplay
                }
            }
        }
        return vd
    }
}
