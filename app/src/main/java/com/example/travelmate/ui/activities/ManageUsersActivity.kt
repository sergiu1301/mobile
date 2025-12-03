package com.example.travelmate.ui.activities

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.example.travelmate.R
import com.example.travelmate.data.TripDatabase
import com.example.travelmate.data.User
import com.example.travelmate.repository.UserRepository
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ManageUsersActivity : AppCompatActivity() {

    private lateinit var userRepository: UserRepository
    private lateinit var usersContainer: LinearLayout
    private lateinit var progressBar: ProgressBar
    private lateinit var tvNoUsers: TextView
    private lateinit var btnLogout: Button
    private lateinit var tvAdminTitle: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_manage_users)

        title = "Manage Users"

        usersContainer = findViewById(R.id.usersContainer)
        progressBar = findViewById(R.id.progressBar)
        tvNoUsers = findViewById(R.id.tvNoUsers)
        btnLogout = findViewById(R.id.btnLogout)
        tvAdminTitle = findViewById(R.id.tvAdminTitle)

        val db = TripDatabase.getDatabase(this)
        userRepository = UserRepository(db.userDao())

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

        val email = securePrefs.getString("email", null)
        val role = securePrefs.getString("role", null)

        if (role != "admin") {
            Snackbar.make(
                btnLogout,
                "Access denied ❌ (Admin only)",
                Snackbar.LENGTH_LONG
            ).setBackgroundTint(ContextCompat.getColor(this, android.R.color.holo_red_dark)).show()
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        tvAdminTitle.text = "👤 Logged as admin: $email"

        loadUsers()

        btnLogout.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    private fun loadUsers() {
        progressBar.visibility = View.VISIBLE
        usersContainer.removeAllViews()

        lifecycleScope.launch {
            val users = withContext(Dispatchers.IO) {
                userRepository.getAllUsers()
            }

            progressBar.visibility = View.GONE

            if (users.isEmpty()) {
                tvNoUsers.visibility = View.VISIBLE
                return@launch
            }

            tvNoUsers.visibility = View.GONE

            for (user in users) {
                if (user.role == "admin") continue

                val userView = layoutInflater.inflate(R.layout.item_user_row, usersContainer, false)
                val tvEmail = userView.findViewById<TextView>(R.id.tvUserEmail)
                val tvRole = userView.findViewById<TextView>(R.id.tvUserRole)
                val btnBlock = userView.findViewById<Button>(R.id.btnBlock)

                tvEmail.text = user.email
                tvRole.text = "Role: ${user.role}"

                updateButtonState(btnBlock, user.isBlocked)

                btnBlock.setOnClickListener {
                    toggleUserBlock(user, btnBlock)
                }

                usersContainer.addView(userView)
            }
        }
    }

    private fun updateButtonState(button: Button, isBlocked: Boolean) {
        if (isBlocked) {
            button.text = "Unblock"
            button.setBackgroundResource(R.drawable.btn_green_bg)
        } else {
            button.text = "Block"
            button.setBackgroundResource(R.drawable.btn_red_bg)
        }
    }

    private fun toggleUserBlock(user: User, button: Button) {
        val newState = !user.isBlocked

        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                userRepository.blockUser(user.id, newState)
            }
            user.isBlocked = newState
            updateButtonState(button, newState)
            val msg = if (newState) "User blocked 🚫" else "User unblocked ✅"
            Snackbar.make(button, msg, Snackbar.LENGTH_SHORT)
                .setBackgroundTint(
                    ContextCompat.getColor(
                        this@ManageUsersActivity,
                        if (newState) android.R.color.holo_red_dark else android.R.color.holo_green_dark
                    )
                )
                .show()
        }
    }
}
