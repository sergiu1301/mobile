package com.example.travelmate.ui.activities

import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.example.travelmate.R
import com.example.travelmate.data.TripDatabase
import com.example.travelmate.repository.UserRepository
import com.example.travelmate.network.RemoteServerClient
import com.google.android.gms.auth.api.signin.*
import com.google.android.gms.common.SignInButton
import com.google.android.gms.common.api.ApiException
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.*
import java.util.concurrent.Executor

class LoginActivity : AppCompatActivity() {

    private lateinit var emailInputLayout: TextInputLayout
    private lateinit var passwordInputLayout: TextInputLayout
    private lateinit var emailInput: EditText
    private lateinit var passwordInput: EditText
    private lateinit var btnLogin: Button
    private lateinit var btnGoogle: SignInButton
    private lateinit var btnGuest: TextView
    private lateinit var tvCreateAccount: TextView
    private lateinit var progressBar: ProgressBar

    private lateinit var biometricPrompt: BiometricPrompt
    private lateinit var promptInfo: BiometricPrompt.PromptInfo

    private lateinit var securePrefs: SharedPreferences
    private lateinit var googleSignInClient: GoogleSignInClient
    private lateinit var userRepo: UserRepository

    private val RC_GOOGLE = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        initViews()
        initSecureStorage()
        initGoogleAuth()
        initDatabase()
        initBiometrics()

        emailInput.addTextChangedListener(watcher)
        passwordInput.addTextChangedListener(watcher)

        // -------------------------------
        // NORMAL LOGIN WITH SERVER CHECK
        // -------------------------------
        btnLogin.setOnClickListener {
            if (!validateInputs()) return@setOnClickListener

            checkServerBefore {
                loginNormal()
            }
        }

        // -------------------------------
        // GOOGLE LOGIN WITH SERVER CHECK
        // -------------------------------
        btnGoogle.setOnClickListener {
            checkServerBefore {
                startActivityForResult(
                    googleSignInClient.signInIntent,
                    RC_GOOGLE
                )
            }
        }

        // -------------------------------
        // GUEST LOGIN — NO SERVER CHECK
        // -------------------------------
        btnGuest.setOnClickListener {
            checkServerBefore {
                loginGuest()
            }
        }

        // Register navigation
        tvCreateAccount.setOnClickListener {
            startActivity(Intent(this, SignupActivity::class.java))
        }

        // -------------------------------
        // BIOMETRIC LOGIN CHECKS SERVER
        // -------------------------------
        val biometricCard = findViewById<View>(R.id.cardBiometric)

        biometricCard.visibility =
            if (securePrefs.getString("fingerprint_email_active", null) != null)
                View.VISIBLE else View.GONE

