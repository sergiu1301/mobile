package com.example.travelmate.ui.activities

import android.app.DatePickerDialog
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
import java.text.SimpleDateFormat
import java.util.*

class AddTripActivity : AppCompatActivity() {

    private var editingTripId: Int? = null
    private lateinit var repo: TripRepository
    private lateinit var dateFormat: SimpleDateFormat

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_add_trip)

        val etTripTitle = findViewById<EditText>(R.id.etTripTitle)
        val etDestination = findViewById<EditText>(R.id.etDestination)
        val etStartDate = findViewById<EditText>(R.id.etStartDate)
        val etEndDate = findViewById<EditText>(R.id.etEndDate)
        val etNotes = findViewById<EditText>(R.id.etNotes)
        val btnSaveTrip = findViewById<Button>(R.id.btnSaveTrip)
        val tvAddEditTitle = findViewById<TextView>(R.id.tvAddEditTitle)

        val db = TripDatabase.getDatabase(this)
        repo = TripRepository(db.tripDao())
        dateFormat = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault())

        etStartDate.setOnClickListener { showDatePicker(etStartDate) }
        etEndDate.setOnClickListener { showDatePicker(etEndDate) }

        editingTripId = intent.getIntExtra("edit_trip_id", -1).takeIf { it != -1 }

        editingTripId?.let { tripId ->
            lifecycleScope.launch(Dispatchers.IO) {
                val trip = repo.getTripById(tripId)
                trip?.let {
                    withContext(Dispatchers.Main) {
                        etTripTitle.setText(it.title)
                        etDestination.setText(it.destination)
                        etStartDate.setText(it.startDate)
                        etEndDate.setText(it.endDate)
                        etNotes.setText(it.notes)
                        btnSaveTrip.text = "Update Trip"
                        tvAddEditTitle.text = "Edit Trip"
                    }
                }
            }
        }

        btnSaveTrip.setOnClickListener {
            val title = etTripTitle.text.toString().trim()
            val destination = etDestination.text.toString().trim()
            val startDate = etStartDate.text.toString().trim()
            val endDate = etEndDate.text.toString().trim()
            val notes = etNotes.text.toString().trim()

            val errorMsg = validateInputs(title, destination, startDate, endDate, notes)
            if (errorMsg != null) {
                Toast.makeText(this, errorMsg, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
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

            val currentEmail = securePrefs.getString("email", null)

            if (currentEmail == null) {
                Toast.makeText(this, "Session expired. Please login again.", Toast.LENGTH_SHORT).show()
                finish()
                return@setOnClickListener
            }

            lifecycleScope.launch {
                if (editingTripId != null) {
                    updateTrip(
                        editingTripId!!,
                        title,
                        destination,
                        startDate,
                        endDate,
                        notes,
                        currentEmail
                    )
                } else {
                    addNewTrip(
                        title,
                        destination,
                        startDate,
                        endDate,
                        notes,
                        currentEmail
                    )
                }
            }
        }
    }

    private suspend fun addNewTrip(
        title: String,
        destination: String,
        startDate: String,
        endDate: String,
        notes: String,
        ownerEmail: String
    ) {
        val newTrip = Trip(
            title = title,
            destination = destination,
            startDate = startDate,
            endDate = endDate,
            notes = notes,
            ownerEmail = ownerEmail
        )

        if (isOnline()) {
            val weather = withContext(Dispatchers.IO) {
                WeatherService.getWeather(destination)
            }
            if (weather != null) {
                newTrip.weatherTemp = weather.first
                newTrip.weatherDescription = weather.second
            }
        }

        repo.insertTrip(newTrip)

        withContext(Dispatchers.Main) {
            val message = if (isOnline())
                "Trip added successfully ✅ (with weather)"
            else
                "Trip added (offline, will sync later ☁️)"
            Toast.makeText(this@AddTripActivity, message, Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private suspend fun updateTrip(
        id: Int,
        title: String,
        destination: String,
        startDate: String,
        endDate: String,
        notes: String,
        ownerEmail: String
    ) {
        val updatedTrip = Trip(
            id = id,
            title = title,
            destination = destination,
            startDate = startDate,
            endDate = endDate,
            notes = notes,
            ownerEmail = ownerEmail
        )

        if (isOnline()) {
            val weather = withContext(Dispatchers.IO) {
                WeatherService.getWeather(destination)
            }
            if (weather != null) {
                updatedTrip.weatherTemp = weather.first
                updatedTrip.weatherDescription = weather.second
            }
        }

        repo.updateTrip(updatedTrip)
        withContext(Dispatchers.Main) {
            Toast.makeText(this@AddTripActivity, "Trip updated successfully ✅", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun showDatePicker(targetEditText: EditText) {
        val calendar = Calendar.getInstance()
        val year = calendar.get(Calendar.YEAR)
        val month = calendar.get(Calendar.MONTH)
        val day = calendar.get(Calendar.DAY_OF_MONTH)

        DatePickerDialog(
            this,
            { _, selectedYear, selectedMonth, selectedDay ->
                val selectedDate = Calendar.getInstance()
                selectedDate.set(selectedYear, selectedMonth, selectedDay)
                targetEditText.setText(dateFormat.format(selectedDate.time))
            },
            year, month, day
        ).show()
    }

    private fun validateInputs(
        title: String,
        destination: String,
        startDate: String,
        endDate: String,
        notes: String
    ): String? {
        if (title.length < 3) return "Trip title must have at least 3 characters"
        if (destination.length < 3) return "Destination must have at least 3 characters"
        if (notes.length > 300) return "Notes can't exceed 300 characters"

        val start: Date
        val end: Date
        try {
            start = dateFormat.parse(startDate) ?: return "Invalid start date format"
            end = dateFormat.parse(endDate) ?: return "Invalid end date format"
        } catch (e: Exception) {
            return "Invalid date format (use dd.MM.yyyy)"
        }

        if (start.after(end)) return "Start date must be before end date"
        return null
    }

    private fun isOnline(): Boolean {
        val connectivityManager = getSystemService(ConnectivityManager::class.java)
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
