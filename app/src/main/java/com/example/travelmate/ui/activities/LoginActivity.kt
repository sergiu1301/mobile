package com.example.travelmate.ui.activities

import android.app.Dialog
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
import com.example.travelmate.R
import com.example.travelmate.data.TripDatabase
import com.example.travelmate.data.User
import com.example.travelmate.network.ApiService
import com.example.travelmate.network.TokenManager
import com.example.travelmate.repository.UserRepository
import com.example.travelmate.repository.AuthRepository
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.tasks.Task
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.*
import java.util.concurrent.Executor

class LoginActivity : AppCompatActivity() {

    private lateinit var emailInputLayout: TextInputLayout
    private lateinit var passwordInputLayout: TextInputLayout
    private lateinit var emailInput: TextInputEditText
    private lateinit var passwordInput: TextInputEditText
    private lateinit var btnLogin: Button
    private lateinit var btnBiometric: ImageButton
    private lateinit var tvBiometricHint: TextView
    private lateinit var btnGoogle: com.google.android.gms.common.SignInButton
    private lateinit var progressBar: ProgressBar
    private lateinit var tvCreateAccount: TextView

    private lateinit var userRepository: UserRepository
    private lateinit var authRepository: AuthRepository
    private lateinit var googleSignInClient: GoogleSignInClient
    private lateinit var biometricPrompt: BiometricPrompt
    private lateinit var promptInfo: BiometricPrompt.PromptInfo
    private lateinit var tokenManager: TokenManager
    private lateinit var securePrefs: SharedPreferences

    private lateinit var btnGuest: TextView

    private val coroutineScope = CoroutineScope(Dispatchers.Main)
    private val RC_SIGN_IN = 1001
    private var biometricAvailable = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        emailInputLayout = findViewById(R.id.inputLayoutEmail)
        passwordInputLayout = findViewById(R.id.inputLayoutPassword)
        emailInput = findViewById(R.id.etEmail)
        passwordInput = findViewById(R.id.etPassword)
        btnLogin = findViewById(R.id.btnLogin)
        btnBiometric = findViewById(R.id.btnBiometric)
        btnGoogle = findViewById(R.id.btnGoogle)
        progressBar = findViewById(R.id.progressBar)
        tvCreateAccount = findViewById(R.id.tvCreateAccount)
        tvBiometricHint = findViewById(R.id.tvBiometricHint)
        btnGuest = findViewById(R.id.btnGuest)

        btnGuest.setOnClickListener {
            continueAsGuest()
        }

        tokenManager = TokenManager.getInstance(this)
        securePrefs = tokenManager.preferences()
        ApiService.attachTokenProvider { tokenManager.getToken() }

        val db = TripDatabase.getDatabase(this)
        userRepository = UserRepository(db.userDao())
        authRepository = AuthRepository(userRepository, tokenManager)

        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .build()
        googleSignInClient = GoogleSignIn.getClient(this, gso)

