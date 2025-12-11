package com.example.travelmate.utils

import android.content.Context
import android.net.*
import kotlinx.coroutines.*
import java.net.HttpURLConnection
import java.net.URL

class NetworkMonitor(
    private val context: Context,
    private val onStatusChange: (Boolean) -> Unit
) {

    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private var isOnline = false
    private var scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            verifyInternet()
        }

        override fun onLost(network: Network) {
            verifyInternet()
        }
    }

    fun start() {
        val req = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        cm.registerNetworkCallback(req, callback)
        verifyInternet()
    }

    fun stop() {
        try { cm.unregisterNetworkCallback(callback) } catch (_: Exception) {}
        scope.cancel()
    }

    private fun hasNetwork(): Boolean {
        val active = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(active) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun verifyInternet() {
        scope.launch {
            if (!hasNetwork()) {
                update(false)
                return@launch
            }

            // 🔥 Verificare reală fără ping
            val hasInternet = try {
                val url = URL("https://clients3.google.com/generate_204")
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 1500
                conn.readTimeout = 1500
                conn.requestMethod = "GET"
                conn.connect()

                conn.responseCode == 204
            } catch (e: Exception) {
                false
            }

            update(hasInternet)
        }
    }

    private fun update(status: Boolean) {
        if (status != isOnline) {
            isOnline = status
            onStatusChange(status)
        }
    }

    fun isCurrentlyOnline(): Boolean = isOnline
}
