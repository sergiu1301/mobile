package com.example.travelmate.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.example.travelmate.network.RemoteServerClient
import com.example.travelmate.repository.TripRepository

class PendingSyncManager(
    private val context: Context,
    private val tripRepository: TripRepository
) {
    suspend fun syncPendingTrips(token: String): Result<Int> {
        if (!isOnline()) return Result.failure(IllegalStateException("Offline"))
        val pendingTrips = tripRepository.getPendingTrips()
        if (pendingTrips.isEmpty()) return Result.success(0)

        return RemoteServerClient.syncTrips(token, pendingTrips).onSuccess {
            pendingTrips.forEach { trip ->
                tripRepository.updatePendingStatus(trip.id, false)
            }
        }
    }

    private fun isOnline(): Boolean {
        val connectivityManager =
            context.getSystemService(ConnectivityManager::class.java) ?: return false
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
