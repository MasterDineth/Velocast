package com.mastersoft.velocast.server.platform

import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
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
 * Mirrors `Display.DEFAULT_DISPLAY` onto a `MediaCodec` input [Surface] via hidden
 * `DisplayManager` APIs. Legacy `SurfaceControl` fallback has been removed (CQ-07): if
 * `DisplayManager` cannot create the mirror display, [UnsupportedPlatformException] is thrown.
 */
class VirtualDisplayBridge(
    private val logger: Logger,
    private val backend: DisplayBridgeBackend = ReflectionDisplayBridgeBackend(logger)
) : Closeable {
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

internal class ReflectionDisplayBridgeBackend(private val logger: Logger) : DisplayBridgeBackend {
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
        throw UnsupportedPlatformException("$detail on API ${Build.VERSION.SDK_INT}", cause)

    override fun createMirrorVirtualDisplay(
        name: String,
        width: Int,
        height: Int,
        surface: Surface
    ): AutoCloseable {
        var lastError: Throwable? = null
        var created: VirtualDisplay? = null

        try {
            created = invokeExactFiveArg(name, width, height, surface)
        } catch (e: ReflectiveOperationException) {
            lastError = e
            logger.warn(TAG, "Exact DisplayManager.createVirtualDisplay unavailable", e)
        } catch (e: SecurityException) {
            lastError = e
            logger.warn(TAG, "Exact DisplayManager.createVirtualDisplay denied", e)
        }

        if (created == null) {
            try {
                created = invokeStaticSearch(name, width, height, surface)
            } catch (e: ReflectiveOperationException) {
                lastError = e
                logger.warn(TAG, "DisplayManager.createVirtualDisplay method search failed", e)
            } catch (e: SecurityException) {
                lastError = e
                logger.warn(TAG, "DisplayManager.createVirtualDisplay method search denied", e)
            }
        }

        val vd = created ?: throw UnsupportedPlatformException(
            "DisplayManager.createVirtualDisplay unsupported on API ${Build.VERSION.SDK_INT}",
            lastError
        )
        return AutoCloseable { vd.release() }
    }

    private fun invokeExactFiveArg(
        name: String,
        width: Int,
        height: Int,
        surface: Surface
    ): VirtualDisplay? {
        val method = DisplayManager::class.java.getMethod(
            "createVirtualDisplay",
            String::class.java,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Surface::class.java
        )
        method.isAccessible = true
        return method.invoke(
            null,
            name,
            width,
            height,
            Display.DEFAULT_DISPLAY,
            surface
        ) as? VirtualDisplay
    }

    private fun invokeStaticSearch(
        name: String,
        width: Int,
        height: Int,
        surface: Surface
    ): VirtualDisplay? {
        for (method in DisplayManager::class.java.methods) {
            val result = tryStaticCandidate(method, name, width, height, surface)
            if (result != null) return result
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

    private companion object {
        const val TAG = "VirtualDisplayBridge"
        const val FIVE_ARGS = 5
        const val SIX_ARGS = 6
    }
}
