package com.mastersoft.velocast.server.platform

import android.os.Build
import android.os.IBinder
import com.mastersoft.velocast.server.Logger
import com.mastersoft.velocast.server.UnsupportedPlatformException
import dalvik.system.PathClassLoader
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import org.lsposed.hiddenapibypass.HiddenApiBypass

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
 * Reflection-backed display power controller with per-API-level handling (FUN-07, CQ-03):
 * - API 26-28: `SurfaceControl.getBuiltInDisplay(0)` + `SurfaceControl.setDisplayPowerMode`
 * - API 29-33: `SurfaceControl.getInternalDisplayToken()` / `getPhysicalDisplayIds()` +
 *   `SurfaceControl.setDisplayPowerMode`
 * - API 34+: `com.android.server.display.DisplayControl` loaded from
 *   `/system/framework/services.jar` via `ClassLoaderFactory.createClassLoader(..., isShared=true)`
 *   with `libandroid_servers.so` bound via `Runtime.loadLibrary0`, paired with
 *   `SurfaceControl.setDisplayPowerMode`.
 */
internal class ReflectionDisplayPowerBackend(
    private val logger: Logger,
    private val sdkInt: Int = Build.VERSION.SDK_INT
) : DisplayPowerBackend {
    private val powerHandles: DisplayPowerHandles? =
        DisplayPowerHandlesResolver.resolveOrNull(sdkInt, logger)

    @Volatile
    private var dynamicallyDisabled: Boolean = false

    override val isSupported: Boolean
        get() = powerHandles != null && !dynamicallyDisabled

    override fun isDisplayPowerOn(): Boolean = try {
        val pm = DisplayPowerHandlesResolver.resolvePowerManager()
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
        val handles = powerHandles
        if (handles == null || dynamicallyDisabled) return false
        val mode = if (on) POWER_MODE_NORMAL else POWER_MODE_OFF
        var succeeded = false
        try {
            val token = handles.resolveDisplayToken()
            if (token != null) {
                handles.setDisplayPowerMode.invoke(null, token, mode)
                succeeded = true
            }
        } catch (e: InvocationTargetException) {
            val cause = e.targetException ?: e
            if (cause is SecurityException || cause is LinkageError) {
                dynamicallyDisabled = true
            }
            logger.warn(TAG, "setDisplayPowerMode($mode) failed", cause)
        } catch (e: ReflectiveOperationException) {
            logger.warn(TAG, "setDisplayPowerMode($mode) unavailable", e)
        } catch (e: SecurityException) {
            dynamicallyDisabled = true
            logger.warn(TAG, "setDisplayPowerMode($mode) denied", e)
        } catch (e: LinkageError) {
            dynamicallyDisabled = true
            logger.warn(TAG, "setDisplayPowerMode($mode) linkage failed", e)
        }
        return succeeded
    }

    companion object {
        private const val TAG = "DisplayPowerShim"
        internal const val POWER_MODE_OFF = 0
        internal const val POWER_MODE_NORMAL = 2
    }
}

internal data class DisplayPowerHandles(
    val setDisplayPowerMode: Method,
    val initialToken: IBinder,
    val tokenSourceLabel: String,
    val resolveDisplayToken: () -> IBinder?
)

internal object DisplayPowerHandlesResolver {
    private const val TAG = "DisplayPowerShim"
    private const val BUILT_IN_DISPLAY_MAIN = 0

