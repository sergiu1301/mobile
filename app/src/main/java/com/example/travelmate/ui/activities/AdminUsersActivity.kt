package com.example.travelmate.ui.activities

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.example.travelmate.R
import com.example.travelmate.data.TripDatabase
import com.example.travelmate.network.RemoteServerClient
import com.example.travelmate.repository.UserRepository
import com.example.travelmate.utils.NetworkMonitor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AdminUsersActivity : AppCompatActivity() {

    private lateinit var tvNetworkStatus: TextView
    private lateinit var usersContainer: LinearLayout
    private lateinit var progressBar: ProgressBar
    private lateinit var tvNoUsers: TextView

    private lateinit var repo: UserRepository
    private lateinit var networkMonitor: NetworkMonitor

    private var lastNetworkState: Boolean? = null
    private var currentUserRole: String = ""
    private var currentUserEmail: String = ""

    private var serverOnline: Boolean = true   // 🔥 nou

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_admin_users)

        tvNetworkStatus = findViewById(R.id.tvNetworkStatus)
        usersContainer = findViewById(R.id.usersContainer)
        progressBar = findViewById(R.id.progressBar)
        tvNoUsers = findViewById(R.id.tvNoUsers)

        val db = TripDatabase.getDatabase(this)
        repo = UserRepository(db.userDao())

        val prefs = loadSecurePrefs()
        currentUserRole = prefs.getString("role", "user") ?: "user"
        currentUserEmail = prefs.getString("email", "") ?: ""

        if (currentUserRole !in listOf("admin", "superadmin")) {
            Toast.makeText(this, "Access denied ❌", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        setupNetworkMonitor()

        // 🔥 verificare server înainte de orice
        lifecycleScope.launch {
            if (!checkServer()) {
                Toast.makeText(
                    this@AdminUsersActivity,
                    "Server unavailable. Try again later ❌",
                    Toast.LENGTH_LONG
                ).show()
                progressBar.isVisible = false
                return@launch
            }
            loadUsers()
        }
    }

    private fun loadSecurePrefs() =
        EncryptedSharedPreferences.create(
            this,
            "secure_user_prefs",
            MasterKey.Builder(this).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )

    private fun setupNetworkMonitor() {
        networkMonitor = NetworkMonitor(this) { isOnline ->
            runOnUiThread {
                updateNetworkStatus(isOnline)
                lastNetworkState = isOnline
            }
        }

        lastNetworkState = networkMonitor.isCurrentlyOnline()
        updateNetworkStatus(lastNetworkState == true)
        networkMonitor.start()
    }

    private fun updateNetworkStatus(isOnline: Boolean) {
        tvNetworkStatus.text =
            if (isOnline && serverOnline) "Online"
            else "Offline (server unreachable)"

        tvNetworkStatus.setTextColor(
            Color.parseColor(
                if (isOnline && serverOnline)
                    "#2E7D32"
                else
                    "#E65100"
            )
        )
    }

    // ============================================================
    // CHECK SERVER
    // ============================================================
    private suspend fun checkServer(): Boolean {
        val result = withContext(Dispatchers.IO) {
            RemoteServerClient.ping()
        }

        serverOnline = result.isSuccess
        updateNetworkStatus(lastNetworkState == true)

        return serverOnline
    }

    // ============================================================
    // LOAD USERS — se execută doar dacă serverul merge
    // ============================================================
    private fun loadUsers() {
        if (!serverOnline) {
            Toast.makeText(this, "Server is offline, cannot load users ❌", Toast.LENGTH_LONG).show()
            progressBar.isVisible = false
            return
        }

        progressBar.isVisible = true
        usersContainer.removeAllViews()
        tvNoUsers.isVisible = false

        lifecycleScope.launch(Dispatchers.IO) {
            val users = repo.getAllUsers()

            withContext(Dispatchers.Main) {
                progressBar.isVisible = false

                if (users.isEmpty()) {
                    tvNoUsers.isVisible = true
                    return@withContext
                }

                val inflater = LayoutInflater.from(this@AdminUsersActivity)

                users.forEach { user ->
                    val card = inflater.inflate(R.layout.item_user_card, usersContainer, false)

                    val tvEmail = card.findViewById<TextView>(R.id.tvUserEmail)
                    val tvRole = card.findViewById<TextView>(R.id.tvUserRole)
                    val switchRole = card.findViewById<Switch>(R.id.switchRole)
                    val switchBlock = card.findViewById<Switch>(R.id.switchBlock)

                    tvEmail.text = user.email
                    tvRole.text = "Role: ${user.role}"

                    // --------------------
                    // ROLE SWITCH
                    // --------------------
                    switchRole.isChecked = user.role == "admin"
                    switchRole.isEnabled = currentUserRole == "superadmin"

                    switchRole.setOnCheckedChangeListener { _, isChecked ->

                        if (!serverOnline) {
                            switchRole.isChecked = user.role == "admin"
                            Toast.makeText(this@AdminUsersActivity, "Server offline ❌", Toast.LENGTH_SHORT).show()
                            return@setOnCheckedChangeListener
                        }

                        if (currentUserRole != "superadmin") {
                            switchRole.isChecked = user.role == "admin"
                            Toast.makeText(this@AdminUsersActivity, "Only superadmin can change roles", Toast.LENGTH_SHORT).show()
                            return@setOnCheckedChangeListener
                        }

                        val newRole = if (isChecked) "admin" else "user"
                        user.role = newRole
                        tvRole.text = "Role: $newRole"

                        lifecycleScope.launch(Dispatchers.IO) {
                            repo.updateUserRole(user.email, newRole)
                        }
                    }

                    // --------------------
                    // BLOCK SWITCH
                    // --------------------
                    switchBlock.isChecked = user.isBlocked

                    switchBlock.setOnCheckedChangeListener { _, isBlocked ->
                        if (!serverOnline) {
                            switchBlock.isChecked = user.isBlocked
                            Toast.makeText(this@AdminUsersActivity, "Server offline ❌", Toast.LENGTH_SHORT).show()
                            return@setOnCheckedChangeListener
                        }

                        user.isBlocked = isBlocked
                        lifecycleScope.launch(Dispatchers.IO) {
                            repo.updateUserBlockStatus(user.email, isBlocked)
                        }
                    }

                    usersContainer.addView(card)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            if (checkServer())
                loadUsers()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        networkMonitor.stop()
    }
}
