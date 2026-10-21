package com.safesphere.app.core.device

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Tells whether the phone has a usable internet connection. It is a HINT used to retry sooner and to
 * explain delays: uploads are still attempted when it says "offline", because a captive or flaky network
 * can be misreported either way.
 */
interface ConnectivityMonitor {
    /** Emits the current state immediately, then every change. */
    val isOnline: Flow<Boolean>
}

/** Uses ConnectivityManager (needs ACCESS_NETWORK_STATE). */
class AndroidConnectivityMonitor(context: Context) : ConnectivityMonitor {

    private val connectivityManager: ConnectivityManager? =
        context.applicationContext.getSystemService(ConnectivityManager::class.java)

    override val isOnline: Flow<Boolean> = callbackFlow {
        val manager = connectivityManager
        if (manager == null) {
            trySend(false)
            close()
            return@callbackFlow
        }
        trySend(manager.hasInternet(manager.activeNetwork))

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                trySend(capabilities.hasInternetCapabilities())
            }

            override fun onLost(network: Network) {
                trySend(false)
            }
        }
        manager.registerDefaultNetworkCallback(callback)
        awaitClose { manager.unregisterNetworkCallback(callback) }
    }.distinctUntilChanged()

    private fun ConnectivityManager.hasInternet(network: Network?): Boolean =
        network != null && getNetworkCapabilities(network)?.hasInternetCapabilities() == true

    private fun NetworkCapabilities.hasInternetCapabilities(): Boolean =
        hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}
