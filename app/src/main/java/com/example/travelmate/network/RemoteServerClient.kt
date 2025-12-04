package com.example.travelmate.network

import com.example.travelmate.data.Trip

object RemoteServerClient {
    suspend fun registerUser(name: String, email: String, password: String): Result<ApiService.TokenPayload> {
        return ApiService.register(name, email, password)
    }

    suspend fun loginUser(email: String, password: String): Result<ApiService.TokenPayload> {
        return ApiService.login(email, password)
    }

    suspend fun syncTrips(token: String, trips: List<Trip>): Result<Int> {
        ApiService.attachTokenProvider { token }
        val result = ApiService.syncTrips(trips)
        ApiService.clearTokenProvider()
        return result
    }
}
