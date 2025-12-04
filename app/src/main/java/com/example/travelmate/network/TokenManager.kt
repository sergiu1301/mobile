package com.example.travelmate.network

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class TokenManager private constructor(context: Context) {

    private val securePrefs: SharedPreferences

    init {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        securePrefs = EncryptedSharedPreferences.create(
            context,
            "secure_user_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun saveAuth(token: String?, role: String?, email: String?) {
        securePrefs.edit().apply {
            putString(KEY_TOKEN, token)
            putString(KEY_ROLE, role)
            putString(KEY_EMAIL, email)
        }.apply()
    }

    fun clearAuth() {
        securePrefs.edit().apply {
            remove(KEY_TOKEN)
            remove(KEY_ROLE)
            remove(KEY_EMAIL)
        }.apply()
    }

    fun getToken(): String? = securePrefs.getString(KEY_TOKEN, null)

    fun getEmail(): String? = securePrefs.getString(KEY_EMAIL, null)

    fun getRole(): String? = securePrefs.getString(KEY_ROLE, null)

    fun isAuthenticated(): Boolean = !getToken().isNullOrBlank()

    fun preferences(): SharedPreferences = securePrefs

    companion object {
        private const val KEY_TOKEN = "auth_token"
        private const val KEY_EMAIL = "email"
        private const val KEY_ROLE = "role"

        @Volatile
        private var INSTANCE: TokenManager? = null

        fun getInstance(context: Context): TokenManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: TokenManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
