package com.example.travelmate.ui.activities

import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.widget.Switch
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.example.travelmate.R
import com.example.travelmate.data.TripDatabase
import com.example.travelmate.network.RemoteServerClient
import com.example.travelmate.repository.UserRepository
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.material.card.MaterialCardView
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : AppCompatActivity() {

    private lateinit var switchBiometrics: Switch
    private lateinit var cardBiometrics: MaterialCardView
    private lateinit var cardLogout: MaterialCardView

    private lateinit var securePrefs: SharedPreferences
    private lateinit var userRepository: UserRepository
    private lateinit var googleClient: GoogleSignInClient

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        initSecureStorage()
        initViews()
        initDatabase()
        initGoogleClient()

        val email = securePrefs.getString("email", null)
        val isGuest = securePrefs.getBoolean("guest_active", false)

        // 🔥 Guest NU trebuie să aibă biometrics
        if (isGuest || email == "guest@local") {
            disableBiometricsForGuest()
        } else if (email != null) {
            loadBiometricState(email)
        }

        setupLogout(isGuest)
    }

    // --------------------------------------------------------
    // SERVER CHECK HELPER
    // --------------------------------------------------------
    private suspend fun requireServerOnline(action: suspend () -> Unit) {
        val ping = withContext(Dispatchers.IO) { RemoteServerClient.ping() }

        if (ping.isFailure) {
            Snackbar.make(
                switchBiometrics,
                "Server unavailable ❌ Try again later",
                Snackbar.LENGTH_LONG
            ).setBackgroundTint(
                ContextCompat.getColor(this, android.R.color.holo_red_dark)
            ).show()
            return
        }

        action()
    }

    // --------------------------------------------------------
    private fun initSecureStorage() {
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
    }

    private fun initViews() {
        switchBiometrics = findViewById(R.id.switchBiometrics)
        cardBiometrics = findViewById(R.id.cardBiometrics)
        cardLogout = findViewById(R.id.cardLogout)
    }

    private fun initDatabase() {
        val db = TripDatabase.getDatabase(this)
        userRepository = UserRepository(db.userDao())
    }

    private fun initGoogleClient() {
        googleClient = GoogleSignIn.getClient(
            this,
            com.google.android.gms.auth.api.signin.GoogleSignInOptions.Builder(
                com.google.android.gms.auth.api.signin.GoogleSignInOptions.DEFAULT_SIGN_IN
            ).requestEmail().build()
        )
    }

    // --------------------------------------------------------
    // 🔥 Disable biometrics completely for guest users
    // --------------------------------------------------------
    private fun disableBiometricsForGuest() {
        switchBiometrics.isEnabled = false
        cardBiometrics.alpha = 0.35f

        // Șterge orice urma de fingerprint pentru guest
        securePrefs.edit()
            .remove("last_biometric_user")
            .remove("fingerprint_enabled_guest@local")
            .apply()
    }

    // --------------------------------------------------------
    // BIOMETRICS TOGGLE
    // --------------------------------------------------------
    private fun loadBiometricState(email: String) {
        lifecycleScope.launch {
            val user = withContext(Dispatchers.IO) { userRepository.getUserByEmail(email) }
                ?: return@launch

            switchBiometrics.isChecked = user.useBiometrics

            switchBiometrics.setOnCheckedChangeListener { _, enabled ->
                lifecycleScope.launch {
                    requireServerOnline {
                        withContext(Dispatchers.IO) {
                            userRepository.updateUseBiometrics(email, enabled)
                        }

                        securePrefs.edit()
                            .putBoolean("fingerprint_enabled_$email", enabled)
                            .apply()

                        val msg =
                            if (enabled) "Biometric login enabled" else "Biometric login disabled"

                        Snackbar.make(switchBiometrics, "$msg ✅", Snackbar.LENGTH_SHORT)
                            .setBackgroundTint(
                                ContextCompat.getColor(
                                    this@SettingsActivity,
                                    android.R.color.holo_green_dark
                                )
                            ).show()
                    }
                }
            }
        }
    }

    // --------------------------------------------------------
    // LOGOUT
    // --------------------------------------------------------
    private fun setupLogout(isGuest: Boolean) {

        val logout = {
            lifecycleScope.launch {

                requireServerOnline {

                    // 🔥 Guest logout behaves differently
                    if (isGuest) {
                        securePrefs.edit().clear().apply()

                        Snackbar.make(cardLogout, "Guest session ended 👋", Snackbar.LENGTH_SHORT)
                            .setBackgroundTint(
                                ContextCompat.getColor(
                                    this@SettingsActivity,
                                    android.R.color.holo_red_dark
                                )
                            ).show()

                        startActivity(Intent(this@SettingsActivity, LoginActivity::class.java))
                        finish()
                        return@requireServerOnline
                    }

                    // Normal logout
                    val email = securePrefs.getString("email", null)

                    if (email != null) {
                        val user = withContext(Dispatchers.IO) {
                            userRepository.getUserByEmail(email)
                        }

                        // limpi fingerprint if disabled
                        if (user?.useBiometrics == false) {
                            securePrefs.edit().apply {
                                remove("fingerprint_enabled_$email")
                                remove("last_biometric_user")
                            }.apply()
                        }
                    }

                    val googleAcc = GoogleSignIn.getLastSignedInAccount(this@SettingsActivity)
                    if (googleAcc != null) googleClient.signOut()

                    securePrefs.edit().apply {
                        remove("email")
                        remove("role")
                        remove("auth_token")
                        remove("guest_active")
                    }.apply()

                    Snackbar.make(cardLogout, "Logged out successfully 👋", Snackbar.LENGTH_SHORT)
                        .setBackgroundTint(
                            ContextCompat.getColor(
                                this@SettingsActivity,
                                android.R.color.holo_red_dark
                            )
                        ).show()

                    startActivity(Intent(this@SettingsActivity, LoginActivity::class.java))
                    finish()
                }
            }
        }

        cardLogout.setOnClickListener { logout() }
    }
}
