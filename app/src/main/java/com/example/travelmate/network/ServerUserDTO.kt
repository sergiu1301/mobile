package com.example.travelmate.network

data class ServerUserDTO(
    val email: String,
    var role: String,
    var isBlocked: Boolean
)