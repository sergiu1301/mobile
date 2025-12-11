package com.example.travelmate.repository

import android.util.Base64
import com.example.travelmate.data.User
import com.example.travelmate.data.UserDao
import java.security.MessageDigest
import java.util.UUID

class UserRepository(private val dao: UserDao) {

    // ---------------------------------------------------------
    // CREATE USER (Normal / Google)
    // Guest NU se salvează în DB
    // ---------------------------------------------------------
    suspend fun registerUser(
        email: String,
        password: String,
        role: String = "user",
        name: String
    ): User {

        val (hashedPassword, salt) =
            if (password == "google_auth") {
                // Google login does not hash passwords
                Pair("google_auth", "NO_SALT")
            } else {
                hashPassword(password)
            }

        val user = User(
            email = email,
            password = hashedPassword,
            salt = salt,
            role = role,
            name = name,
            useBiometrics = false,
            isBlocked = false
        )

        dao.insertUser(user)
        return user
    }

    // ---------------------------------------------------------
    // LOGIN LOCAL (for DB users)
    // ---------------------------------------------------------
    suspend fun loginUser(email: String, password: String): User? {
        val user = dao.getUserByEmail(email) ?: return null

        if (user.isBlocked) return null

        // Google user → always accepted if password is google_auth
        if (user.password == "google_auth") return user

        return if (verifyPassword(password, user.password, user.salt)) user else null
    }

    // ---------------------------------------------------------
    // LOOKUP
    // ---------------------------------------------------------
    suspend fun getUserByEmail(email: String): User? =
        dao.getUserByEmail(email)

    suspend fun getAllUsers(): List<User> =
        dao.getAllUsers()

    // ---------------------------------------------------------
    // ROLE UPDATE (SUPERADMIN ONLY from UI)
    // ---------------------------------------------------------
    suspend fun updateUserRole(email: String, newRole: String) {
        dao.updateUserRole(email, newRole)
    }

    // ---------------------------------------------------------
    // BLOCK / UNBLOCK
    // ---------------------------------------------------------
    suspend fun updateUserBlockStatus(email: String, blocked: Boolean) {
        dao.updateUserBlockStatus(email, blocked)
    }

    // ---------------------------------------------------------
    // BIOMETRIC FLAG
    // ---------------------------------------------------------
    suspend fun updateUseBiometrics(email: String, enabled: Boolean) {
        dao.updateUseBiometrics(email, enabled)
    }

    // ---------------------------------------------------------
    // PASSWORD CRYPTO
    // ---------------------------------------------------------
    private fun hashPassword(
        password: String,
        salt: String = UUID.randomUUID().toString()
    ): Pair<String, String> {

        val md = MessageDigest.getInstance("SHA-256")
        val hashBytes = md.digest((salt + password).toByteArray())

        val hash = Base64.encodeToString(hashBytes, Base64.NO_WRAP)
        return Pair(hash, salt)
    }

    private fun verifyPassword(input: String, storedHash: String, salt: String): Boolean {
        val md = MessageDigest.getInstance("SHA-256")
        val inputHashBytes = md.digest((salt + input).toByteArray())
        val computed = Base64.encodeToString(inputHashBytes, Base64.NO_WRAP)
        return computed == storedHash
    }

    suspend fun markPending(email: String) {
        val user = dao.getUserByEmail(email) ?: return
        user.pendingSync = true
        dao.insertUser(user) // REPLACE update
    }

    suspend fun clearPending(email: String) {
        val user = dao.getUserByEmail(email) ?: return
        user.pendingSync = false
        dao.insertUser(user)
    }

    suspend fun getPendingUsers(): List<User> {
        return dao.getAllUsers().filter { it.pendingSync }
    }

}
