package com.example.travelmate.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.widget.Toast
import com.example.travelmate.data.TripDatabase
import com.example.travelmate.network.WeatherService
import com.example.travelmate.repository.TripRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class WeatherSyncReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (isOnline(context)) {
            CoroutineScope(Dispatchers.IO).launch {
                val db = TripDatabase.getDatabase(context)
                val repo = TripRepository(db.tripDao())

                val tripsWithoutWeather = repo.getAllTrips().filter {
                    it.weatherTemp == null || it.weatherDescription == null
                }

                var updatedCount = 0

                for (trip in tripsWithoutWeather) {
                    val weather = WeatherService.getWeather(trip.destination)
                    if (weather != null) {
                        repo.updateWeather(trip.id, weather.first, weather.second)
                        updatedCount++
                    }
                }

                if (updatedCount > 0) {
                    launch(Dispatchers.Main) {
                        Toast.makeText(
                            context,
                            "☀️ Synced weather for $updatedCount trip(s)",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }
    }

    private fun isOnline(context: Context): Boolean {
        val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
