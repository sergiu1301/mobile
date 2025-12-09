package com.example.travelmate.ui.activities

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.example.travelmate.R
import com.example.travelmate.data.TripDatabase
import com.example.travelmate.network.RemoteServerClient
import com.example.travelmate.repository.UserRepository
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.*

class SignupActivity : AppCompatActivity() {

    // UI
    private lateinit var nameInputLayout: TextInputLayout
    private lateinit var emailInputLayout: TextInputLayout
    private lateinit var passwordInputLayout: TextInputLayout
    private lateinit var confirmPasswordInputLayout: TextInputLayout
    private lateinit var etName: TextInputEditText
    private lateinit var etEmail: TextInputEditText
    private lateinit var etPassword: TextInputEditText
    private lateinit var etConfirmPassword: TextInputEditText
    private lateinit var btnSignup: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var tvLoginLink: TextView

    private lateinit var securePrefs: android.content.SharedPreferences
    private lateinit var userRepository: UserRepository

    private val scope = CoroutineScope(Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_signup)

        bindViews()
        initSecurePrefs()
        initRepo()

        listOf(etName, etEmail, etPassword, etConfirmPassword).forEach {
            it.addTextChangedListener(inputWatcher)
        }

        btnSignup.setOnClickListener {
            if (validateInputs()) performSignup()
        }

        tvLoginLink.setOnClickListener { finish() }
    }

    // -------------------------------------------------------------
    // SERVER CHECK
    // -------------------------------------------------------------
    private suspend fun requireServerOnline(): Boolean {
        val result = withContext(Dispatchers.IO) { RemoteServerClient.ping() }

        return if (result.isSuccess) {
            true
        } else {
            Snackbar.make(
                btnSignup,
                "Server unavailable ❌ Try again later.",
                Snackbar.LENGTH_LONG
            ).setBackgroundTint(
                ContextCompat.getColor(this, android.R.color.holo_red_dark)
            ).show()
            false
        }
    }

    // -------------------------------------------------------------
    // INIT
    // -------------------------------------------------------------
    private fun bindViews() {
        nameInputLayout = findViewById(R.id.inputLayoutName)
        emailInputLayout = findViewById(R.id.inputLayoutEmail)
        passwordInputLayout = findViewById(R.id.inputLayoutPassword)
        confirmPasswordInputLayout = findViewById(R.id.inputLayoutConfirmPassword)

        etName = findViewById(R.id.etName)
        etEmail = findViewById(R.id.etEmail)
        etPassword = findViewById(R.id.etPassword)
        etConfirmPassword = findViewById(R.id.etConfirmPassword)

        btnSignup = findViewById(R.id.btnSignup)
        progressBar = findViewById(R.id.progressBar)
        tvLoginLink = findViewById(R.id.tvLoginLink)
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

    private fun initRepo() {
        val db = TripDatabase.getDatabase(this)
        userRepository = UserRepository(db.userDao())
    }

    // -------------------------------------------------------------
    // VALIDATION WATCHER
    // -------------------------------------------------------------
    private val inputWatcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun afterTextChanged(s: Editable?) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
            validateInputs(showError = false)
        }
    }

    // -------------------------------------------------------------
    // VALIDATION
    // -------------------------------------------------------------
    private fun validateInputs(showError: Boolean = true): Boolean {
        var ok = true

        val name = etName.text.toString().trim()
        val email = etEmail.text.toString().trim()
        val pass = etPassword.text.toString()
        val confirm = etConfirmPassword.text.toString()

        if (name.length < 3) {
            if (showError) nameInputLayout.error = "Name must be at least 3 characters"
            ok = false
        } else nameInputLayout.error = null

        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            if (showError) emailInputLayout.error = "Invalid email address"
            ok = false
        } else emailInputLayout.error = null

        when {
            pass.length < 6 -> {
                if (showError) passwordInputLayout.error = "Password must be at least 6 characters"
                ok = false
            }
            pass.contains(" ") -> {
                if (showError) passwordInputLayout.error = "Password cannot contain spaces"
                ok = false
            }
            else -> passwordInputLayout.error = null
        }

        if (confirm != pass) {
            if (showError) confirmPasswordInputLayout.error = "Passwords do not match"
            ok = false
        } else confirmPasswordInputLayout.error = null

        return ok
    }

    // -------------------------------------------------------------
    // SIGNUP PROCESS (+ server check)
    // -------------------------------------------------------------
    private fun performSignup() {
        val name = etName.text.toString().trim()
        val email = etEmail.text.toString().trim()
        val password = etPassword.text.toString()

        btnSignup.isEnabled = false
        progressBar.visibility = View.VISIBLE

        scope.launch {

            // 🔥 Step 1: Verify server availability
            if (!requireServerOnline()) {
                fail("Server unavailable ❌ Please try again later")
                return@launch
            }

            // 🔥 Step 2: Local duplicate check
            val existsLocal = withContext(Dispatchers.IO) { userRepository.getUserByEmail(email) }

            if (existsLocal != null) {
                fail("This email already exists locally ❌")
                return@launch
            }

            // 🔥 Step 3: Cloud signup
            val remote = withContext(Dispatchers.IO) {
                RemoteServerClient.registerUser(name, email, password)
            }

            if (remote.isFailure) {
                fail("Cloud signup failed ⚠️")
                return@launch
            }

            val payload = remote.getOrNull()

            // 🔥 Step 4: Save local user
            withContext(Dispatchers.IO) {
                userRepository.registerUser(
                    email = email,
                    password = password,
                    role = payload?.role ?: "user",
                    name = name
                )
            }

            // 🔥 Step 5: Save session
            securePrefs.edit().apply {
                putString("email", email)
                putString("role", payload?.role ?: "user")
                putString("auth_token", payload?.token)
                apply()
            }

            success("Account created successfully 🎉")
            delay(600)

            startActivity(Intent(this@SignupActivity, LoginActivity::class.java))
            finish()
        }
    }

    // -------------------------------------------------------------
    // UI HELPERS
    // -------------------------------------------------------------
    private fun fail(msg: String) {
        progressBar.visibility = View.GONE
        btnSignup.isEnabled = true

        Snackbar.make(btnSignup, msg, Snackbar.LENGTH_LONG)
            .setBackgroundTint(ContextCompat.getColor(this, android.R.color.holo_red_dark))
            .show()
    }

    private fun success(msg: String) {
        progressBar.visibility = View.GONE
        btnSignup.isEnabled = true

        Snackbar.make(btnSignup, msg, Snackbar.LENGTH_LONG)
            .setBackgroundTint(ContextCompat.getColor(this, android.R.color.holo_green_dark))
            .show()
    }
}
