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
import com.example.travelmate.network.RemoteServerClient
import com.example.travelmate.network.WeatherService
import com.example.travelmate.repository.TripRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.*

class AddTripActivity : AppCompatActivity() {

    private var editingTripId: Int? = null
    private lateinit var repo: TripRepository
    private lateinit var dateFormat: SimpleDateFormat

    private lateinit var etTripTitle: EditText
    private lateinit var etDestination: EditText
    private lateinit var etStartDate: EditText
    private lateinit var etEndDate: EditText
    private lateinit var etNotes: EditText
    private lateinit var btnSaveTrip: Button
    private lateinit var tvAddEditTitle: TextView

    private var ownerEmail: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_add_trip)

        bindViews()
        init()

        etStartDate.setOnClickListener { showDatePicker(etStartDate) }
        etEndDate.setOnClickListener { showDatePicker(etEndDate) }

        editingTripId = intent.getIntExtra("edit_trip_id", -1).takeIf { it != -1 }

        if (editingTripId != null) loadTripForEditing()

        btnSaveTrip.setOnClickListener { handleSave() }
    }

    // ---------------------------------------------------------
    private fun bindViews() {
        etTripTitle = findViewById(R.id.etTripTitle)
        etDestination = findViewById(R.id.etDestination)
        etStartDate = findViewById(R.id.etStartDate)
        etEndDate = findViewById(R.id.etEndDate)
        etNotes = findViewById(R.id.etNotes)
        btnSaveTrip = findViewById(R.id.btnSaveTrip)
        tvAddEditTitle = findViewById(R.id.tvAddEditTitle)
    }

    private fun init() {
        repo = TripRepository(TripDatabase.getDatabase(this).tripDao())
        dateFormat = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault())
        loadOwnerEmail()
    }

    // ---------------------------------------------------------
    private fun loadOwnerEmail() {
        val prefs = EncryptedSharedPreferences.create(
            this,
            "secure_user_prefs",
            MasterKey.Builder(this).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )

        ownerEmail = prefs.getString("email", null)

        if (ownerEmail == null) {
            toast("Session expired. Please login again.")
            finish()
        }
    }

    private fun getAuthToken(): String? {
        val prefs = EncryptedSharedPreferences.create(
            this,
            "secure_user_prefs",
            MasterKey.Builder(this).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )

        return prefs.getString("auth_token", null)
    }

    // ---------------------------------------------------------
    private fun loadTripForEditing() {
        lifecycleScope.launch(Dispatchers.IO) {
            val trip = repo.getTripById(editingTripId!!)
            if (trip != null) {
                withContext(Dispatchers.Main) {
                    tvAddEditTitle.text = "Edit Trip"
                    btnSaveTrip.text = "Update Trip"

                    etTripTitle.setText(trip.title)
                    etDestination.setText(trip.destination)
                    etStartDate.setText(trip.startDate)
                    etEndDate.setText(trip.endDate)
                    etNotes.setText(trip.notes)
                }
            }
        }
    }

    // ---------------------------------------------------------
    private fun handleSave() {
        val title = etTripTitle.text.toString().trim()
        val dest = etDestination.text.toString().trim()
        val start = etStartDate.text.toString().trim()
        val end = etEndDate.text.toString().trim()
        val notes = etNotes.text.toString().trim()

        val validationError = validateInputs(title, dest, start, end, notes)
        if (validationError != null) {
            toast(validationError)
            return
        }

        val email = ownerEmail ?: return

        lifecycleScope.launch {
            if (editingTripId != null)
                updateTrip(title, dest, start, end, notes, email)
            else
                createTrip(title, dest, start, end, notes, email)
        }
    }

    // ---------------------------------------------------------
    private fun validateInputs(
        title: String,
        destination: String,
        startDate: String,
        endDate: String,
        notes: String
    ): String? {

        if (title.length < 3) return "Trip title must have at least 3 characters"
        if (destination.length < 3) return "Destination must have at least 3 characters"
        if (notes.length > 400) return "Notes cannot exceed 400 characters"

        return try {
            val start = dateFormat.parse(startDate)
            val end = dateFormat.parse(endDate)
            if (start == null || end == null) return "Invalid date format"
            if (!start.before(end)) return "Start date must be before end date"
            null
        } catch (e: ParseException) {
            "Date must be in dd.MM.yyyy format"
        }
    }

    // ---------------------------------------------------------
    private suspend fun createTrip(
        title: String,
        destination: String,
        start: String,
        end: String,
        notes: String,
        ownerEmail: String
    ) {
        val isGuest = ownerEmail == "guest@local"
        val token = getAuthToken()
        val online = isOnline()

        val trip = Trip(
            title = title,
            destination = destination,
            startDate = start,
            endDate = end,
            notes = notes,
            ownerEmail = ownerEmail,
            isSynced = false
        )

        // ------------ GUEST MODE → DOAR LOCAL ------------
        if (isGuest) {
            val newId = repo.insertTrip(trip).toInt()
            trip.id = newId

            withContext(Dispatchers.Main) {
                toast("Trip saved locally ✔️ (guest mode)")
                finish()
            }
            return
        }

        // ------------ USER NORMAL ------------
        if (online) applyWeather(trip)

        val newId = repo.insertTrip(trip).toInt()
        trip.id = newId

        if (!online || token == null) {
            withContext(Dispatchers.Main) {
                toast("Trip saved offline ✔️ Will sync later")
                finish()
            }
            return
        }

        val syncResult = withContext(Dispatchers.IO) {
            RemoteServerClient.syncTrips(token, listOf(trip))
        }

        if (syncResult.isSuccess) {
            trip.isSynced = true
            repo.updateTrip(trip)
        }

        withContext(Dispatchers.Main) {
            toast("Trip added ✔️")
            finish()
        }
    }


    // ---------------------------------------------------------
    private suspend fun updateTrip(
        title: String,
        destination: String,
        start: String,
        end: String,
        notes: String,
        ownerEmail: String
    ) {
        val isGuest = ownerEmail == "guest@local"
        val token = getAuthToken()
        val online = isOnline()

        val trip = Trip(
            id = editingTripId!!,
            title = title,
            destination = destination,
            startDate = start,
            endDate = end,
            notes = notes,
            ownerEmail = ownerEmail,
            isSynced = false
        )

        // ------------ GUEST MODE → DOAR LOCAL ------------
        if (isGuest) {
            repo.updateTrip(trip)

            withContext(Dispatchers.Main) {
                toast("Trip updated locally ✨ (guest mode)")
                finish()
            }
            return
        }

        // ------------ USER NORMAL ------------
        if (online) applyWeather(trip)

        repo.updateTrip(trip)

        if (online && token != null) {
            val syncResult = withContext(Dispatchers.IO) {
                RemoteServerClient.syncTrips(token, listOf(trip))
            }

            if (syncResult.isSuccess) {
                trip.isSynced = true
                repo.updateTrip(trip)
            }
        }

        withContext(Dispatchers.Main) {
            toast("Trip updated ✨")
            finish()
        }
    }


    // ---------------------------------------------------------
    private suspend fun applyWeather(trip: Trip) {
        val w = withContext(Dispatchers.IO) {
            WeatherService.getWeather(trip.destination)
        }

        if (w != null) {
            trip.weatherTemp = w.first
            trip.weatherDescription = w.second
        }
    }

    // ---------------------------------------------------------
    private fun showDatePicker(target: EditText) {
        val c = Calendar.getInstance()
        DatePickerDialog(
            this,
            { _, year, month, day ->
                val sel = Calendar.getInstance()
                sel.set(year, month, day)
                target.setText(dateFormat.format(sel.time))
            },
            c.get(Calendar.YEAR),
            c.get(Calendar.MONTH),
            c.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    // ---------------------------------------------------------
    private fun isOnline(): Boolean {
        val cm = getSystemService(ConnectivityManager::class.java)
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    // ---------------------------------------------------------
    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
