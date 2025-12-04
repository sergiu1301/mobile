package com.example.travelmate.ui.activities

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.travelmate.R
import com.example.travelmate.TripAdapter
import com.example.travelmate.data.Trip
import com.example.travelmate.data.TripDatabase
import com.example.travelmate.network.TokenManager
import com.example.travelmate.network.WeatherService
import com.example.travelmate.repository.TripRepository
import com.example.travelmate.utils.NetworkMonitor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TripListActivity : AppCompatActivity() {

    private lateinit var tripRepo: TripRepository
    private lateinit var networkMonitor: NetworkMonitor
    private lateinit var tvOffline: TextView
    private lateinit var recyclerView: RecyclerView
    private lateinit var btnAddTrip: Button
    private lateinit var tokenManager: TokenManager

    private var lastNetworkState: Boolean? = null
    private var currentUserEmail: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_trip_list)

        recyclerView = findViewById(R.id.recyclerTrips)
        tvOffline = findViewById(R.id.tvOffline)
        btnAddTrip = findViewById(R.id.btnAddTrip)

        recyclerView.layoutManager = LinearLayoutManager(this)

        val db = TripDatabase.getDatabase(this)
        tripRepo = TripRepository(db.tripDao())

        tokenManager = TokenManager.getInstance(this)

        currentUserEmail = tokenManager.getEmail() ?: ""

        if (currentUserEmail.isEmpty()) {
            Toast.makeText(this, "Session expired, please login again", Toast.LENGTH_SHORT).show()
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        loadTripsForUser()

        networkMonitor = NetworkMonitor(this) { isOnline ->
            runOnUiThread {
                updateNetworkStatus(isOnline)
                if (lastNetworkState == false && isOnline) {
                    Toast.makeText(this, "Back online 🌐 Syncing weather data...", Toast.LENGTH_SHORT).show()
                    syncWeatherData()
                }
                lastNetworkState = isOnline
            }
        }

        val initialOnline = networkMonitor.isCurrentlyOnline()
        updateNetworkStatus(initialOnline)
        lastNetworkState = initialOnline
        networkMonitor.start()

        btnAddTrip.setOnClickListener {
            startActivity(Intent(this, AddTripActivity::class.java))
        }
    }

    private fun updateNetworkStatus(isOnline: Boolean) {
        if (isOnline) {
            tvOffline.text = "✅ Online Mode – trips will sync automatically"
            tvOffline.setTextColor(Color.parseColor("#2E7D32"))
            btnAddTrip.isEnabled = true
            btnAddTrip.alpha = 1f
        } else {
            tvOffline.text = "⚠️ Offline Mode – viewing cached trips (read-only)"
            tvOffline.setTextColor(Color.parseColor("#E65100"))
            btnAddTrip.isEnabled = false
            btnAddTrip.alpha = 0.5f
        }
    }

    private fun loadTripsForUser() {
        lifecycleScope.launch {
            val trips = withContext(Dispatchers.IO) { tripRepo.getTripsForUser(currentUserEmail) }
            recyclerView.adapter = TripAdapter(trips, onDeleteClick = { trip ->
                deleteTrip(trip)
            })

            if (lastNetworkState != false) {
                syncTripsWithServer(trips)
            }
        }
    }

    private fun syncTripsWithServer(trips: List<Trip>) {
        val token = tokenManager.getToken() ?: return
        lifecycleScope.launch {
            val syncResult = withContext(Dispatchers.IO) {
                tripRepo.syncTrips(token, trips)
            }

            syncResult.onSuccess {
                Toast.makeText(this@TripListActivity, "Trips synced to cloud ☁️", Toast.LENGTH_SHORT).show()
            }.onFailure {
                Toast.makeText(this@TripListActivity, "Trip sync failed. We'll retry when you're online.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun deleteTrip(trip: Trip) {
        lifecycleScope.launch(Dispatchers.IO) {
            tripRepo.deleteTrip(trip)
            withContext(Dispatchers.Main) {
                Toast.makeText(this@TripListActivity, "Trip deleted 🗑️", Toast.LENGTH_SHORT).show()
                loadTripsForUser()
            }
        }
    }

    private fun syncWeatherData() {
        lifecycleScope.launch(Dispatchers.IO) {
            val trips = tripRepo.getTripsForUser(currentUserEmail)
            for (trip in trips) {
                if (trip.weatherTemp == null || trip.weatherDescription == null) {
                    val weather = WeatherService.getWeather(trip.destination)
                    if (weather != null) {
                        trip.weatherTemp = weather.first
                        trip.weatherDescription = weather.second
                        tripRepo.updateTrip(trip)
                    }
                }
            }
            withContext(Dispatchers.Main) {
                loadTripsForUser()
                Toast.makeText(this@TripListActivity, "Weather data synced ☀️", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        loadTripsForUser()
    }

    override fun onDestroy() {
        if (::networkMonitor.isInitialized) {
            try {
                networkMonitor.stop()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        super.onDestroy()
    }
}
