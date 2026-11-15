package dev.kodakiko.data.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.io.Closeable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

data class NetworkState(val online: Boolean, val unmetered: Boolean) {
    companion object {
        val OFFLINE = NetworkState(online = false, unmetered = false)

        private val UNMETERED_TRANSPORTS = listOf(
            NetworkCapabilities.TRANSPORT_WIFI,
            NetworkCapabilities.TRANSPORT_ETHERNET,
        )

        fun of(capabilities: NetworkCapabilities): NetworkState = NetworkState(
            online = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            unmetered = UNMETERED_TRANSPORTS.any(capabilities::hasTransport),
        )
    }
}

class NetworkMonitor(context: Context) : Closeable {
    private val connectivity: ConnectivityManager =
        context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private val state = AtomicReference(NetworkState.OFFLINE)
    private val listeners = CopyOnWriteArrayList<(NetworkState) -> Unit>()
    private val closed = AtomicBoolean(false)

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) =
            update(NetworkState.of(networkCapabilities))

        override fun onLost(network: Network) = update(NetworkState.OFFLINE)
    }

    init {
        connectivity.registerDefaultNetworkCallback(callback)
    }

    fun current(): NetworkState = state.get()

    fun isWifi(): Boolean = current().unmetered

    fun subscribe(listener: (NetworkState) -> Unit): () -> Unit {
        listeners += listener
        return { listeners.remove(listener) }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            connectivity.unregisterNetworkCallback(callback)
            listeners.clear()
        }
    }

    private fun update(next: NetworkState) {
        if (state.getAndSet(next) != next) {
            listeners.forEach { it(next) }
        }
    }
}