    fun resolveOrThrow(sdkInt: Int, logger: Logger): DisplayPowerHandles {
        val surfaceControl = Class.forName("android.view.SurfaceControl")
        val strategies = PlatformApiPolicy.displayPowerStrategies(sdkInt)
        var lastError: Throwable? = null
        var displayControl: Class<*>? = null
        var attemptedDisplayControl = false

        for (strategy in strategies) {
            try {
                if (strategy ==
                    PlatformApiPolicy.DisplayPowerStrategy.DISPLAY_CONTROL_SERVICES_JAR &&
                    !attemptedDisplayControl
                ) {
                    attemptedDisplayControl = true
                    displayControl = DisplayControlLoader.getOrLoad(logger)
                }
                val candidate = tryResolveStrategy(strategy, surfaceControl, displayControl)
                if (candidate != null) return candidate
            } catch (e: ReflectiveOperationException) {
                lastError = e
            } catch (e: LinkageError) {
                lastError = e
            } catch (e: SecurityException) {
                lastError = e
            }
        }

        if (lastError != null) {
            throw lastError
        }
        throw UnsupportedPlatformException(
            "Display power control handles unavailable on API $sdkInt"
        )
    }

    fun resolveOrNull(sdkInt: Int, logger: Logger): DisplayPowerHandles? = try {
        resolveOrThrow(sdkInt, logger)
    } catch (e: UnsupportedPlatformException) {
        logger.info(TAG, e.message ?: "Display power control unsupported on API $sdkInt")
        null
    } catch (e: ReflectiveOperationException) {
        logger.info(TAG, "Display power control reflection unavailable on API $sdkInt")
        logger.debug(TAG, "Display power reflection failure detail", e)
        null
    } catch (e: LinkageError) {
        logger.info(TAG, "Display power control native linkage unavailable on API $sdkInt")
        logger.debug(TAG, "Display power linkage failure detail", e)
        null
    } catch (e: SecurityException) {
        logger.info(TAG, "Display power control denied on API $sdkInt")
        logger.debug(TAG, "Display power security failure detail", e)
        null
    }

