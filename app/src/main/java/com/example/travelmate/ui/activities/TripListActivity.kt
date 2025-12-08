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
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.example.travelmate.R
import com.example.travelmate.TripAdapter
import com.example.travelmate.data.Trip
import com.example.travelmate.data.TripDatabase
import com.example.travelmate.network.RemoteServerClient
import com.example.travelmate.network.WeatherService
import com.example.travelmate.repository.TripRepository
import com.example.travelmate.utils.NetworkMonitor
import com.example.travelmate.utils.PendingSyncManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TripListActivity : AppCompatActivity() {

    private lateinit var tripRepo: TripRepository
    private lateinit var networkMonitor: NetworkMonitor
    private lateinit var tvOffline: TextView
    private lateinit var recyclerView: RecyclerView
    private lateinit var btnAddTrip: Button
    private lateinit var securePrefs: android.content.SharedPreferences
    private lateinit var pendingSyncManager: PendingSyncManager

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
        pendingSyncManager = PendingSyncManager(this, tripRepo)

        val masterKey = MasterKey.Builder(this)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        securePrefs = EncryptedSharedPreferences.create(
            this,
            "secure_user_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )

        currentUserEmail = securePrefs.getString("email", "") ?: ""

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
                    syncPendingTrips()
                }
                lastNetworkState = isOnline
            }
        }

        val initialOnline = networkMonitor.isCurrentlyOnline()
        updateNetworkStatus(initialOnline)
        lastNetworkState = initialOnline
        networkMonitor.start()

        if (initialOnline) {
            syncPendingTrips()
        }

        btnAddTrip.setOnClickListener {
            startActivity(Intent(this, AddTripActivity::class.java))
        }
    }

    private fun updateNetworkStatus(isOnline: Boolean) {
        if (isOnline) {
            tvOffline.text = "✅ Online Mode – trips will sync automatically"
            tvOffline.setTextColor(Color.parseColor("#2E7D32"))
        } else {
            tvOffline.text = "⚠️ Offline Mode – changes will sync later"
            tvOffline.setTextColor(Color.parseColor("#E65100"))
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
        val token = securePrefs.getString("auth_token", null) ?: return
        lifecycleScope.launch {
            val syncResult = withContext(Dispatchers.IO) {
                RemoteServerClient.syncTrips(token, trips)
            }

            syncResult.onSuccess {
                Toast.makeText(this@TripListActivity, "Trips synced to cloud ☁️", Toast.LENGTH_SHORT).show()
            }.onFailure {
                Toast.makeText(this@TripListActivity, "Trip sync failed ⚠️", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun syncPendingTrips() {
        val token = securePrefs.getString("auth_token", null) ?: return
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { pendingSyncManager.syncPendingTrips(token) }
            result.onSuccess { synced ->
                if (synced > 0) {
                    Toast.makeText(this@TripListActivity, "Pending trips synced ($synced)", Toast.LENGTH_SHORT).show()
                    loadTripsForUser()
                }
            }.onFailure {
                Toast.makeText(this@TripListActivity, "Still offline, will retry sync", Toast.LENGTH_SHORT).show()
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
