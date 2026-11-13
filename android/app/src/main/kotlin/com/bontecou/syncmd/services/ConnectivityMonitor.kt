package com.bontecou.syncmd.services

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whether the phone has a validated internet connection, from a [ConnectivityManager.NetworkCallback].
 * Home uses it for the offline state ("Keep writing. We'll catch up.") and to hold Sync all.
 */
@Singleton
class ConnectivityMonitor @Inject constructor(@ApplicationContext context: Context) {
    private val manager = context.getSystemService(ConnectivityManager::class.java)
    private val networks = mutableMapOf<Network, Boolean>()
    private val _online = MutableStateFlow(currentlyOnline())
    /** True while at least one network has internet capability; true when the state can't be read. */
    val online: StateFlow<Boolean> = _online.asStateFlow()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = update {
            networks[network] = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }
        override fun onLost(network: Network) = update { networks.remove(network) }
        override fun onUnavailable() = update { networks.clear() }
    }

    init {
        runCatching {
            manager?.registerNetworkCallback(
                NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(), callback)
        }
    }

    private fun update(change: () -> Unit) = synchronized(networks) {
        change()
        _online.value = networks.values.any { it }
    }

    private fun currentlyOnline(): Boolean = runCatching {
        val capabilities = manager?.getNetworkCapabilities(manager.activeNetwork) ?: return@runCatching manager == null
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }.getOrDefault(true)
}
