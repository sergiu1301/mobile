package com.example.travelmate.ui.activities

import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.WindowManager
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
        initSecurePrefs()
        initGoogleAuth()
        initDatabase()
        initBiometrics()

        emailInput.addTextChangedListener(watcher)
        passwordInput.addTextChangedListener(watcher)

        btnLogin.setOnClickListener {
            if (!validateInputs()) return@setOnClickListener
            checkServerBefore { loginNormal() }
        }

        btnGoogle.setOnClickListener {
            checkServerBefore {
                startActivityForResult(googleSignInClient.signInIntent, RC_GOOGLE)
            }
        }

        btnGuest.setOnClickListener { loginGuest() }

        tvCreateAccount.setOnClickListener {
            startActivity(Intent(this, SignupActivity::class.java))
        }

        val biometricCard = findViewById<View>(R.id.cardBiometric)

        val lastEmail = securePrefs.getString("last_biometric_user", null)
        val enabled = lastEmail != null &&
                securePrefs.getBoolean("fingerprint_enabled_$lastEmail", false)

        val isGuest = securePrefs.getBoolean("guest_active", false)

        // 🔥 Guest NU vede opțiunea biometric
        biometricCard.visibility = if (!isGuest && enabled) View.VISIBLE else View.GONE

        biometricCard.setOnClickListener {
            checkServerBefore { biometricPrompt.authenticate(promptInfo) }
        }
    }

    // ------------------ SERVER CHECK ------------------------

    private fun checkServerBefore(action: () -> Unit) {
        progressBar.visibility = View.VISIBLE

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { RemoteServerClient.ping() }

            progressBar.visibility = View.GONE

            if (result.isFailure) {
                Snackbar.make(btnLogin, "Server unavailable ❌", Snackbar.LENGTH_LONG).show()
                return@launch
            }
            action()
        }
    }

    // ------------------ INIT ------------------------

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

    private fun initSecurePrefs() {
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
        userRepo = UserRepository(TripDatabase.getDatabase(this).userDao())
    }

    // ------------------ BIOMETRICS ------------------------

    private fun initBiometrics() {
        val manager = BiometricManager.from(this)

        if (manager.canAuthenticate(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                        BiometricManager.Authenticators.DEVICE_CREDENTIAL
            ) != BiometricManager.BIOMETRIC_SUCCESS
        ) return

        biometricPrompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
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

    // ------------------ LOGIN FLOWS ------------------------

    private fun loginNormal() {
        progressBar.visibility = View.VISIBLE

        val email = emailInput.text.toString()
        val pass = passwordInput.text.toString()

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                RemoteServerClient.loginUser(email, pass)
            }

            if (result.isFailure) {
                error("Login failed")
                return@launch
            }

            val payload = result.getOrNull()!!

            saveToSecurePrefs(payload.token, payload.email, payload.role)
            securePrefs.edit().putString("token_${payload.email}", payload.token).apply()
            securePrefs.edit().putBoolean("guest_active", false).apply()

            val user = withContext(Dispatchers.IO) {
                userRepo.getUserByEmail(payload.email)
                    ?: userRepo.registerUser(
                        email = payload.email,
                        password = pass,
                        role = payload.role,
                        name = payload.email.substringBefore("@")
                    )
            }

            // Dacă nu are biometric activ
            if (!securePrefs.getBoolean("fingerprint_enabled_${user.email}", false)) {
                showBiometricChoiceDialog(user.email)
            } else {
                goDashboard()
            }
        }
    }

    private fun loginWithGoogle(email: String, name: String) {
        progressBar.visibility = View.VISIBLE

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                RemoteServerClient.googleLogin(email, name)
            }

            if (result.isFailure) {
                error("Google login failed")
                return@launch
            }

            val payload = result.getOrNull()!!

            saveToSecurePrefs(payload.token, payload.email, payload.role)
            securePrefs.edit().putString("token_${payload.email}", payload.token).apply()
            securePrefs.edit().putBoolean("guest_active", false).apply()

            val user = withContext(Dispatchers.IO) {
                userRepo.getUserByEmail(payload.email)
                    ?: userRepo.registerUser(email, "", payload.role, name)
            }

            if (!securePrefs.getBoolean("fingerprint_enabled_${user.email}", false)) {
                showBiometricChoiceDialog(user.email)
            } else {
                goDashboard()
            }
        }
    }

    private fun loginGuest() {
        securePrefs.edit()
            .putString("auth_token", "guest-token")
            .putString("email", "guest@local")
            .putString("role", "guest")
            .putBoolean("guest_active", true)
            .apply()

        goDashboard()
    }

    // ------------------ BIOMETRIC DIALOG ------------------------

    private fun showBiometricChoiceDialog(email: String) {
        val view = layoutInflater.inflate(R.layout.dialog_biometric_choice, null)

        val dialog = AlertDialog.Builder(this).create()

        dialog.setView(view)

        // 🔥 Background blur style
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.parseColor("#AA000000")))
        dialog.window?.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)

        val params = dialog.window?.attributes
        params?.gravity = Gravity.CENTER
        params?.dimAmount = 0.75f
        dialog.window?.attributes = params

        dialog.show()

        view.findViewById<Button>(R.id.btnEnable).setOnClickListener {
            securePrefs.edit()
                .putBoolean("fingerprint_enabled_$email", true)
                .putString("last_biometric_user", email)
                .putString("token_$email", securePrefs.getString("auth_token", ""))
                .apply()

            dialog.dismiss()
            goDashboard()
        }

        view.findViewById<Button>(R.id.btnNo).setOnClickListener {
            securePrefs.edit()
                .putBoolean("fingerprint_enabled_$email", false)
                .apply()

            dialog.dismiss()
            goDashboard()
        }
    }

    // ------------------ BIOMETRIC RESUME ------------------------

    private fun resumeBiometricSession() {
        val email = securePrefs.getString("last_biometric_user", null) ?: return
        val token = securePrefs.getString("token_$email", null)

        if (token.isNullOrEmpty()) {
            Snackbar.make(btnLogin, "Please login once before using biometrics ❌", Snackbar.LENGTH_LONG).show()
            return
        }

        lifecycleScope.launch {
            val user = withContext(Dispatchers.IO) { userRepo.getUserByEmail(email) }

            if (user != null) {
                saveToSecurePrefs(token, email, user.role)
                goDashboard()
            } else {
                Snackbar.make(btnLogin, "Biometric login failed ❌", Snackbar.LENGTH_SHORT).show()
            }
        }
    }

    // ------------------ HELPERS ------------------------

    private fun saveToSecurePrefs(token: String, email: String, role: String) {
        securePrefs.edit().apply {
            putString("auth_token", token)
            putString("email", email)
            putString("role", role)
        }.apply()
    }

    private fun goDashboard() {
        startActivity(Intent(this, DashboardActivity::class.java))
        finish()
    }

    private fun error(msg: String) {
        progressBar.visibility = View.GONE
        Snackbar.make(btnLogin, "$msg ❌", Snackbar.LENGTH_LONG).show()
    }

    // ------------------ GOOGLE CALLBACK ------------------------

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == RC_GOOGLE) {
            val task = GoogleSignIn.getSignedInAccountFromIntent(data)

            try {
                val account = task.getResult(ApiException::class.java)
                val email = account.email ?: return error("Google email missing")
                val name = account.displayName ?: "Google User"

                loginWithGoogle(email, name)

            } catch (e: ApiException) {
                error("Google login failed")
            }
        }
    }

    // ------------------ VALIDATION ------------------------

    private val watcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun afterTextChanged(s: Editable?) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
            validateInputs(false)
        }
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
