package com.example.travelmate.ui.activities

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.example.travelmate.R
import com.google.android.material.snackbar.Snackbar

class DashboardActivity : AppCompatActivity() {

    private lateinit var tvWelcome: TextView
    private lateinit var tvRole: TextView
    private lateinit var btnGoToTrips: Button
    private lateinit var btnManageUsers: Button
    private lateinit var btnLogout: Button

    private lateinit var tvGuestBanner: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dashboard)

        tvWelcome = findViewById(R.id.tvWelcome)
        tvRole = findViewById(R.id.tvRole)
        btnGoToTrips = findViewById(R.id.btnGoToTrips)
        btnManageUsers = findViewById(R.id.btnManageUsers)
        btnLogout = findViewById(R.id.btnLogout)
        tvGuestBanner = findViewById(R.id.tvGuestBanner)

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

        tvWelcome.text = "Welcome back, $email! 🌍"
        tvRole.text = "Role: ${role?.replaceFirstChar { it.uppercase() }}"

        when (role) {
            "guest" -> {
                tvGuestBanner.visibility = View.VISIBLE
                findViewById<View>(R.id.btnGoToTrips)?.isEnabled = false
            }
            "admin" -> {
                btnManageUsers.visibility = View.VISIBLE
                btnManageUsers.setOnClickListener {
                    Snackbar.make(it, "Opening user management...", Snackbar.LENGTH_SHORT)
                        .setBackgroundTint(ContextCompat.getColor(this, R.color.teal_700))
                        .show()
                    startActivity(Intent(this, AdminUsersActivity::class.java))
                }
            }
            else -> {
                btnManageUsers.visibility = View.GONE
            }
        }

        btnGoToTrips.setOnClickListener {
            Snackbar.make(it, "Opening your trips...", Snackbar.LENGTH_SHORT)
                .setBackgroundTint(ContextCompat.getColor(this, R.color.teal_700))
                .show()
            startActivity(Intent(this, TripListActivity::class.java))
        }

        btnLogout.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }
}
