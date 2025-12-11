package com.example.travelmate.ui.activities

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.example.travelmate.R
import com.google.android.material.snackbar.Snackbar

class DashboardActivity : AppCompatActivity() {

    private lateinit var tvWelcomeUser: TextView
    private lateinit var tvUserRole: TextView

    private lateinit var cardUsers: View
    private lateinit var cardTrips: View
    private lateinit var cardSettings: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dashboard)

        // USER INFO CARD
        tvWelcomeUser = findViewById(R.id.tvWelcomeUser)
        tvUserRole = findViewById(R.id.tvUserRole)

        // CARDS
        cardUsers = findViewById(R.id.cardUsers)
        cardTrips = findViewById(R.id.cardTrips)
        cardSettings = findViewById(R.id.cardSettings)

        // SECURE PREFS --------------------------------------------------------------
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

        val email = securePrefs.getString("email", "Traveler")
        val role = securePrefs.getString("role", "user")

        // SET USER INFO --------------------------------------------------------------
        tvWelcomeUser.text = "Welcome, $email 👋"
        tvUserRole.text = "Role: ${role?.replaceFirstChar { it.uppercase() }}"

        // ROLE-BASED UI --------------------------------------------------------------
        when (role) {

            // ⭐ GUEST MODE ---------------------------------------------------------
            "guest" -> {
                cardUsers.visibility = View.GONE

                Snackbar.make(
                    tvWelcomeUser,
                    "Guest mode: trips are saved only locally.",
                    Snackbar.LENGTH_LONG
                ).show()

                cardTrips.setOnClickListener {
                    startActivity(Intent(this, TripListActivity::class.java))
                }
            }

            // ⭐ ADMIN + SUPERADMIN -------------------------------------------------
            "admin", "superadmin" -> {
                cardUsers.visibility = View.VISIBLE

                cardUsers.setOnClickListener {
                    Snackbar.make(it, "Opening user management...", Snackbar.LENGTH_SHORT).show()
                    startActivity(Intent(this, AdminUsersActivity::class.java))
                }

                cardTrips.setOnClickListener {
                    startActivity(Intent(this, TripListActivity::class.java))
                }
            }

            // ⭐ NORMAL USER -------------------------------------------------------
            else -> {
                cardUsers.visibility = View.GONE

                cardTrips.setOnClickListener {
                    startActivity(Intent(this, TripListActivity::class.java))
                }
            }
        }

        // SETTINGS CARD --------------------------------------------------------------
        cardSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }
}
