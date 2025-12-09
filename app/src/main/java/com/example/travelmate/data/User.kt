package com.example.travelmate.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "users")
data class User(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,

    val name: String,
    val email: String,

    val password: String,
    val salt: String,

    var role: String = "user",  // user / admin / superadmin

    var isBlocked: Boolean = false,

    var useBiometrics: Boolean = false
)
