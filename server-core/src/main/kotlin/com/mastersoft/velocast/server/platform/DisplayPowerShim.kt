package com.mastersoft.velocast.server.platform

import android.os.Build
import android.os.IBinder
import com.mastersoft.velocast.server.Logger
import dalvik.system.PathClassLoader
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/**
 * Abstraction over Android display panel power control (off while mirroring vs normal) so session
 * state restoration and capability reporting are unit-testable on the JVM.
 */
interface DisplayPowerBackend {
    val isSupported: Boolean
    fun isDisplayPowerOn(): Boolean
    fun setDisplayPowerOn(on: Boolean): Boolean
}

/**
 * Reflection-backed display power controller probing `SurfaceControl` (API 30-33) and
 * `DisplayControl` from `/system/framework/services.jar` (API 34+).
 */
internal class ReflectionDisplayPowerBackend(private val logger: Logger) : DisplayPowerBackend {
    private val powerHandles: DisplayPowerHandles? = resolvePowerHandles(logger)

    override val isSupported: Boolean
        get() = powerHandles != null

    override fun isDisplayPowerOn(): Boolean = try {
        val pm = resolvePowerManager()
        val interactive = pm?.javaClass?.getMethod("isInteractive")?.invoke(pm) as? Boolean
        interactive ?: true
    } catch (e: InvocationTargetException) {
        logger.debug(TAG, "IPowerManager.isInteractive failed", e.targetException ?: e)
        true
    } catch (e: ReflectiveOperationException) {
        logger.debug(TAG, "IPowerManager.isInteractive unavailable", e)
        true
    } catch (e: SecurityException) {
        logger.debug(TAG, "IPowerManager.isInteractive denied", e)
        true
    }

    override fun setDisplayPowerOn(on: Boolean): Boolean {
        val handles = powerHandles ?: return false
        val mode = if (on) POWER_MODE_NORMAL else POWER_MODE_OFF
        var succeeded = false
        try {
            val token = handles.resolveDisplayToken()
            if (token != null) {
                handles.setDisplayPowerMode.invoke(null, token, mode)
                succeeded = true
            }
        } catch (e: InvocationTargetException) {
            logger.warn(TAG, "setDisplayPowerMode($mode) failed", e.targetException ?: e)
        } catch (e: ReflectiveOperationException) {
            logger.warn(TAG, "setDisplayPowerMode($mode) unavailable", e)
        } catch (e: SecurityException) {
            logger.warn(TAG, "setDisplayPowerMode($mode) denied", e)
        }
        return succeeded
    }

    private fun resolvePowerManager(): Any? = try {
        val smClass = Class.forName("android.os.ServiceManager")
        val binder = smClass.getDeclaredMethod("getService", String::class.java)
            .apply { isAccessible = true }
            .invoke(null, "power") as? IBinder
        if (binder == null) {
            null
        } else {
            val stubClass = Class.forName("android.os.IPowerManager\$Stub")
            stubClass.getDeclaredMethod("asInterface", IBinder::class.java)
                .apply { isAccessible = true }
                .invoke(null, binder)
        }
    } catch (_: ReflectiveOperationException) {
        null
    } catch (_: SecurityException) {
        null
    }

    private data class DisplayPowerHandles(
        val setDisplayPowerMode: Method,
        val resolveDisplayToken: () -> IBinder?
    )

    private fun resolvePowerHandles(logger: Logger): DisplayPowerHandles? {
        var handles: DisplayPowerHandles? = null
        val surfaceControl = tryLoadClass("android.view.SurfaceControl", logger)
        if (surfaceControl != null) {
            val setMode = findSetDisplayPowerMode(surfaceControl)
                ?: findDisplayControlClass(logger)?.let { findSetDisplayPowerMode(it) }
            val resolver = if (setMode != null) buildTokenResolver(surfaceControl, logger) else null
            if (setMode != null && resolver != null) {
                handles = DisplayPowerHandles(setMode, resolver)
            } else {
                logger.info(
                    TAG,
                    "Display power control unsupported on API ${Build.VERSION.SDK_INT}"
                )
            }
        }
        return handles
    }

    private fun tryLoadClass(name: String, logger: Logger): Class<*>? = try {
        Class.forName(name)
    } catch (e: ClassNotFoundException) {
        logger.debug(TAG, "$name absent", e)
        null
    } catch (e: LinkageError) {
        logger.debug(TAG, "$name linkage error", e)
        null
    }

    private fun findSetDisplayPowerMode(cls: Class<*>): Method? = try {
        cls.getDeclaredMethod(
            "setDisplayPowerMode",
            IBinder::class.java,
            Int::class.javaPrimitiveType
        ).apply { isAccessible = true }
    } catch (_: NoSuchMethodException) {
        null
    }

    private fun buildTokenResolver(surfaceControl: Class<*>, logger: Logger): (() -> IBinder?)? {
        val legacyInternal = optionalNoArgMethod(surfaceControl, "getInternalDisplayToken")
        var resolver: (() -> IBinder?)? = if (legacyInternal != null) {
            { legacyInternal.invoke(null) as? IBinder }
        } else {
            null
        }
        if (resolver == null) {
            resolver = buildPhysicalTokenResolver(surfaceControl)
        }
        if (resolver == null) {
            resolver = findDisplayControlClass(logger)?.let { buildPhysicalTokenResolver(it) }
        }
        return resolver
    }

    private fun buildPhysicalTokenResolver(cls: Class<*>): (() -> IBinder?)? {
        val idsMethod = optionalNoArgMethod(cls, "getPhysicalDisplayIds")
        val tokenMethod = optionalLongMethod(cls, "getPhysicalDisplayToken")
        return if (idsMethod != null && tokenMethod != null) {
            {
                val ids = idsMethod.invoke(null) as? LongArray
                if (ids != null && ids.isNotEmpty()) {
                    tokenMethod.invoke(null, ids[0]) as? IBinder
                } else {
                    null
                }
            }
        } else {
            null
        }
    }

    private fun findDisplayControlClass(logger: Logger): Class<*>? {
        var resolved: Class<*>? = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            File(SERVICES_JAR_PATH).exists()
        ) {
            try {
                val loader = PathClassLoader(SERVICES_JAR_PATH, ClassLoader.getSystemClassLoader())
                resolved = Class.forName(
                    "com.android.server.display.DisplayControl",
                    true,
                    loader
                )
            } catch (e: ReflectiveOperationException) {
                logger.debug(TAG, "DisplayControl unavailable from services.jar", e)
            } catch (e: LinkageError) {
                logger.debug(TAG, "DisplayControl linkage failed", e)
            } catch (e: SecurityException) {
                logger.debug(TAG, "DisplayControl load denied", e)
            }
        }
        return resolved
    }

    private fun optionalNoArgMethod(cls: Class<*>, name: String): Method? = try {
        cls.getDeclaredMethod(name).apply { isAccessible = true }
    } catch (_: NoSuchMethodException) {
        null
    }

    private fun optionalLongMethod(cls: Class<*>, name: String): Method? = try {
        cls.getDeclaredMethod(name, Long::class.javaPrimitiveType).apply { isAccessible = true }
    } catch (_: NoSuchMethodException) {
        null
    }

    private companion object {
        const val TAG = "DisplayPowerShim"
        const val SERVICES_JAR_PATH = "/system/framework/services.jar"
        const val POWER_MODE_OFF = 0
        const val POWER_MODE_NORMAL = 2
    }
}
