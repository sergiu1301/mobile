package com.example.travelmate.ui.activities

import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.example.travelmate.R
import com.example.travelmate.data.Trip
import com.example.travelmate.data.TripDatabase
import com.example.travelmate.network.WeatherService
import com.example.travelmate.repository.TripRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TripDetailsActivity : AppCompatActivity() {

    private var currentTrip: Trip? = null
    private lateinit var repo: TripRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_trip_details)

        val tvTitle = findViewById<TextView>(R.id.tvTitle)
        val tvDestination = findViewById<TextView>(R.id.tvDestination)
        val tvDates = findViewById<TextView>(R.id.tvDates)
        val tvNotes = findViewById<TextView>(R.id.tvNotes)
        val tvWeather = findViewById<TextView>(R.id.tvWeather)
        val btnEditTrip = findViewById<Button>(R.id.btnEditTrip)

        val tripId = intent.getIntExtra("trip_id", -1)
        if (tripId == -1) {
            Toast.makeText(this, "Trip not found", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val masterKey = MasterKey.Builder(this)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        val securePrefs = EncryptedSharedPreferences.create(
            this,
            "secure_user_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )

        val loggedEmail = securePrefs.getString("email", null)

        val db = TripDatabase.getDatabase(this)
        repo = TripRepository(db.tripDao())

        lifecycleScope.launch {
            val trip = withContext(Dispatchers.IO) { repo.getTripById(tripId) }

            if (trip == null) {
                runOnUiThread {
                    Toast.makeText(this@TripDetailsActivity, "Trip not found", Toast.LENGTH_SHORT).show()
                    finish()
                }
                return@launch
            }

            if (trip.ownerEmail != loggedEmail) {
                runOnUiThread {
                    Toast.makeText(this@TripDetailsActivity, "Access denied 🚫", Toast.LENGTH_LONG).show()
                    finish()
                }
                return@launch
            }

            currentTrip = trip

            runOnUiThread {
                tvTitle.text = trip.title
                tvDestination.text = "Destination: ${trip.destination}"
                tvDates.text = "From ${trip.startDate} to ${trip.endDate}"
                tvNotes.text = "Notes: ${trip.notes.ifEmpty { "No notes" }}"
            }

            val weatherText = when {
                trip.weatherTemp != null && trip.weatherDescription != null ->
                    "${trip.weatherTemp}, ${trip.weatherDescription}"

                isOnline() -> {
                    val weather = withContext(Dispatchers.IO) {
                        WeatherService.getWeather(trip.destination)
                    }
                    if (weather != null) {
                        repo.updateWeather(trip.id, weather.first, weather.second)
                        "${weather.first}, ${weather.second}"
                    } else "Weather data unavailable 🌥️"
                }

                else -> "Weather unavailable (offline)"
            }

            runOnUiThread {
                tvWeather.text = weatherText
            }
        }

        btnEditTrip.setOnClickListener {
            currentTrip?.let { trip ->
                val intent = Intent(this, AddTripActivity::class.java)
                intent.putExtra("edit_trip_id", trip.id)
                startActivity(intent)
            }
        }
    }

    private fun isOnline(): Boolean {
        val connectivityManager = getSystemService(ConnectivityManager::class.java)
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
