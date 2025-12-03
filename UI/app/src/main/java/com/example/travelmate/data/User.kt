package com.example.travelmate.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "users")
data class User(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val email: String,
    val password: String,
    val role: String = "user",
    val salt: String,
    var isBlocked: Boolean = false,
    val useBiometrics: Boolean = true
)