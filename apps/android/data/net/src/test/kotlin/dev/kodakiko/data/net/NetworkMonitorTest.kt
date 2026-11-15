package dev.kodakiko.data.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities

@RunWith(RobolectricTestRunner::class)
class NetworkMonitorTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    private fun capabilities(vararg transports: Int, validated: Boolean = true): NetworkCapabilities =
        ShadowNetworkCapabilities.newInstance().also { caps ->
            transports.forEach { shadowOf(caps).addTransportType(it) }
            shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            if (validated) shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }

    private fun callbacks() = shadowOf(connectivity).networkCallbacks

    private fun deliver(network: Network, caps: NetworkCapabilities) = callbacks().forEach {
        it.onAvailable(network)
        it.onCapabilitiesChanged(network, caps)
    }

    @Test
    fun startsOfflineAndRegistersOneDefaultNetworkCallback() {
        val monitor = NetworkMonitor(context)
        assertEquals(NetworkState(online = false, unmetered = false), monitor.current())
        assertEquals(1, callbacks().size)
        monitor.close()
        assertTrue(callbacks().isEmpty())
    }

    @Test
    fun wifiAndEthernetAreUnmeteredAndCellularIsNot() {
        val monitor = NetworkMonitor(context)
        val network = ShadowNetwork.newInstance(1)
        deliver(network, capabilities(NetworkCapabilities.TRANSPORT_WIFI))
        assertEquals(NetworkState(online = true, unmetered = true), monitor.current())
        deliver(network, capabilities(NetworkCapabilities.TRANSPORT_ETHERNET))
        assertEquals(NetworkState(online = true, unmetered = true), monitor.current())
        deliver(network, capabilities(NetworkCapabilities.TRANSPORT_CELLULAR))
        assertEquals(NetworkState(online = true, unmetered = false), monitor.current())
        deliver(network, capabilities(NetworkCapabilities.TRANSPORT_WIFI, NetworkCapabilities.TRANSPORT_VPN))
        assertEquals(NetworkState(online = true, unmetered = true), monitor.current())
        deliver(network, capabilities(NetworkCapabilities.TRANSPORT_CELLULAR, NetworkCapabilities.TRANSPORT_VPN))
        assertEquals(NetworkState(online = true, unmetered = false), monitor.current())
        monitor.close()
    }

    @Test
    fun aNetworkWithoutValidatedInternetIsOffline() {
        val monitor = NetworkMonitor(context)
        deliver(ShadowNetwork.newInstance(2), capabilities(NetworkCapabilities.TRANSPORT_WIFI, validated = false))
        assertEquals(NetworkState(online = false, unmetered = true), monitor.current())
        monitor.close()
    }

    @Test
    fun losingTheDefaultNetworkGoesOfflineAndListenersHearEveryChangeOnce() {
        val monitor = NetworkMonitor(context)
        val seen = mutableListOf<NetworkState>()
        val unsubscribe = monitor.subscribe { seen += it }
        val network = ShadowNetwork.newInstance(3)
        deliver(network, capabilities(NetworkCapabilities.TRANSPORT_WIFI))
        deliver(network, capabilities(NetworkCapabilities.TRANSPORT_WIFI))
        callbacks().forEach { it.onLost(network) }
        assertEquals(
            listOf(NetworkState(online = true, unmetered = true), NetworkState(online = false, unmetered = false)),
            seen,
        )
        unsubscribe()
        deliver(network, capabilities(NetworkCapabilities.TRANSPORT_CELLULAR))
        assertEquals(2, seen.size)
        assertEquals(NetworkState(online = true, unmetered = false), monitor.current())
        monitor.close()
    }

    @Test
    fun isWifiMatchesTheV031Question() {
        val monitor = NetworkMonitor(context)
        deliver(ShadowNetwork.newInstance(4), capabilities(NetworkCapabilities.TRANSPORT_ETHERNET))
        assertTrue(monitor.isWifi())
        monitor.close()
    }
}
