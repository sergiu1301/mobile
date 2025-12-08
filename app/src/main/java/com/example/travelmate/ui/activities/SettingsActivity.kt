package com.example.travelmate.ui.activities

import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.widget.ImageView
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.example.travelmate.R
import com.example.travelmate.data.TripDatabase
import com.example.travelmate.repository.UserRepository
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : AppCompatActivity() {

    private lateinit var switchBiometrics: Switch
    private lateinit var tvLogout: TextView
    private lateinit var ivLogoutIcon: ImageView
    private lateinit var userRepository: UserRepository
    private lateinit var securePrefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

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

        switchBiometrics = findViewById(R.id.switchBiometrics)
        tvLogout = findViewById(R.id.btnLogout)
        ivLogoutIcon = findViewById(R.id.icLogout)

        val db = TripDatabase.getDatabase(this)
        userRepository = UserRepository(db.userDao())

        val email = securePrefs.getString("email", null)
        if (email.isNullOrEmpty()) {
            finish()
            return
        }

        lifecycleScope.launch {
            val user = withContext(Dispatchers.IO) { userRepository.getUserByEmail(email) }
            if (user != null) {
                switchBiometrics.isChecked = user.useBiometrics

                switchBiometrics.setOnCheckedChangeListener { _, isChecked ->
                    lifecycleScope.launch(Dispatchers.IO) {
                        userRepository.updateUseBiometrics(email, isChecked)
                    }

                    val message = if (isChecked)
                        "Biometric login enabled ✅"
                    else
                        "Biometric login disabled 🚫"

                    Snackbar.make(switchBiometrics, message, Snackbar.LENGTH_SHORT)
                        .setBackgroundTint(
                            ContextCompat.getColor(this@SettingsActivity, android.R.color.holo_green_dark)
                        )
                        .show()
                }
            }
        }

        val logoutAction = {
            lifecycleScope.launch {
                val email = securePrefs.getString("email", null)

                if (email != null) {
                    val user = withContext(Dispatchers.IO) { userRepository.getUserByEmail(email) }

                    if (user?.useBiometrics == false) {
                        securePrefs.edit().apply {
                            remove("biometric_enabled")
                            remove("fingerprint_email_active")
                            apply()
                        }
                    }
                }

                securePrefs.edit().apply {
                    remove("email")
                    remove("role")
                    apply()
                }

                Snackbar.make(tvLogout, "Logged out successfully 👋", Snackbar.LENGTH_SHORT)
                    .setBackgroundTint(ContextCompat.getColor(this@SettingsActivity, android.R.color.holo_red_dark))
                    .show()

                startActivity(Intent(this@SettingsActivity, LoginActivity::class.java))
                finish()
            }
        }


        tvLogout.setOnClickListener { logoutAction() }
        ivLogoutIcon.setOnClickListener { logoutAction() }
    }
}
