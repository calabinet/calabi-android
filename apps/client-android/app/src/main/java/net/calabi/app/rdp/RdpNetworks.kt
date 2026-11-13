package net.calabi.app.rdp

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper

/** Observe the real uplinks, never the app's own VPN. Burst events settle before repair. */
internal class RdpNetworks(context: Context, private val changed: (Boolean) -> Unit) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val paths = mutableMapOf<Network, String>()
    private var signature = ""
    private var closed = false
    private val publish = Runnable {
        val next = paths.entries.sortedBy { it.key.toString() }.joinToString { "${it.key}:${it.value}" }
        if (!closed && signature != next) { signature = next; changed(paths.isNotEmpty()) }
    }
    private fun update(network: Network, caps: NetworkCapabilities?, links: LinkProperties?) {
        if (caps == null || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) paths.remove(network)
        else paths[network] = links?.let { "${it.interfaceName}:${it.linkAddresses}" } ?: paths[network].orEmpty()
        main.removeCallbacks(publish)
        // Loss must stop input promptly; additions/updates are debounced.
        if (paths.isEmpty()) publish.run() else main.postDelayed(publish, 750)
    }
    private val callback = object : ConnectivityManager.NetworkCallback() {
        private val caps = mutableMapOf<Network, NetworkCapabilities>()
        private val links = mutableMapOf<Network, LinkProperties>()
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            caps[network] = capabilities; update(network, capabilities, links[network])
        }
        override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) {
            links[network] = properties
            caps[network]?.let { update(network, it, properties) }
        }
        override fun onLost(network: Network) { caps.remove(network); links.remove(network); update(network, null, null) }
    }
    init {
        cm.allNetworks.forEach { network -> update(network, cm.getNetworkCapabilities(network), cm.getLinkProperties(network)) }
        signature = paths.entries.sortedBy { it.key.toString() }.joinToString { "${it.key}:${it.value}" }
        main.removeCallbacks(publish)
        cm.registerNetworkCallback(NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build(), callback, main)
        if (paths.isEmpty()) changed(false)
    }
    fun close() { closed = true; main.removeCallbacks(publish); cm.unregisterNetworkCallback(callback) }
}