    fun resolvePowerManager(): Any? = try {
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

    private fun tryResolveStrategy(
        strategy: PlatformApiPolicy.DisplayPowerStrategy,
        surfaceControl: Class<*>,
        displayControl: Class<*>?
    ): DisplayPowerHandles? {
        val setMode = findSetDisplayPowerMode(surfaceControl)
            ?: displayControl?.let { findSetDisplayPowerMode(it) }
        val resolverAndLabel = if (setMode != null) {
            resolveTokenSupplier(strategy, surfaceControl, displayControl)
        } else {
            null
        }
        val verifiedToken = resolverAndLabel?.second?.invoke()
        return if (setMode != null && resolverAndLabel != null && verifiedToken != null) {
            DisplayPowerHandles(
                setDisplayPowerMode = setMode,
                initialToken = verifiedToken,
                tokenSourceLabel = resolverAndLabel.first,
                resolveDisplayToken = resolverAndLabel.second
            )
        } else {
            null
        }
    }

    private fun resolveTokenSupplier(
        strategy: PlatformApiPolicy.DisplayPowerStrategy,
        surfaceControl: Class<*>,
        displayControl: Class<*>?
    ): Pair<String, () -> IBinder?>? = when (strategy) {
        PlatformApiPolicy.DisplayPowerStrategy.DISPLAY_CONTROL_SERVICES_JAR ->
            displayControl?.let { cls ->
                buildPhysicalTokenResolver(cls)?.let {
                    "DisplayControl.getPhysicalDisplayToken" to it
                }
            }

        PlatformApiPolicy.DisplayPowerStrategy.SURFACE_CONTROL_PHYSICAL_IDS ->
            buildPhysicalTokenResolver(surfaceControl)?.let {
                "SurfaceControl.getPhysicalDisplayToken" to it
            }

        PlatformApiPolicy.DisplayPowerStrategy.SURFACE_CONTROL_INTERNAL_TOKEN ->
            optionalDeclaredMethod(surfaceControl, "getInternalDisplayToken")?.let { method ->
                "SurfaceControl.getInternalDisplayToken" to { method.invoke(null) as? IBinder }
            }

        PlatformApiPolicy.DisplayPowerStrategy.SURFACE_CONTROL_BUILT_IN_DISPLAY ->
            optionalDeclaredMethod(
                surfaceControl,
                "getBuiltInDisplay",
                Int::class.javaPrimitiveType
            )?.let { method ->
                "SurfaceControl.getBuiltInDisplay" to
                    { method.invoke(null, BUILT_IN_DISPLAY_MAIN) as? IBinder }
            }
    }

    private fun buildPhysicalTokenResolver(cls: Class<*>): (() -> IBinder?)? {
        val idsMethod = optionalDeclaredMethod(cls, "getPhysicalDisplayIds")
        val tokenMethod = optionalDeclaredMethod(
            cls,
            "getPhysicalDisplayToken",
            Long::class.javaPrimitiveType
        )
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

    private fun findSetDisplayPowerMode(cls: Class<*>): Method? = optionalDeclaredMethod(
        cls,
        "setDisplayPowerMode",
        IBinder::class.java,
        Int::class.javaPrimitiveType
    )

    internal fun optionalDeclaredMethod(
        cls: Class<*>,
        name: String,
        vararg params: Class<*>?
    ): Method? = try {
        cls.getDeclaredMethod(name, *params).apply { isAccessible = true }
    } catch (_: NoSuchMethodException) {
        null
    }
}

private object DisplayControlLoader {
    private const val TAG = "DisplayPowerShim"
    private const val SERVICES_JAR_PATH = "/system/framework/services.jar"
    private const val ANDROID_SERVERS_LIB = "android_servers"

    private val lock = Any()
    private var cachedClass: Class<*>? = null
    private var cachedError: Throwable? = null
    private var loaded = false

    fun getOrLoad(logger: Logger): Class<*>? = synchronized(lock) {
        if (!loaded) {
            loaded = true
            try {
                cachedClass = loadDisplayControlWithJni(logger)
            } catch (e: ReflectiveOperationException) {
                cachedError = e
            } catch (e: LinkageError) {
                cachedError = e
            } catch (e: SecurityException) {
                cachedError = e
            }
        }
        cachedError?.let { throw it }
        cachedClass
    }

    private fun loadDisplayControlWithJni(logger: Logger): Class<*>? {
        if (!File(SERVICES_JAR_PATH).exists()) return null
        val loader = createSharedServicesClassLoader(logger)
        val cls = Class.forName("com.android.server.display.DisplayControl", false, loader)
        bindAndroidServersLibrary(cls, loader)
        return cls
    }

    private fun createSharedServicesClassLoader(logger: Logger): ClassLoader {
        val systemLoader = ClassLoader.getSystemClassLoader()
        return try {
            val factoryClass = Class.forName("com.android.internal.os.ClassLoaderFactory")
            HiddenApiBypass.invoke(
                factoryClass,
                null,
                "createClassLoader",
                SERVICES_JAR_PATH,
                null,
                null,
                systemLoader,
                0,
                true,
                null
            ) as ClassLoader
        } catch (e: ReflectiveOperationException) {
            logger.debug(TAG, "ClassLoaderFactory unavailable; falling back to PathClassLoader", e)
            PathClassLoader(SERVICES_JAR_PATH, "/system/lib64:/system/lib", systemLoader)
        }
    }

    private fun bindAndroidServersLibrary(displayControlClass: Class<*>, loader: ClassLoader) {
        val runtime = Runtime.getRuntime()
        val byClass = DisplayPowerHandlesResolver.optionalDeclaredMethod(
            Runtime::class.java,
            "loadLibrary0",
            Class::class.java,
            String::class.java
        )
        if (byClass != null) {
            byClass.invoke(runtime, displayControlClass, ANDROID_SERVERS_LIB)
            return
        }
        val byLoader = DisplayPowerHandlesResolver.optionalDeclaredMethod(
            Runtime::class.java,
            "loadLibrary0",
            ClassLoader::class.java,
            String::class.java
        )
        if (byLoader != null) {
            byLoader.invoke(runtime, loader, ANDROID_SERVERS_LIB)
        }
    }
}
