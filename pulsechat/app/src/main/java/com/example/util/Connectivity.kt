package com.example.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.example.domain.NetworkKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Tracks the active network so the UI can show offline states and media rules can apply. */
class ConnectivityMonitor(context: Context) {
    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val _network = MutableStateFlow(currentKind())
    val network: StateFlow<NetworkKind> = _network.asStateFlow()
    val isOnline: Boolean get() = _network.value != NetworkKind.NONE

    init {
        try {
            cm.registerNetworkCallback(
                NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(),
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) { _network.value = currentKind() }
                    override fun onLost(network: Network) { _network.value = currentKind() }
                    override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) { _network.value = currentKind() }
                }
            )
        } catch (e: Exception) {
            // Some OEM builds throw when too many callbacks are registered; fall back to polling on demand.
        }
    }

    fun refresh() { _network.value = currentKind() }

    private fun currentKind(): NetworkKind {
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return NetworkKind.NONE
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return NetworkKind.NONE
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkKind.WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ->
                if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING)) NetworkKind.ROAMING else NetworkKind.CELLULAR
            else -> NetworkKind.WIFI
        }
    }
}

/** Estimated offset between the device clock and Firestore server time. */
class ServerClock(private val deviceClock: () -> Long = System::currentTimeMillis) {
    @Volatile private var offsetMs: Long = 0L
    private var samples = 0

    fun now(): Long = deviceClock() + offsetMs

    /** Called with a server timestamp for a write the server received at device time [localSentAt]. */
    @Synchronized
    fun observe(serverMillis: Long, localSentAt: Long) {
        if (serverMillis <= 0 || localSentAt <= 0) return
        val sample = serverMillis - localSentAt
        if (kotlin.math.abs(sample) >= MAX_OFFSET_MS) return
        // The first measurement is used as is: averaging from 0 would need many samples to catch up
        // with a phone whose clock is minutes off. Later ones are averaged to absorb network jitter.
        offsetMs = if (samples == 0) sample else (offsetMs * 3 + sample) / 4
        samples++
    }

    private companion object {
        const val MAX_OFFSET_MS = 7 * 24 * 3600_000L
    }
}
