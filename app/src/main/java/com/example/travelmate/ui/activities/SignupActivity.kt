package com.example.travelmate.ui.activities

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.travelmate.R
import com.example.travelmate.data.TripDatabase
import com.example.travelmate.network.TokenManager
import com.example.travelmate.repository.AuthRepository
import com.example.travelmate.repository.UserRepository
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.*

class SignupActivity : AppCompatActivity() {

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
    private lateinit var tokenManager: TokenManager

    private lateinit var userRepository: UserRepository
    private lateinit var authRepository: AuthRepository
    private val coroutineScope = CoroutineScope(Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_signup)

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

        val db = TripDatabase.getDatabase(this)
        userRepository = UserRepository(db.userDao())
        tokenManager = TokenManager.getInstance(this)
        authRepository = AuthRepository(userRepository, tokenManager)

        listOf(etName, etEmail, etPassword, etConfirmPassword).forEach {
            it.addTextChangedListener(validationWatcher)
        }

        tvLoginLink.setOnClickListener { finish() }

        btnSignup.setOnClickListener {
            if (validateInputs()) performSignup()
        }
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

        val name = etName.text.toString().trim()
        val email = etEmail.text.toString().trim()
        val password = etPassword.text.toString()
        val confirmPassword = etConfirmPassword.text.toString()

        if (name.length < 3) {
            if (showError) nameInputLayout.error = "Name must have at least 3 characters"
            valid = false
        } else nameInputLayout.error = null

        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            if (showError) emailInputLayout.error = "Invalid email format"
            valid = false
        } else emailInputLayout.error = null

        if (password.length < 6 || password.contains(" ")) {
            if (showError) passwordInputLayout.error =
                "Password must be 6+ chars and contain no spaces"
            valid = false
        } else passwordInputLayout.error = null

        if (confirmPassword != password) {
            if (showError) confirmPasswordInputLayout.error = "Passwords do not match"
            valid = false
        } else confirmPasswordInputLayout.error = null

        return valid
    }

    private fun performSignup() {
        btnSignup.isEnabled = false
        progressBar.visibility = View.VISIBLE

        val name = etName.text.toString().trim()
        val email = etEmail.text.toString().trim()
        val password = etPassword.text.toString().trim()

        coroutineScope.launch {
            val existingUser = withContext(Dispatchers.IO) { userRepository.getUserByEmail(email) }
            delay(300)

            if (existingUser != null) {
                progressBar.visibility = View.GONE
                btnSignup.isEnabled = true
                Snackbar.make(btnSignup, "Account already exists ❌", Snackbar.LENGTH_LONG)
                    .setBackgroundTint(
                        ContextCompat.getColor(this@SignupActivity, android.R.color.holo_red_dark)
                    )
                    .show()
                return@launch
            }

            val signupResult = withContext(Dispatchers.IO) {
                authRepository.register(name, email, password)
            }

            progressBar.visibility = View.GONE
            btnSignup.isEnabled = true

            signupResult.onSuccess {
                Snackbar.make(btnSignup, "Account created successfully ✅", Snackbar.LENGTH_SHORT)
                    .setBackgroundTint(
                        ContextCompat.getColor(this@SignupActivity, android.R.color.holo_green_dark)
                    )
                    .show()

                delay(800)
                startActivity(Intent(this@SignupActivity, LoginActivity::class.java))
                finish()
            }.onFailure {
                Snackbar.make(btnSignup, "Cloud signup failed. Check connection ⚠️", Snackbar.LENGTH_LONG)
                    .setBackgroundTint(
                        ContextCompat.getColor(this@SignupActivity, android.R.color.holo_red_dark)
                    )
                    .show()
            }
        }
    }
}