        biometricCard.setOnClickListener {
            checkServerBefore {
                biometricPrompt.authenticate(promptInfo)
            }
        }
    }

    // ======================================================================
    // SERVER CHECK — UNIVERSAL FUNCTION
    // ======================================================================
    private fun checkServerBefore(action: () -> Unit) {
        progressBar.visibility = View.VISIBLE

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                RemoteServerClient.ping()
            }

            progressBar.visibility = View.GONE

            if (result.isFailure) {
                Snackbar.make(
                    btnLogin,
                    "Server unavailable ❌ Please try again later",
                    Snackbar.LENGTH_LONG
                )
                    .setBackgroundTint(
                        ContextCompat.getColor(
                            this@LoginActivity,
                            android.R.color.holo_red_dark
                        )
                    )
                    .setTextColor(ContextCompat.getColor(this@LoginActivity, android.R.color.white))
                    .show()

                return@launch
            }

            // Server OK → continue
            action()
        }
    }


    // ======================================================================
    // INITIALIZATIONS
    // ======================================================================
    private fun initViews() {
        emailInputLayout = findViewById(R.id.inputLayoutEmail)
        passwordInputLayout = findViewById(R.id.inputLayoutPassword)
        emailInput = findViewById(R.id.etEmail)
        passwordInput = findViewById(R.id.etPassword)
        btnLogin = findViewById(R.id.btnLogin)
        btnGoogle = findViewById(R.id.btnGoogle)
        btnGuest = findViewById(R.id.btnGuest)
        tvCreateAccount = findViewById(R.id.tvCreateAccount)
        progressBar = findViewById(R.id.progressBar)
    }

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

    private fun initGoogleAuth() {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .build()

        googleSignInClient = GoogleSignIn.getClient(this, gso)
    }

    private fun initDatabase() {
        val db = TripDatabase.getDatabase(this)
        userRepo = UserRepository(db.userDao())
    }

    // ======================================================================
    // BIOMETRICS
    // ======================================================================
    private fun initBiometrics() {
        val manager = BiometricManager.from(this)

        val canUse = manager.canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL
        )

        if (canUse != BiometricManager.BIOMETRIC_SUCCESS)
            return

        val executor = ContextCompat.getMainExecutor(this)

        biometricPrompt = BiometricPrompt(
            this,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    resumeBiometricSession()
                }
            }
        )

        promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Biometric Login")
            .setSubtitle("Authenticate to continue")
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                        BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
            .build()
    }

    // ======================================================================
    // LOGIN FLOW
    // ======================================================================
    private fun loginNormal() {
        progressBar.visibility = View.VISIBLE
        btnLogin.isEnabled = false

        val email = emailInput.text.toString()
        val pass = passwordInput.text.toString()

        lifecycleScope.launch {
            val serverResponse = withContext(Dispatchers.IO) {
                RemoteServerClient.loginUser(email, pass)
            }

            if (serverResponse.isFailure) {
                error("Authentication failed")
                return@launch
            }

            val payload = serverResponse.getOrNull()!!

            securePrefs.edit().putString("auth_token", payload.token).apply()

            val user = withContext(Dispatchers.IO) {
                userRepo.getUserByEmail(email)
                    ?: userRepo.registerUser(email, pass, payload.role, email)
            }

            saveSession(user.email, user.role)

            if (!user.useBiometrics) askEnableBiometrics(user.email)

            goDashboard()
        }
    }

    private fun resumeBiometricSession() {
        val email = securePrefs.getString("fingerprint_email_active", null) ?: return

        lifecycleScope.launch {
            val user = withContext(Dispatchers.IO) { userRepo.getUserByEmail(email) }

            if (user != null && !user.isBlocked) {
                saveSession(user.email, user.role)
                goDashboard()
            } else {
                Snackbar.make(btnLogin, "Biometric login failed ❌", Snackbar.LENGTH_SHORT).show()
            }
        }
    }

    // ======================================================================
    // HELPERS
    // ======================================================================
    private fun saveSession(email: String, role: String) {
        securePrefs.edit().apply {
            putString("email", email)
            putString("role", role)
        }.apply()
    }

    private fun goDashboard() {
        startActivity(Intent(this, DashboardActivity::class.java))
        finish()
    }

    private fun loginGuest() {
        securePrefs.edit().apply {
            putString("email", "guest@local")
            putString("role", "guest")
        }.apply()

        startActivity(Intent(this, DashboardActivity::class.java))
        finish()
    }

    private fun error(msg: String) {
        progressBar.visibility = View.GONE
        btnLogin.isEnabled = true
        Snackbar.make(btnLogin, "$msg ❌", Snackbar.LENGTH_LONG).show()
    }

    // ======================================================================
    // VALIDATION
    // ======================================================================
    private val watcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun afterTextChanged(s: Editable?) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
            validateInputs(false)
        }
    }

    private fun askEnableBiometrics(email: String) {
        AlertDialog.Builder(this)
            .setTitle("Enable biometric login?")
            .setMessage("Use fingerprint for faster and more secure login.")
            .setCancelable(false)
            .setPositiveButton("Enable") { _, _ ->

                // Save preference in local database
                lifecycleScope.launch(Dispatchers.IO) {
                    userRepo.updateUseBiometrics(email, true)
                }

                // Save active biometric session for next time
                securePrefs.edit()
                    .putString("fingerprint_email_active", email)
                    .apply()

                Snackbar.make(btnLogin, "Biometrics enabled 🔐", Snackbar.LENGTH_SHORT).show()
            }
            .setNegativeButton("No thanks") { _, _ -> }
            .show()
    }


    private fun validateInputs(showError: Boolean = true): Boolean {
        val email = emailInput.text.toString().trim()
        val pass = passwordInput.text.toString().trim()

        var ok = true

        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            if (showError) emailInputLayout.error = "Invalid email"
            ok = false
        } else emailInputLayout.error = null

        if (pass.length < 6) {
            if (showError) passwordInputLayout.error = "Min 6 chars"
            ok = false
        } else passwordInputLayout.error = null

        return ok
    }
}
