package com.campusmeal.android.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.shareIn

/**
 * Whether the device has a network with internet capability. It is only a hint for when to retry:
 * HTTP results stay authoritative about whether the CampusMeal backend is reachable.
 */
interface ConnectivityObserver {
    val isOnline: Flow<Boolean>
}

/**
 * One default-network callback, registered only while someone collects [isOnline] (for example a
 * screen collecting with `collectAsStateWithLifecycle`) and unregistered shortly after the last
 * collector stops, so callbacks are neither duplicated nor leaked. No polling.
 */
class AndroidConnectivityObserver(
    context: Context,
    scope: CoroutineScope,
) : ConnectivityObserver {

    private val connectivityManager =
        context.applicationContext.getSystemService(ConnectivityManager::class.java)

    override val isOnline: Flow<Boolean> = callbackFlow {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                trySend(capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
            }

            override fun onLost(network: Network) {
                trySend(false)
            }
        }
        trySend(currentlyOnline())
        connectivityManager.registerDefaultNetworkCallback(callback)
        awaitClose { connectivityManager.unregisterNetworkCallback(callback) }
    }
        .distinctUntilChanged()
        .shareIn(scope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), replay = 1)

    private fun currentlyOnline(): Boolean =
        connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
