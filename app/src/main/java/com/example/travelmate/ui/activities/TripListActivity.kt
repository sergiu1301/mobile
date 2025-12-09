package com.example.travelmate.ui.activities

import android.content.Intent
import android.content.SharedPreferences
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
import com.example.travelmate.repository.TripRepository
import com.example.travelmate.utils.NetworkMonitor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TripListActivity : AppCompatActivity() {

    private lateinit var repo: TripRepository
    private lateinit var monitor: NetworkMonitor

    private lateinit var tvNetworkStatus: TextView
    private lateinit var tvNoTrips: TextView
    private lateinit var tripsContainer: LinearLayout
    private lateinit var progress: ProgressBar
    private lateinit var btnAddTrip: View

    private lateinit var securePrefs: SharedPreferences

    private var currentEmail: String = ""
    private var isOnline = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_trip_list)

        bindViews()
        repo = TripRepository(TripDatabase.getDatabase(this).tripDao())
        securePrefs = loadPrefs()

        currentEmail = securePrefs.getString("email", "") ?: ""
        if (currentEmail.isBlank()) {
            Toast.makeText(this, "Session expired", Toast.LENGTH_SHORT).show()
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        btnAddTrip.setOnClickListener {
            startActivity(Intent(this, AddTripActivity::class.java))
        }

        setupNetworkMonitor()
        loadInitialData()
    }

    // -------------------------------------------------------
    private fun bindViews() {
        tvNetworkStatus = findViewById(R.id.tvNetworkStatus)
        tvNoTrips = findViewById(R.id.tvNoTrips)
        tripsContainer = findViewById(R.id.tripsContainer)
        progress = findViewById(R.id.progressBar)
        btnAddTrip = findViewById(R.id.btnAddTrip)
    }

    private fun loadPrefs() =
        EncryptedSharedPreferences.create(
            this,
            "secure_user_prefs",
            MasterKey.Builder(this).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )

    // -------------------------------------------------------
    private fun setupNetworkMonitor() {
        monitor = NetworkMonitor(this) { online ->
            runOnUiThread {
                isOnline = online
                tvNetworkStatus.text = if (online) "Online" else "Offline mode"
                tvNetworkStatus.setTextColor(
                    Color.parseColor(if (online) "#2E7D32" else "#E65100")
                )
            }
        }

        isOnline = monitor.isCurrentlyOnline()
        monitor.start()
    }

    // -------------------------------------------------------
    // FIRST LOAD: cache → server
    // -------------------------------------------------------
    private fun loadInitialData() {
        lifecycleScope.launch {
            val cached = withContext(Dispatchers.IO) { repo.getTripsForUser(currentEmail) }
            showTrips(cached)

            if (isOnline) fetchFromServer()
        }
    }

    // -------------------------------------------------------
    private suspend fun fetchFromServer() {
        progress.visibility = View.VISIBLE

        val token = securePrefs.getString("auth_token", null) ?: return

        val serverTrips = withContext(Dispatchers.IO) {
            val result = RemoteServerClient.getTrips(token)
            if (result.isSuccess) result.getOrNull() else null
        }

        progress.visibility = View.GONE

        if (serverTrips == null) {
            Toast.makeText(this, "Failed loading from server", Toast.LENGTH_SHORT).show()
            return
        }

        withContext(Dispatchers.IO) {
            repo.replaceAllForUser(currentEmail, serverTrips)
        }

        showTrips(serverTrips)
    }

    // -------------------------------------------------------
    private fun showTrips(trips: List<Trip>) {
        tripsContainer.removeAllViews()

        if (trips.isEmpty()) {
            tvNoTrips.visibility = View.VISIBLE
            return
        }

        tvNoTrips.visibility = View.GONE

        val inflater = layoutInflater

        trips.forEach { trip ->
            val card = inflater.inflate(R.layout.item_trip, tripsContainer, false)

            card.findViewById<TextView>(R.id.tvTripTitle).text = trip.title
            card.findViewById<TextView>(R.id.tvTripDestination).text = trip.destination
            card.findViewById<TextView>(R.id.tvTripDates).text =
                "${trip.startDate} → ${trip.endDate}"

            val deleteBtn = card.findViewById<View>(R.id.btnDeleteTrip)
            deleteBtn.setOnClickListener { deleteTrip(trip) }

            card.setOnClickListener {
                val intent = Intent(this, AddTripActivity::class.java)
                intent.putExtra("edit_trip_id", trip.id)
                startActivity(intent)
            }

            tripsContainer.addView(card)
        }
    }

    // -------------------------------------------------------
    private fun deleteTrip(trip: Trip) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { repo.deleteTrip(trip) }

            val cached = withContext(Dispatchers.IO) { repo.getTripsForUser(currentEmail) }
            showTrips(cached)

            if (isOnline) syncTrips()
        }
    }

    // -------------------------------------------------------
    private suspend fun syncTrips() {
        val token = securePrefs.getString("auth_token", null) ?: return

        val localTrips = withContext(Dispatchers.IO) {
            repo.getTripsForUser(currentEmail)
        }

        val result = withContext(Dispatchers.IO) {
            RemoteServerClient.syncTrips(token, localTrips)
        }

        if (result.isSuccess) {
            Toast.makeText(this, "Synced ☁️", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        loadInitialData()
    }

    override fun onDestroy() {
        try { monitor.stop() } catch (_: Exception) {}
        super.onDestroy()
    }
}

