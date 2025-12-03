package com.example.travelmate.repository

import android.util.Base64
import com.example.travelmate.data.User
import com.example.travelmate.data.UserDao
import java.security.MessageDigest
import java.util.UUID

class UserRepository(private val dao: UserDao) {

    suspend fun registerUser(email: String, password: String, role: String = "user", name: String) {
        val (hashedPassword, salt) = hashPassword(password)
        val user = User(email = email, password = hashedPassword, role = role, salt = salt, name = name, useBiometrics = true)
        dao.insertUser(user)
    }

    suspend fun loginUser(email: String, password: String): User? {
        val user = dao.getUserByEmail(email)
        return if (user != null && verifyPassword(password, user.password, user.salt)) {
            user
        }
        else null
    }

    suspend fun getUserByEmail(email: String): User? {
        return dao.getUserByEmail(email)
    }

    suspend fun getAllUsers() = dao.getAllUsers()

    suspend fun blockUser(userId: Int, blocked: Boolean) = dao.setUserBlocked(userId, blocked)

    suspend fun updateUserBlockStatus(email: String, blocked: Boolean) {
        dao.updateUserBlockStatus(email, blocked)
    }

    suspend fun updateUseBiometrics(email: String, enabled: Boolean) {
        dao.updateUseBiometrics(email, enabled)
    }

    private fun hashPassword(password: String, salt: String = UUID.randomUUID().toString()): Pair<String, String> {
        val md = MessageDigest.getInstance("SHA-256")
        val hash = md.digest((salt + password).toByteArray())
        return Pair(Base64.encodeToString(hash, Base64.NO_WRAP), salt)
    }

    private fun verifyPassword(input: String, storedHash: String, salt: String): Boolean {
        val md = MessageDigest.getInstance("SHA-256")
        val hash = md.digest((salt + input).toByteArray())
        return Base64.encodeToString(hash, Base64.NO_WRAP) == storedHash
    }
}