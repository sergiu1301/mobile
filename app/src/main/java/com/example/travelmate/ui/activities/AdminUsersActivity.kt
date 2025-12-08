package com.example.travelmate.ui.activities

import android.graphics.Color
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.example.travelmate.R
import com.example.travelmate.UserAdapter
import com.example.travelmate.data.TripDatabase
import com.example.travelmate.repository.UserRepository
import com.example.travelmate.utils.NetworkMonitor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AdminUsersActivity : AppCompatActivity() {

    private lateinit var recyclerUsers: RecyclerView
    private lateinit var repo: UserRepository
    private lateinit var tvAdminStatus: TextView
    private lateinit var networkMonitor: NetworkMonitor

    private var lastNetworkState: Boolean? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_admin_users)

        recyclerUsers = findViewById(R.id.recyclerUsers)
        recyclerUsers.layoutManager = LinearLayoutManager(this)
        tvAdminStatus = findViewById(R.id.tvAdminStatus)

        val db = TripDatabase.getDatabase(this)
        repo = UserRepository(db.userDao())

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

        val loggedEmail = securePrefs.getString("email", null)
        val loggedRole = securePrefs.getString("role", "user")

        if (loggedRole != "admin") {
            Toast.makeText(this, "Access denied ❌ (Admin only)", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        tvAdminStatus.text = "👤 Logged as admin: $loggedEmail"

        loadUsers()

        networkMonitor = NetworkMonitor(this) { isOnline ->
            runOnUiThread {
                updateNetworkStatus(isOnline)
                if (lastNetworkState == false && isOnline) {
                    Toast.makeText(this, "Back online 🌐", Toast.LENGTH_SHORT).show()
                    loadUsers()
                }
                lastNetworkState = isOnline
            }
        }

        val initialOnline = networkMonitor.isCurrentlyOnline()
        updateNetworkStatus(initialOnline)
        lastNetworkState = initialOnline
        networkMonitor.start()
    }

    private fun updateNetworkStatus(isOnline: Boolean) {
        if (isOnline) {
            tvAdminStatus.setTextColor(Color.parseColor("#2E7D32")) // verde
        } else {
            tvAdminStatus.setTextColor(Color.parseColor("#E65100")) // portocaliu
            Toast.makeText(this, "⚠️ Offline Mode – changes might not sync", Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadUsers() {
        lifecycleScope.launch(Dispatchers.IO) {
            val users = repo.getAllUsers()
            withContext(Dispatchers.Main) {
                recyclerUsers.adapter = UserAdapter(users, repo)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        loadUsers()
    }

    override fun onDestroy() {
        super.onDestroy()
        networkMonitor.stop()
    }
}
