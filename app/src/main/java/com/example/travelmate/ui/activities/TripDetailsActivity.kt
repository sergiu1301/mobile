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
import com.example.travelmate.network.RemoteServerClient
import com.example.travelmate.network.WeatherService
import com.example.travelmate.repository.TripRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TripDetailsActivity : AppCompatActivity() {

    private var currentTrip: Trip? = null
    private lateinit var repo: TripRepository

    private lateinit var tvTitle: TextView
    private lateinit var tvDestination: TextView
    private lateinit var tvDates: TextView
    private lateinit var tvNotes: TextView
    private lateinit var tvWeather: TextView
    private lateinit var btnEditTrip: Button

    private var loggedUserEmail: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_trip_details)

        initViews()
        loadSecurePrefs()

        val tripId = intent.getIntExtra("trip_id", -1)
        if (tripId == -1) {
            toast("Trip not found")
            finish()
            return
        }

        val db = TripDatabase.getDatabase(this)
        repo = TripRepository(db.tripDao())

        loadTrip(tripId)
        setupEditButton()
    }

    private fun initViews() {
        tvTitle = findViewById(R.id.tvTitle)
        tvDestination = findViewById(R.id.tvDestination)
        tvDates = findViewById(R.id.tvDates)
        tvNotes = findViewById(R.id.tvNotes)
        tvWeather = findViewById(R.id.tvWeather)
        btnEditTrip = findViewById(R.id.btnEditTrip)
    }

    // ---------------------------------------------------------
    // LOAD USER EMAIL (ownerEmail check)
    // ---------------------------------------------------------
    private fun loadSecurePrefs() {
        val masterKey = MasterKey.Builder(this)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        val prefs = EncryptedSharedPreferences.create(
            this,
            "secure_user_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )

        loggedUserEmail = prefs.getString("email", null)

        if (loggedUserEmail == null) {
            toast("Session expired, please login again")
            finish()
        }
    }

    // ---------------------------------------------------------
    // SERVER CHECK
    // ---------------------------------------------------------
    private suspend fun requireServerOnline(): Boolean {
        val result = withContext(Dispatchers.IO) { RemoteServerClient.ping() }

        return if (result.isSuccess) {
            true
        } else {
            tvWeather.text = "Server unavailable ❌"
            toast("Server unavailable ❌")
            false
        }
    }

    // ---------------------------------------------------------
    // LOAD TRIP + VERIFY OWNER
    // ---------------------------------------------------------
    private fun loadTrip(tripId: Int) {
        lifecycleScope.launch {
            val trip = withContext(Dispatchers.IO) { repo.getTripById(tripId) }

            if (trip == null) {
                toast("Trip not found")
                finish()
                return@launch
            }

            // ❗ New correct check
            if (trip.ownerEmail != loggedUserEmail) {
                toast("Access denied 🚫")
                finish()
                return@launch
            }

            currentTrip = trip
            bindTripToUI(trip)
            loadWeather(trip)
        }
    }

    private fun bindTripToUI(trip: Trip) {
        tvTitle.text = trip.title
        tvDestination.text = trip.destination
        tvDates.text = "${trip.startDate} → ${trip.endDate}"
        tvNotes.text = if (trip.notes.isBlank()) "No notes" else trip.notes

        tvWeather.text = when {
            trip.weatherTemp != null -> "${trip.weatherTemp}, ${trip.weatherDescription}"
            else -> "Loading weather..."
        }
    }

    // ---------------------------------------------------------
    // WEATHER FETCH
    // ---------------------------------------------------------
    private suspend fun loadWeather(trip: Trip) {
        if (!isOnline()) {
            tvWeather.text = "Weather unavailable (offline)"
            return
        }

        if (!requireServerOnline()) {
            tvWeather.text = "Weather unavailable (server down)"
            return
        }

        val weather = withContext(Dispatchers.IO) {
            WeatherService.getWeather(trip.destination)
        }

        if (weather != null) {
            repo.updateWeather(trip.id, weather.first, weather.second)
            tvWeather.text = "${weather.first}, ${weather.second}"
        } else {
            tvWeather.text = "Weather data unavailable"
        }
    }

    private fun setupEditButton() {
        btnEditTrip.setOnClickListener {
            currentTrip?.let { trip ->
                val intent = Intent(this, AddTripActivity::class.java)
                intent.putExtra("edit_trip_id", trip.id)
                startActivity(intent)
            }
        }
    }

    // ---------------------------------------------------------
    private fun isOnline(): Boolean {
        val cm = getSystemService(ConnectivityManager::class.java)
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
