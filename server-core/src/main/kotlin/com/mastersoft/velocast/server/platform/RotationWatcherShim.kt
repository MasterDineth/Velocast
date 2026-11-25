package com.mastersoft.velocast.server.platform

import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.view.Display
import com.mastersoft.velocast.server.Logger
import java.io.Closeable
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * Registers an `IWindowManager.watchRotation` listener over Binder when the hidden API is
 * accessible, falling back cleanly when unsupported (FUN-01, CQ-03).
 */
internal object RotationWatcherShim {
    private const val TAG = "RotationWatcherShim"
    private const val DESCRIPTOR = "android.view.IRotationWatcher"

    fun register(logger: Logger, onRotationChanged: (Int) -> Unit): Closeable? {
        val wm = resolveWindowManager(logger) ?: return null
        return try {
            registerOnWindowManager(wm, logger, onRotationChanged)
        } catch (e: InvocationTargetException) {
            logger.warn(TAG, "watchRotation invocation failed", e.targetException ?: e)
            null
        } catch (e: ReflectiveOperationException) {
            logger.warn(TAG, "watchRotation unavailable", e)
            null
        } catch (e: SecurityException) {
            logger.warn(TAG, "watchRotation denied", e)
            null
        }
    }

    private fun registerOnWindowManager(
        wm: Any,
        logger: Logger,
        onRotationChanged: (Int) -> Unit
    ): Closeable? {
        val watcherInterface = Class.forName(DESCRIPTOR)
        val binder = RotationWatcherBinder(onRotationChanged)
        val proxy = Proxy.newProxyInstance(
            watcherInterface.classLoader,
            arrayOf(watcherInterface)
        ) { _, method, args ->
            when (method.name) {
                "asBinder" -> binder

                "onRotationChanged" -> {
                    val rot = args?.firstOrNull() as? Int
                    if (rot != null) onRotationChanged(rot)
                    null
                }

                else -> null
            }
        }

        val twoArg = optionalMethod(
            wm.javaClass,
            "watchRotation",
            watcherInterface,
            Int::class.javaPrimitiveType
        )
        if (twoArg != null) {
            twoArg.invoke(wm, proxy, Display.DEFAULT_DISPLAY)
        } else {
            val oneArg = optionalMethod(wm.javaClass, "watchRotation", watcherInterface)
                ?: return null
            oneArg.invoke(wm, proxy)
        }

        val removeMethod = optionalMethod(wm.javaClass, "removeRotationWatcher", watcherInterface)
        logger.info(TAG, "Registered IWindowManager rotation watcher")
        return Closeable {
            unregisterQuietly(wm, removeMethod, proxy, logger)
        }
    }

    private fun unregisterQuietly(wm: Any, removeMethod: Method?, proxy: Any, logger: Logger) {
        if (removeMethod == null) return
        try {
            removeMethod.invoke(wm, proxy)
        } catch (e: InvocationTargetException) {
            logger.debug(TAG, "removeRotationWatcher failed", e.targetException ?: e)
        } catch (e: ReflectiveOperationException) {
            logger.debug(TAG, "removeRotationWatcher unavailable", e)
        } catch (e: SecurityException) {
            logger.debug(TAG, "removeRotationWatcher denied", e)
        }
    }

    private fun resolveWindowManager(logger: Logger): Any? = try {
        val smClass = Class.forName("android.os.ServiceManager")
        val binder = smClass.getDeclaredMethod("getService", String::class.java)
            .apply { isAccessible = true }
            .invoke(null, "window") as? IBinder
        if (binder == null) {
            null
        } else {
            val stubClass = Class.forName("android.view.IWindowManager\$Stub")
            stubClass.getDeclaredMethod("asInterface", IBinder::class.java)
                .apply { isAccessible = true }
                .invoke(null, binder)
        }
    } catch (e: ReflectiveOperationException) {
        logger.debug(TAG, "IWindowManager lookup failed", e)
        null
    } catch (e: SecurityException) {
        logger.debug(TAG, "IWindowManager lookup denied", e)
        null
    }

    private fun optionalMethod(cls: Class<*>, name: String, vararg paramTypes: Class<*>?): Method? =
        try {
            cls.getMethod(name, *paramTypes).apply { isAccessible = true }
        } catch (_: NoSuchMethodException) {
            null
        }

    private class RotationWatcherBinder(private val onChanged: (Int) -> Unit) : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == FIRST_CALL_TRANSACTION) {
                data.enforceInterface(DESCRIPTOR)
                val rotation = data.readInt()
                onChanged(rotation)
                return true
            }
            return super.onTransact(code, data, reply, flags)
        }
    }
}
