package com.example.travelmate.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface UserDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertUser(user: User)

    @Query("SELECT * FROM users WHERE email = :email AND password = :password LIMIT 1")
    suspend fun getUser(email: String, password: String): User?

    @Query("SELECT * FROM users WHERE email = :email LIMIT 1")
    suspend fun getUserByEmail(email: String): User?

    @Query("SELECT * FROM users")
    suspend fun getAllUsers(): List<User>

    @Query("UPDATE users SET isBlocked = :blocked WHERE id = :userId")
    suspend fun setUserBlocked(userId: Int, blocked: Boolean)

    @Query("UPDATE users SET isBlocked = :blocked WHERE email = :email")
    suspend fun updateUserBlockStatus(email: String, blocked: Boolean)

    @Query("UPDATE users SET useBiometrics = :enabled WHERE email = :email")
    suspend fun updateUseBiometrics(email: String, enabled: Boolean)
}