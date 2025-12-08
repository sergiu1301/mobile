package com.example.travelmate.network

import android.util.Log
import com.example.travelmate.data.Trip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

object RemoteServerClient {

    data class TokenPayload(val token: String, val role: String = "user")

    suspend fun registerUser(name: String, email: String, password: String): Result<TokenPayload> =
        withContext(Dispatchers.IO) {
            val payload = JSONObject()
                .put("name", name)
                .put("email", email)
                .put("password", password)

            ApiService.postJson("/auth/register", payload).mapCatching { json ->
                val token = json.optString("token")
                val role = json.optString("role", "user")
                if (token.isBlank()) throw IOException("Missing token")
                TokenPayload(token, role)
            }
        }

    suspend fun loginUser(email: String, password: String): Result<TokenPayload> =
        withContext(Dispatchers.IO) {
            val payload = JSONObject()
                .put("email", email)
                .put("password", password)

            ApiService.postJson("/auth/login", payload).mapCatching { json ->
                val token = json.optString("token")
                val role = json.optString("role", "user")
                if (token.isBlank()) throw IOException("Missing token")
                TokenPayload(token, role)
            }
        }

    suspend fun syncTrips(token: String, trips: List<Trip>): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val tripArray = JSONArray()
            trips.forEach { trip ->
                tripArray.put(
                    JSONObject()
                        .put("id", trip.id)
                        .put("title", trip.title)
                        .put("destination", trip.destination)
                        .put("startDate", trip.startDate)
                        .put("endDate", trip.endDate)
                        .put("notes", trip.notes)
                        .put("ownerEmail", trip.ownerEmail)
                        .put("weatherTemp", trip.weatherTemp)
                        .put("weatherDescription", trip.weatherDescription)
                )
            }

            val payload = JSONObject().put("trips", tripArray)
            ApiService.postJson("/trips/sync", payload, requiresAuth = true, tokenOverride = token)
                .mapCatching { json ->
                    json.optInt("synced", trips.size)
                }
        } catch (e: Exception) {
            Log.e("RemoteServer", "Sync exception", e)
            Result.failure(e)
        }
    }
}
