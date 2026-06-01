package com.cybersiren.android.online

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

class ConnectivityObserver(context: Context) {

    companion object {
        private const val TAG = "ConnectivityObserver"

        @Volatile
        private var INSTANCE: ConnectivityObserver? = null

        fun getInstance(context: Context): ConnectivityObserver {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: ConnectivityObserver(context.applicationContext).also {
                    INSTANCE = it
                }
            }
        }
    }

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val _connectionState = MutableStateFlow(getCurrentConnectionState())
    val connectionState: StateFlow<ConnectionState> = _connectionState

    sealed class ConnectionState {
        object Available : ConnectionState()
        object Unavailable : ConnectionState()
        object Losing : ConnectionState()
        object Lost : ConnectionState()

        val isOnline: Boolean get() = this == Available
    }

    enum class NetworkType {
        WIFI,
        CELLULAR_4G,
        CELLULAR_5G,
        CELLULAR_OTHER,
        UNKNOWN
    }

    val isOnline: Boolean
        get() = _connectionState.value.isOnline

    fun getCurrentNetworkType(): NetworkType {
        val network = connectivityManager.activeNetwork ?: return NetworkType.UNKNOWN
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return NetworkType.UNKNOWN

        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkType.WIFI
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> {

                if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) {
                    NetworkType.CELLULAR_5G
                } else {
                    NetworkType.CELLULAR_4G
                }
            }
            else -> NetworkType.UNKNOWN
        }
    }

    fun observe(): Flow<ConnectionState> = callbackFlow {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.d(TAG, "Network available")
                trySend(ConnectionState.Available)
                _connectionState.value = ConnectionState.Available
            }

            override fun onLosing(network: Network, maxMsToLive: Int) {
                Log.d(TAG, "Network losing (${maxMsToLive}ms to live)")
                trySend(ConnectionState.Losing)
                _connectionState.value = ConnectionState.Losing
            }

            override fun onLost(network: Network) {
                Log.d(TAG, "Network lost")
                trySend(ConnectionState.Lost)
                _connectionState.value = ConnectionState.Lost
            }

            override fun onUnavailable() {
                Log.d(TAG, "Network unavailable")
                trySend(ConnectionState.Unavailable)
                _connectionState.value = ConnectionState.Unavailable
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities
            ) {
                val hasInternet = networkCapabilities.hasCapability(
                    NetworkCapabilities.NET_CAPABILITY_INTERNET
                )
                val isValidated = networkCapabilities.hasCapability(
                    NetworkCapabilities.NET_CAPABILITY_VALIDATED
                )

                if (hasInternet && isValidated) {
                    trySend(ConnectionState.Available)
                    _connectionState.value = ConnectionState.Available
                }
            }
        }

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .build()

        connectivityManager.registerNetworkCallback(request, callback)

        trySend(getCurrentConnectionState())

        awaitClose {
            connectivityManager.unregisterNetworkCallback(callback)
        }
    }.distinctUntilChanged()

    private fun getCurrentConnectionState(): ConnectionState {
        val network = connectivityManager.activeNetwork
        val capabilities = network?.let { connectivityManager.getNetworkCapabilities(it) }

        return if (capabilities != null &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        ) {
            ConnectionState.Available
        } else {
            ConnectionState.Unavailable
        }
    }

    fun hasFastConnection(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false

        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
               capabilities.linkDownstreamBandwidthKbps > 50000
    }
}
