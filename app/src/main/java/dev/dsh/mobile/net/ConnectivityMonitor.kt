package dev.dsh.mobile.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper

/**
 * Watches the system default network (VPN transports such as Tailscale included) and reports the
 * current reachability plus a coarse transport label.
 *
 * [onChanged] runs on the main thread when a new default network becomes available, when the
 * default network is lost and when the transport label changes (for example a Tailscale VPN
 * coming up over Wi-Fi). Callers are expected to debounce and to ignore no-op transitions.
 *
 * `ACCESS_NETWORK_STATE` is already declared by the app; no runtime permission is involved.
 */
class ConnectivityMonitor(
    context: Context,
    private val onChanged: () -> Unit,
) {
    private val connectivityManager: ConnectivityManager? =
        context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var registered = false

    /** Whether the default network currently has internet capability. */
    var available: Boolean = false
        private set

    /** Coarse transport label: "VPN", "Wi-Fi", "Cellular", "Ethernet", "Other" or "None". */
    var transport: String = TRANSPORT_NONE
        private set

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            refresh(network = network)
            onChanged()
        }

        override fun onLost(network: Network) {
            // A replacement default network may already be active; onAvailable fires for it too.
            refresh()
            onChanged()
        }

        override fun onCapabilitiesChanged(
            network: Network,
            networkCapabilities: NetworkCapabilities,
        ) {
            val previousTransport = transport
            val previousAvailable = available
            refresh(network, networkCapabilities)
            // Capabilities change for signal/bandwidth tweaks too; only react to transport changes.
            if (previousTransport != transport || previousAvailable != available) onChanged()
        }
    }

    /** Registers the callback and seeds [available]/[transport] from the current default network. */
    fun start() {
        if (registered) return
        registered = true
        refresh()
        runCatching {
            connectivityManager?.registerDefaultNetworkCallback(callback, handler)
        }.onFailure {
            registered = false
        }
    }

    /** Unregisters the callback; safe to call repeatedly and after a failed [start]. */
    fun stop() {
        if (!registered) return
        registered = false
        runCatching { connectivityManager?.unregisterNetworkCallback(callback) }
    }

    private fun refresh(
        network: Network? = null,
        networkCapabilities: NetworkCapabilities? = null,
    ) {
        val manager = connectivityManager
        if (manager == null) {
            available = false
            transport = TRANSPORT_NONE
            return
        }
        val activeNetwork = network ?: manager.activeNetwork
        val capabilities = networkCapabilities
            ?: activeNetwork?.let { manager.getNetworkCapabilities(it) }
        if (activeNetwork == null || capabilities == null ||
            !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        ) {
            available = false
            transport = TRANSPORT_NONE
            return
        }
        available = true
        transport = when {
            // Tailscale and other VPNs present themselves as the default network; prefer the VPN
            // label even when the capabilities also report the underlying transport.
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> TRANSPORT_VPN
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Cellular"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            else -> "Other"
        }
    }

    private companion object {
        const val TRANSPORT_NONE = "None"
        const val TRANSPORT_VPN = "VPN"
    }
}
