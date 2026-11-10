package dev.dsh.mobile.data

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Local-network discovery for hosts that advertise `_dsh._tcp` over mDNS (the host plugin does this
 * on the same Wi-Fi). Uses Android's [NsdManager]; results are de-duplicated by host:port and posted
 * to the main thread so Compose can render them.
 */
class LanDiscovery(context: Context) {
    data class FoundHost(
        val serviceName: String,
        val host: String,
        val port: Int,
    ) {
        val baseUrl: String get() = "http://$host:$port"
        val key: String get() = "$host:$port"
    }

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val found = CopyOnWriteArrayList<FoundHost>()
    private val pending = ArrayDeque<NsdServiceInfo>()
    private var resolving = false

    private var nsd: NsdManager? = null
    private var listener: NsdManager.DiscoveryListener? = null

    /** Starts discovery. [onChanged] receives the current result list on the main thread. */
    fun start(onChanged: (List<FoundHost>) -> Unit) {
        stop()
        found.clear()
        pending.clear()
        val manager = appContext.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
        nsd = manager
        val discovery = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                stopInternal()
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit

            override fun onServiceFound(info: NsdServiceInfo) {
                if (!info.serviceType.contains(DISCOVERY_SERVICE_TYPE.trimEnd('.'))) return
                pending.addLast(info)
                resolveNext(onChanged)
            }

            override fun onServiceLost(info: NsdServiceInfo) {
                val name = info.serviceName
                val removed = found.filter { it.serviceName == name }
                if (removed.isNotEmpty()) {
                    found.removeAll(removed.toSet())
                    main.post { onChanged(found.toList()) }
                }
            }
        }
        listener = discovery
        runCatching {
            manager.discoverServices(DISCOVERY_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discovery)
        }.onFailure { stopInternal() }
    }

    fun stop() {
        stopInternal()
    }

    private fun stopInternal() {
        listener?.let { discovered -> runCatching { nsd?.stopServiceDiscovery(discovered) } }
        listener = null
        nsd = null
        resolving = false
        pending.clear()
    }

    private fun resolveNext(onChanged: (List<FoundHost>) -> Unit) {
        if (resolving) return
        val manager = nsd ?: return
        val info = pending.removeFirstOrNull() ?: return
        resolving = true
        runCatching {
            manager.resolveService(
                info,
                object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        resolving = false
                        resolveNext(onChanged)
                    }

                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                        val address = serviceInfo.host?.hostAddress
                        val port = serviceInfo.port
                        if (!address.isNullOrBlank() && port > 0) {
                            val entry = FoundHost(
                                serviceName = serviceInfo.serviceName,
                                host = address,
                                port = port,
                            )
                            if (found.none { it.key == entry.key }) {
                                found.add(entry)
                                main.post { onChanged(found.toList()) }
                            }
                        }
                        resolving = false
                        resolveNext(onChanged)
                    }
                },
            )
        }.onFailure {
            resolving = false
            resolveNext(onChanged)
        }
    }

    companion object {
        /** Must match the host plugin's advertised service type (with trailing dot for NSD). */
        const val DISCOVERY_SERVICE_TYPE = "_dsh._tcp."
    }
}
