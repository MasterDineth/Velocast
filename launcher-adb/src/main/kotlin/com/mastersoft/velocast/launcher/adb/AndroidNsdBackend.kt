package com.mastersoft.velocast.launcher.adb

import android.annotation.SuppressLint
import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import java.io.Closeable
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor

/**
 * Production Android [NsdBackend] backed by [NsdManager] for `_adb-tls-pairing._tcp` and
 * `_adb-tls-connect._tcp`, using API 34+ `registerServiceInfoCallback` on Android 14+ and falling
 * back cleanly to `resolveService` on API 26–33.
 */
class AndroidNsdBackend(context: Context) : NsdBackend {
    private val appContext = context.applicationContext
    private val directExecutor = Executor { runnable -> runnable.run() }

    override fun startDiscovery(
        serviceType: AdbServiceType,
        listener: NsdDiscoveryListener
    ): Closeable {
        if (!LocalNetworkPermissionGate.canAccessLocalNetwork(appContext)) {
            throw AdbClientException(
                AdbFailure.DifferentNetwork("Local network permission is required on API 37+")
            )
        }
        val nsdManager = appContext.getSystemService(Context.NSD_SERVICE) as? NsdManager
            ?: throw IOException("NsdManager system service is unavailable")

        val activeInfoCallbacks = ConcurrentHashMap<String, Closeable>()
        val discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceTypeStr: String?, errorCode: Int) {
                listener.onDiscoveryFailed(
                    IOException("NsdManager startDiscovery failed (code=$errorCode)")
                )
            }

            override fun onStopDiscoveryFailed(serviceTypeStr: String?, errorCode: Int) = Unit

            override fun onDiscoveryStarted(serviceTypeStr: String?) = Unit

            override fun onDiscoveryStopped(serviceTypeStr: String?) = Unit

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                resolveDiscoveredService(
                    nsdManager = nsdManager,
                    serviceType = serviceType,
                    serviceInfo = serviceInfo,
                    listener = listener,
                    activeInfoCallbacks = activeInfoCallbacks
                )
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                activeInfoCallbacks.remove(serviceInfo.serviceName)?.close()
                listener.onServiceLost(serviceInfo.serviceName, serviceType)
            }
        }

        nsdManager.discoverServices(
            serviceType.dnsType,
            NsdManager.PROTOCOL_DNS_SD,
            discoveryListener
        )

        return Closeable {
            for (handle in activeInfoCallbacks.values) {
                try {
                    handle.close()
                } catch (_: IOException) {
                }
            }
            activeInfoCallbacks.clear()
            try {
                nsdManager.stopServiceDiscovery(discoveryListener)
            } catch (_: IllegalArgumentException) {
                // Already stopped.
            }
        }
    }

    override fun registerService(
        serviceName: String,
        serviceType: AdbServiceType,
        port: Int
    ): Closeable {
        val nsdManager = appContext.getSystemService(Context.NSD_SERVICE) as? NsdManager
            ?: throw IOException("NsdManager system service is unavailable")
        val info = NsdServiceInfo().apply {
            this.serviceName = serviceName
            this.serviceType = serviceType.dnsType
            this.port = port
        }
        val regListener = object : NsdManager.RegistrationListener {
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) = Unit
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) = Unit
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo?) = Unit
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo?) = Unit
        }
        nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, regListener)
        return Closeable {
            try {
                nsdManager.unregisterService(regListener)
            } catch (_: IllegalArgumentException) {
            }
        }
    }

    private fun resolveDiscoveredService(
        nsdManager: NsdManager,
        serviceType: AdbServiceType,
        serviceInfo: NsdServiceInfo,
        listener: NsdDiscoveryListener,
        activeInfoCallbacks: ConcurrentHashMap<String, Closeable>
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val handle = Api34NsdResolver.registerCallback(
                nsdManager = nsdManager,
                serviceInfo = serviceInfo,
                executor = directExecutor
            ) { resolved ->
                toDiscoveredService(resolved, serviceType)?.let(listener::onServiceResolved)
            }
            activeInfoCallbacks.put(serviceInfo.serviceName, handle)?.close()
        } else {
            Api26NsdCompat.resolveService(nsdManager, serviceInfo) { resolved ->
                toDiscoveredService(resolved, serviceType)?.let(listener::onServiceResolved)
            }
        }
    }

    private fun toDiscoveredService(
        info: NsdServiceInfo,
        serviceType: AdbServiceType
    ): DiscoveredAdbService? {
        val port = info.port
        val hostAddress = if (port in AdbEndpoint.MIN_PORT..AdbEndpoint.MAX_PORT) {
            selectPreferredHostAddress(info)
        } else {
            null
        } ?: return null
        val attrs = LinkedHashMap<String, String>()
        for ((k, v) in info.attributes) {
            if (k != null) {
                attrs[k] = v?.let { String(it, Charsets.UTF_8) }.orEmpty()
            }
        }
        return DiscoveredAdbService(
            serviceName = info.serviceName,
            serviceType = serviceType,
            host = hostAddress,
            port = port,
            attributes = attrs
        )
    }

    private fun selectPreferredHostAddress(info: NsdServiceInfo): String? {
        val candidates: List<InetAddress> =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                Api34NsdResolver.hostAddresses(info)
            } else {
                listOfNotNull(Api26NsdCompat.host(info))
            }
        val preferred = candidates.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
            ?: candidates.firstOrNull { !it.isLoopbackAddress }
            ?: candidates.firstOrNull()
        return preferred?.hostAddress
    }

    @SuppressLint("NewApi")
    private object Api34NsdResolver {
        fun registerCallback(
            nsdManager: NsdManager,
            serviceInfo: NsdServiceInfo,
            executor: Executor,
            onUpdated: (NsdServiceInfo) -> Unit
        ): Closeable {
            val callback = object : NsdManager.ServiceInfoCallback {
                override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) = Unit
                override fun onServiceUpdated(updatedInfo: NsdServiceInfo) = onUpdated(updatedInfo)
                override fun onServiceLost() = Unit
                override fun onServiceInfoCallbackUnregistered() = Unit
            }
            nsdManager.registerServiceInfoCallback(serviceInfo, executor, callback)
            return Closeable {
                try {
                    nsdManager.unregisterServiceInfoCallback(callback)
                } catch (_: IllegalArgumentException) {
                }
            }
        }

        fun hostAddresses(info: NsdServiceInfo): List<InetAddress> = info.hostAddresses
    }

    @Suppress("DEPRECATION")
    private object Api26NsdCompat {
        fun resolveService(
            nsdManager: NsdManager,
            serviceInfo: NsdServiceInfo,
            onResolved: (NsdServiceInfo) -> Unit
        ) {
            nsdManager.resolveService(
                serviceInfo,
                object : NsdManager.ResolveListener {
                    override fun onResolveFailed(failedInfo: NsdServiceInfo?, errorCode: Int) = Unit
                    override fun onServiceResolved(resolvedInfo: NsdServiceInfo) =
                        onResolved(resolvedInfo)
                }
            )
        }

        fun host(info: NsdServiceInfo): InetAddress? = info.host
    }
}
