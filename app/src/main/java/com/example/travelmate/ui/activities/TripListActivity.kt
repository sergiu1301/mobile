package com.example.travelmate.ui.activities

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
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
import com.example.travelmate.utils.NetworkMonitor
import kotlinx.coroutines.*

class TripListActivity : AppCompatActivity() {

    private lateinit var repo: TripRepository
    private lateinit var monitor: NetworkMonitor

    private lateinit var tvNetworkStatus: TextView
    private lateinit var tvNoTrips: TextView
    private lateinit var tripsContainer: LinearLayout
    private lateinit var progress: ProgressBar
    private lateinit var btnAddTrip: View

    private lateinit var prefs: EncryptedSharedPreferences

    private var email = ""
    private var token: String? = null
    private var lastOnline = false
    private var syncRunning = false

    // Guest FLAG
    private val isGuest: Boolean
        get() = email == "guest@local"

    // ============================================================
    // LIFECYCLE
    // ============================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_trip_list)

        bindViews()
        setupPrefs()

        repo = TripRepository(TripDatabase.getDatabase(this).tripDao())

        if (email.isBlank()) {
            Toast.makeText(this, "Session expired", Toast.LENGTH_SHORT).show()
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        btnAddTrip.setOnClickListener {
            startActivity(Intent(this, AddTripActivity::class.java))
        }

        setupNetworkMonitor()
        loadLocalTrips() // show local immediately
    }

    override fun onResume() {
        super.onResume()
        loadLocalTrips()
    }

    override fun onDestroy() {
        try { monitor.stop() } catch (_: Exception) {}
        super.onDestroy()
    }

    // ============================================================
    // SETUP
    // ============================================================

    private fun bindViews() {
        tvNetworkStatus = findViewById(R.id.tvNetworkStatus)
        tvNoTrips = findViewById(R.id.tvNoTrips)
        tripsContainer = findViewById(R.id.tripsContainer)
        progress = findViewById(R.id.progressBar)
        btnAddTrip = findViewById(R.id.btnAddTrip)
        progress.visibility = View.GONE
    }

