package com.example.travelmate.ui.activities

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.example.travelmate.R
import com.example.travelmate.data.TripDatabase
import com.example.travelmate.network.RemoteServerClient
import com.example.travelmate.network.ServerUserDTO
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
    private lateinit var networkMonitor: NetworkMonitor

    private lateinit var repo: UserRepository

    private var token: String? = null
    private var currentUserRole: String = ""
    private var currentUserEmail: String = ""

    private var isSyncRunning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_admin_users)

        tvNetworkStatus = findViewById(R.id.tvNetworkStatus)
        usersContainer = findViewById(R.id.usersContainer)
        progressBar = findViewById(R.id.progressBar)
        tvNoUsers = findViewById(R.id.tvNoUsers)

        val db = TripDatabase.getDatabase(this)
        repo = UserRepository(db.userDao())

        loadPrefs()
        setupNetworkMonitor()
        loadUsers()
    }

    private fun loadPrefs() {
        val prefs = EncryptedSharedPreferences.create(
            this,
            "secure_user_prefs",
            MasterKey.Builder(this).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )

        token = prefs.getString("auth_token", null)
        currentUserRole = prefs.getString("role", "user") ?: "user"
        currentUserEmail = prefs.getString("email", "") ?: ""
    }

    private fun setupNetworkMonitor() {
        networkMonitor = NetworkMonitor(this) { isOnline ->
            runOnUiThread {
                tvNetworkStatus.text = if (isOnline) "Online" else "Offline Mode"
                tvNetworkStatus.setTextColor(
                    Color.parseColor(if (isOnline) "#2E7D32" else "#E65100")
                )
            }

            if (isOnline) {
                lifecycleScope.launch { performSafeSync() }
            }
        }

        networkMonitor.start()
    }

    private suspend fun performSafeSync() {
        if (isSyncRunning) return
        isSyncRunning = true

        try {
            progressBar.isVisible = true
            syncPendingUsers()
            loadUsers()
        } finally {
            isSyncRunning = false
            progressBar.isVisible = false
        }
    }

    private suspend fun syncPendingUsers() {
        val tk = token ?: return
        val pending = repo.getPendingUsers()

        if (pending.isEmpty()) return

        withContext(Dispatchers.IO) {
            for (u in pending) {
                RemoteServerClient.updateUserRole(tk, u.email, u.role)
                RemoteServerClient.updateBlockStatus(tk, u.email, u.isBlocked)
                repo.clearPending(u.email)
            }
        }
    }

    private fun loadUsers() {
        val tk = token ?: return

        progressBar.isVisible = true
        usersContainer.removeAllViews()

        lifecycleScope.launch(Dispatchers.IO) {

            val online = networkMonitor.isCurrentlyOnline()
            val response = if (online) RemoteServerClient.getUsers(tk) else null

            val users = if (response != null && response.isSuccess) {
                val serverList = response.getOrNull() ?: emptyList()
                syncLocalWithServer(serverList)
                serverList
            } else {
                repo.getAllUsers().map { ServerUserDTO(it.email, it.role, it.isBlocked) }
            }

            withContext(Dispatchers.Main) {
                progressBar.isVisible = false
                renderUsers(users)
            }
        }
    }

    private suspend fun syncLocalWithServer(serverUsers: List<ServerUserDTO>) {
        withContext(Dispatchers.IO) {
            for (srv in serverUsers) {
                repo.updateUserRole(srv.email, srv.role)
                repo.updateUserBlockStatus(srv.email, srv.isBlocked)
                repo.clearPending(srv.email)
            }
        }
    }

    private fun renderUsers(allUsers: List<ServerUserDTO>) {

        val filtered = when (currentUserRole) {
            "superadmin" -> allUsers.filter { it.email != currentUserEmail }
            "admin" -> allUsers.filter { it.role == "user" }
            else -> emptyList()
        }

        usersContainer.removeAllViews()
        tvNoUsers.isVisible = filtered.isEmpty()

        val inflater = LayoutInflater.from(this)

        filtered.forEach { dto ->

            val card = inflater.inflate(R.layout.item_user_card, usersContainer, false)

            val tvEmail = card.findViewById<TextView>(R.id.tvUserEmail)
            val tvRole = card.findViewById<TextView>(R.id.tvUserRole)
            val switchRole = card.findViewById<SwitchCompat>(R.id.switchRole)
            val switchBlock = card.findViewById<SwitchCompat>(R.id.switchBlock)

            tvEmail.text = dto.email
            tvRole.text = "Role: ${dto.role}"

            switchRole.isChecked = dto.role == "admin"
            switchRole.isEnabled = currentUserRole == "superadmin"

            switchRole.setOnCheckedChangeListener { _, checked ->
                if (currentUserRole != "superadmin") {
                    switchRole.isChecked = dto.role == "admin"
                    Toast.makeText(this, "Only superadmin can change roles", Toast.LENGTH_SHORT).show()
                    return@setOnCheckedChangeListener
                }

                val newRole = if (checked) "admin" else "user"
                dto.role = newRole
                tvRole.text = "Role: $newRole"

                lifecycleScope.launch(Dispatchers.IO) {
                    if (networkMonitor.isCurrentlyOnline()) {
                        RemoteServerClient.updateUserRole(token!!, dto.email, newRole)
                        repo.updateUserRole(dto.email, newRole)
                        repo.clearPending(dto.email)
                    } else {
                        repo.updateUserRole(dto.email, newRole)
                        repo.markPending(dto.email)
                    }
                }
            }

            switchBlock.isChecked = dto.isBlocked

            switchBlock.setOnCheckedChangeListener { _, blocked ->
                dto.isBlocked = blocked

                lifecycleScope.launch(Dispatchers.IO) {
                    if (networkMonitor.isCurrentlyOnline()) {
                        RemoteServerClient.updateBlockStatus(token!!, dto.email, blocked)
                        repo.updateUserBlockStatus(dto.email, blocked)
                        repo.clearPending(dto.email)
                    } else {
                        repo.updateUserBlockStatus(dto.email, blocked)
                        repo.markPending(dto.email)
                    }
                }
            }

            usersContainer.addView(card)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        networkMonitor.stop()
    }
}
