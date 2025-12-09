package com.example.travelmate.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface UserDao {

    // ---------------------------------------------------------
    // INSERT OR UPDATE USER
    // ---------------------------------------------------------
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertUser(user: User)

    // ---------------------------------------------------------
    // LOOKUP
    // ---------------------------------------------------------
    @Query("SELECT * FROM users WHERE email = :email LIMIT 1")
    suspend fun getUserByEmail(email: String): User?

    @Query("SELECT * FROM users")
    suspend fun getAllUsers(): List<User>

    // ---------------------------------------------------------
    // BLOCK / UNBLOCK
    // ---------------------------------------------------------
    @Query("UPDATE users SET isBlocked = :blocked WHERE email = :email")
    suspend fun updateUserBlockStatus(email: String, blocked: Boolean)

    // ---------------------------------------------------------
    // ROLE UPDATE (SUPERADMIN ONLY)
    // ---------------------------------------------------------
    @Query("UPDATE users SET role = :role WHERE email = :email")
    suspend fun updateUserRole(email: String, role: String)

    // ---------------------------------------------------------
    // BIOMETRICS FLAG
    // ---------------------------------------------------------
    @Query("UPDATE users SET useBiometrics = :enabled WHERE email = :email")
    suspend fun updateUseBiometrics(email: String, enabled: Boolean)
}