    private fun setupPrefs() {
        val masterKey = MasterKey.Builder(this)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        prefs = EncryptedSharedPreferences.create(
            this,
            "secure_user_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        ) as EncryptedSharedPreferences

        email = prefs.getString("email", "") ?: ""
        token = prefs.getString("auth_token", null)
    }

    // ============================================================
    // NETWORK MONITOR with GUEST WEATHER LOGIC
    // ============================================================

    private fun setupNetworkMonitor() {
        monitor = NetworkMonitor(this) { online ->
            runOnUiThread {
                updateNetworkStatus(online)

                val becameOnline = (!lastOnline && online)
                lastOnline = online

                if (becameOnline) {
                    lifecycleScope.launch {
                        if (isGuest) {
                            // GUEST → ONLY UPDATE WEATHER
                            fixMissingWeatherBeforeSync()
                            loadLocalTrips()
                        } else {
                            // NORMAL USER → FULL SYNC
                            doSafeSync()
                        }
                    }
                }
            }
        }

        lastOnline = monitor.isCurrentlyOnline()
        updateNetworkStatus(lastOnline)
        monitor.start()
    }

    private fun updateNetworkStatus(online: Boolean) {
        tvNetworkStatus.text = if (online) "Online" else "Offline Mode"
        tvNetworkStatus.setTextColor(
            Color.parseColor(if (online) "#2E7D32" else "#E65100")
        )
    }

    // ============================================================
    // SYNC OR CHESTIONS
    // ============================================================

    private suspend fun doSafeSync() {
        if (syncRunning) return
        syncRunning = true

        try {
            progress.visibility = View.VISIBLE

            fullSyncWithServer()
            loadLocalTrips()
            showCustomToast("Synced successfully ✔", Color.parseColor("#4CAF50"))

        } finally {
            progress.visibility = View.GONE
            syncRunning = false
        }
    }

    // ============================================================
    // LOAD LOCAL TRIPS
    // ============================================================

    private fun loadLocalTrips() {
        lifecycleScope.launch {
            // GUEST + ONLINE → aplică weather înainte de afișare
            if (isGuest && monitor.isCurrentlyOnline()) {
                fixMissingWeatherBeforeSync()
            }

            val trips = withContext(Dispatchers.IO) {
                repo.getTripsForUser(email)
            }

            showTrips(trips)
        }
    }

    // ============================================================
    // FULL SYNC (server <-> local)
    // ============================================================

    private suspend fun fullSyncWithServer() {
        if (isGuest) {
            // Guest → only update weather locally
            fixMissingWeatherBeforeSync()
            return
        }

        val token = token ?: return

        fixMissingWeatherBeforeSync()

        val localTrips = withContext(Dispatchers.IO) {
            repo.getTripsForUser(email)
        }

        val syncResult = RemoteServerClient.syncTrips(token, localTrips)
        if (!syncResult.isSuccess) return

        val serverTrips = RemoteServerClient.getTrips(token).getOrNull() ?: return

        withContext(Dispatchers.IO) {
            serverTrips.forEach { it.isSynced = true }
            repo.replaceAllForUser(email, serverTrips)
        }
    }

    // ============================================================
    // WEATHER UPDATE LOCAL
    // ============================================================

    private suspend fun fixMissingWeatherBeforeSync() {
        val trips = withContext(Dispatchers.IO) { repo.getTripsForUser(email) }
        val missing = trips.filter { it.weatherTemp == null }

        if (missing.isEmpty()) return

        for (trip in missing) {
            val weather = WeatherService.getWeather(trip.destination)
            if (weather != null) {
                trip.weatherTemp = weather.first
                trip.weatherDescription = weather.second
                trip.isSynced = false

                withContext(Dispatchers.IO) { repo.updateTrip(trip) }
            }
        }
    }


    // ============================================================
    // DELETE
    // ============================================================

    private fun deleteTrip(trip: Trip) {
        lifecycleScope.launch(Dispatchers.IO) {

            repo.deleteTrip(trip)

            if (!isGuest && monitor.isCurrentlyOnline() && token != null) {
                fullSyncWithServer()
            }

            withContext(Dispatchers.Main) { loadLocalTrips() }
        }
    }

    // ============================================================
    // UI
    // ============================================================

    private fun showTrips(trips: List<Trip>) {
        tripsContainer.removeAllViews()
        tvNoTrips.visibility = if (trips.isEmpty()) View.VISIBLE else View.GONE

        val inflater = layoutInflater

        for (trip in trips) {
            val card = inflater.inflate(R.layout.item_trip, tripsContainer, false)

            card.findViewById<TextView>(R.id.tvTripTitle).text = trip.title
            card.findViewById<TextView>(R.id.tvTripDestination).text = trip.destination
            card.findViewById<TextView>(R.id.tvTripDates).text =
                "${trip.startDate} → ${trip.endDate}"

            val tvWeather = card.findViewById<TextView>(R.id.tvTripWeather)
            val ivWeather = card.findViewById<ImageView>(R.id.ivWeatherIcon)

            if (trip.weatherTemp != null) {
                tvWeather.text = trip.weatherTemp
                ivWeather.setImageResource(getWeatherIcon(trip.weatherDescription))
            } else {
                tvWeather.text = "No data"
                ivWeather.setImageResource(R.drawable.ic_weather_unknown)
            }

            card.findViewById<ImageButton>(R.id.btnDeleteTrip).setOnClickListener {
                deleteTrip(trip)
            }

            card.setOnClickListener {
                startActivity(Intent(this, AddTripActivity::class.java).apply {
                    putExtra("edit_trip_id", trip.id)
                })
            }

            tripsContainer.addView(card)
        }
    }

    private fun showCustomToast(message: String, bgColor: Int) {
        val toast = Toast.makeText(this, message, Toast.LENGTH_SHORT)
        val view = toast.view
        view?.setBackgroundColor(bgColor)
        val padding = (16 * resources.displayMetrics.density).toInt()
        view?.setPadding(padding, padding, padding, padding)
        toast.show()
    }

    private fun getWeatherIcon(desc: String?): Int {
        if (desc == null) return R.drawable.ic_weather_unknown
        return when {
            desc.contains("sun", true) -> R.drawable.ic_weather_sun
            desc.contains("clear", true) -> R.drawable.ic_weather_sun
            desc.contains("cloud", true) -> R.drawable.ic_weather_cloud
            desc.contains("rain", true) -> R.drawable.ic_weather_rain
            else -> R.drawable.ic_weather_unknown
        }
    }
}