        val biometricManager = BiometricManager.from(this)
        val canAuth = biometricManager.canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL
        )
        biometricAvailable = (canAuth == BiometricManager.BIOMETRIC_SUCCESS)

        if (biometricAvailable) setupFingerprintPrompt()
        else {
            btnBiometric.visibility = View.GONE
            tvBiometricHint.visibility = View.GONE
        }

        emailInput.addTextChangedListener(validationWatcher)
        passwordInput.addTextChangedListener(validationWatcher)

        tvCreateAccount.setOnClickListener {
            startActivity(Intent(this, SignupActivity::class.java))
        }

        btnLogin.setOnClickListener {
            if (validateInputs()) performLogin()
        }

        btnGoogle.setOnClickListener {
            startActivityForResult(googleSignInClient.signInIntent, RC_SIGN_IN)
        }

        btnBiometric.setOnClickListener {
            val activeEmail = securePrefs.getString("fingerprint_email_active", null)
            if (activeEmail != null && securePrefs.getBoolean("biometric_enabled", false)) {
                securePrefs.edit().putString("email", activeEmail).apply()
                biometricPrompt.authenticate(promptInfo)
            } else {
                Snackbar.make(btnLogin, "Fingerprint not enabled yet ⚠️", Snackbar.LENGTH_SHORT).show()
            }
        }

        val fingerprintEmail = securePrefs.getString("fingerprint_email_active", null)
        val biometricEnabled = securePrefs.getBoolean("biometric_enabled", false)

        if (biometricAvailable && biometricEnabled && !fingerprintEmail.isNullOrEmpty()) {
            lifecycleScope.launch {
                val user = withContext(Dispatchers.IO) {
                    userRepository.getUserByEmail(fingerprintEmail)
                }

                if (user != null && user.useBiometrics && !user.isBlocked) {
                    securePrefs.edit().putString("email", user.email).apply()
                    biometricPrompt.authenticate(promptInfo)
                } else {
                    securePrefs.edit().apply {
                        remove("biometric_enabled")
                        remove("fingerprint_email_active")
                        apply()
                    }
                }
            }
        }
    }

    private fun continueAsGuest() {
        tokenManager.clearAuth()
        tokenManager.saveAuth(null, "guest", "guest@local")
        securePrefs.edit().apply {
            putString("role", "guest")
            putString("email", "guest@local")
            apply()
        }

        Snackbar.make(btnLogin, "Continuing as guest 👤", Snackbar.LENGTH_SHORT)
            .setBackgroundTint(ContextCompat.getColor(this, android.R.color.holo_green_dark))
            .show()

        startActivity(Intent(this, DashboardActivity::class.java))
        finish()
    }

    private fun validateFingerprintUser(onSuccess: () -> Unit) {
        coroutineScope.launch {
            val email = securePrefs.getString("email", null)
            if (email.isNullOrEmpty()) {
                Snackbar.make(btnLogin, "No saved user found. Please log in again.", Snackbar.LENGTH_SHORT).show()
                return@launch
            }

            val user = withContext(Dispatchers.IO) { userRepository.getUserByEmail(email) }
            if (user == null) {
                Snackbar.make(btnLogin, "Account not found. Please log in again.", Snackbar.LENGTH_SHORT).show()
                return@launch
            }

            if (user.isBlocked) {
                Snackbar.make(btnLogin, "Your account is blocked ❌", Snackbar.LENGTH_LONG)
                    .setBackgroundTint(ContextCompat.getColor(this@LoginActivity, android.R.color.holo_red_dark))
                    .show()
                return@launch
            }

            securePrefs.edit().apply {
                putString("email", user.email)
                putString("role", user.role)
                apply()
            }

            onSuccess()
        }
    }

    private fun setupFingerprintPrompt() {
        val executor: Executor = ContextCompat.getMainExecutor(this)
        biometricPrompt = BiometricPrompt(this, executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    validateFingerprintUser {
                        Snackbar.make(btnLogin, "Fingerprint login successful 👆", Snackbar.LENGTH_SHORT)
                            .setBackgroundTint(ContextCompat.getColor(this@LoginActivity, android.R.color.holo_green_dark))
                            .show()
                        goToDashboard()
                    }
                }

                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                    Snackbar.make(btnLogin, "Authentication failed ❌", Snackbar.LENGTH_SHORT)
                        .setBackgroundTint(ContextCompat.getColor(this@LoginActivity, android.R.color.holo_red_dark))
                        .show()
                }
            })

        promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Biometric Login")
            .setSubtitle("Use your fingerprint or device PIN to sign in")
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                        BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
            .build()
    }

    private val validationWatcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun afterTextChanged(s: Editable?) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
            validateInputs(showError = false)
        }
    }

    private fun validateInputs(showError: Boolean = true): Boolean {
        var valid = true
        val email = emailInput.text.toString().trim()
        val password = passwordInput.text.toString().trim()

        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            if (showError) emailInputLayout.error = "Invalid email format"
            valid = false
        } else emailInputLayout.error = null

        if (password.length < 6) {
            if (showError) passwordInputLayout.error = "Password must be at least 6 characters"
            valid = false
        } else passwordInputLayout.error = null

        return valid
    }

    private fun performLogin() {
        btnLogin.isEnabled = false
        progressBar.visibility = View.VISIBLE

        coroutineScope.launch {
            val email = emailInput.text.toString().trim()
            val password = passwordInput.text.toString().trim()

            val loginResult = withContext(Dispatchers.IO) {
                authRepository.login(email, password, enableBiometrics = biometricAvailable)
            }

            progressBar.visibility = View.GONE
            btnLogin.isEnabled = true

            loginResult.onSuccess { resolvedUser ->
                if (resolvedUser.isBlocked) {
                    Snackbar.make(btnLogin, "Your account is blocked ❌", Snackbar.LENGTH_LONG)
                        .setBackgroundTint(ContextCompat.getColor(this@LoginActivity, android.R.color.holo_red_dark))
                        .show()
                    return@launch
                }

                saveUserSession(resolvedUser)
                showMfaDialog(resolvedUser)
            }.onFailure {
                Snackbar.make(btnLogin, "Login failed. Check credentials or network.", Snackbar.LENGTH_LONG)
                    .setBackgroundTint(ContextCompat.getColor(this@LoginActivity, android.R.color.holo_red_dark))
                    .show()
            }
        }
    }

    private fun saveUserSession(user: User) {
        tokenManager.saveAuth(tokenManager.getToken(), user.role, user.email)
        securePrefs.edit().apply {
            putString("email", user.email)
            putString("role", user.role)
            apply()
        }
    }

    private fun showMfaDialog(user: User) {
        val dialog = Dialog(this)
        dialog.setContentView(R.layout.dialog_mfa)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val etCode = dialog.findViewById<EditText>(R.id.etMfaCode)
        val btnVerify = dialog.findViewById<Button>(R.id.btnVerify)

        btnVerify.setOnClickListener {
            val code = etCode.text.toString().trim()
            if (code == "123456") {
                dialog.dismiss()

                lifecycleScope.launch {
                    val freshUser = withContext(Dispatchers.IO) {
                        userRepository.getUserByEmail(user.email)
                    }

                    if (freshUser?.useBiometrics == true && biometricAvailable) {
                        val fingerprintEmail = securePrefs.getString("fingerprint_email_active", null)
                        val biometricEnabled = securePrefs.getBoolean("biometric_enabled", false)

                        if (biometricEnabled && fingerprintEmail == user.email) {
                            goToDashboard()
                        } else {
                            showEnableFingerprintPrompt(user)
                        }
                    } else {
                        goToDashboard()
                    }
                }
            } else {
                Snackbar.make(btnLogin, "Invalid MFA code ❌", Snackbar.LENGTH_SHORT)
                    .setBackgroundTint(ContextCompat.getColor(this, android.R.color.holo_red_dark))
                    .show()
            }
        }

        dialog.show()
    }

    private fun showEnableFingerprintPrompt(user: User) {
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.sheet_enable_biometric, null)
        dialog.setContentView(view)

        val btnEnable = view.findViewById<Button>(R.id.btnEnable)
        val btnNotNow = view.findViewById<TextView>(R.id.btnNotNow)

        btnEnable.setOnClickListener {
            securePrefs.edit().apply {
                putBoolean("biometric_enabled", true)
                putString("fingerprint_email_active", user.email)
                apply()
            }
            dialog.dismiss()
            Snackbar.make(btnLogin, "Fingerprint login enabled 👆", Snackbar.LENGTH_SHORT)
                .setBackgroundTint(ContextCompat.getColor(this, android.R.color.holo_green_dark))
                .show()
            biometricPrompt.authenticate(promptInfo)
        }

        btnNotNow.setOnClickListener {
            dialog.dismiss()
            Snackbar.make(btnLogin, "Maybe later 😌", Snackbar.LENGTH_SHORT)
                .setBackgroundTint(ContextCompat.getColor(this, android.R.color.darker_gray))
                .show()
            goToDashboard()
        }

        dialog.show()
    }

    private fun goToDashboard() {
        val role = securePrefs.getString("role", "user")
        val intent = if (role == "admin") {
            Intent(this, ManageUsersActivity::class.java)
        } else {
            Intent(this, DashboardActivity::class.java)
        }
        startActivity(intent)
        finish()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == RC_SIGN_IN) {
            val task = GoogleSignIn.getSignedInAccountFromIntent(data)
            handleSignInResult(task)
        }
    }

    private fun handleSignInResult(task: Task<com.google.android.gms.auth.api.signin.GoogleSignInAccount>) {
        try {
            val account = task.getResult(ApiException::class.java)
            val email = account?.email ?: return
            val name = account.displayName ?: "Traveler"

            tokenManager.saveAuth(tokenManager.getToken(), "user", email)
            securePrefs.edit().apply {
                putString("email", email)
                putString("role", "user")
                apply()
            }

            coroutineScope.launch(Dispatchers.IO) {
                val existing = userRepository.getUserByEmail(email)
                if (existing == null) {
                    userRepository.registerUser(email, "google_auth", role = "user", email)
                }
            }

            Snackbar.make(btnLogin, "Welcome $name 🎉", Snackbar.LENGTH_SHORT)
                .setBackgroundTint(ContextCompat.getColor(this, android.R.color.holo_green_dark))
                .show()
            goToDashboard()

        } catch (e: ApiException) {
            Snackbar.make(btnLogin, "Google Sign-In failed: ${e.statusCode}", Snackbar.LENGTH_SHORT)
                .setBackgroundTint(ContextCompat.getColor(this, android.R.color.holo_red_dark))
                .show()
        }
    }
}
