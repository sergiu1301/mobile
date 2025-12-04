package com.example.travelmate.network

import android.util.Log
import com.example.travelmate.data.Trip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

object RemoteServerClient {
    private const val BASE_URL = "http://10.0.2.2:8000"
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private val client: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(8, TimeUnit.SECONDS)
        .build()

    data class TokenPayload(val token: String, val role: String = "user")

    suspend fun registerUser(name: String, email: String, password: String): Result<TokenPayload> =
        withContext(Dispatchers.IO) {
            try {
                val body = JSONObject()
                    .put("name", name)
                    .put("email", email)
                    .put("password", password)
                    .toString()
                    .toRequestBody(jsonMediaType)

                val request = Request.Builder()
                    .url("$BASE_URL/auth/register")
                    .post(body)
                    .build()

                client.newCall(request).execute().use { response ->
                    val content = response.body?.string() ?: ""
                    if (!response.isSuccessful) {
                        Log.e("RemoteServer", "Register failed: ${response.code} ${response.message} -> $content")
                        return@withContext Result.failure(IOException("Register failed: ${response.code}"))
                    }

                    val json = JSONObject(content)
                    val token = json.optString("token")
                    val role = json.optString("role", "user")
                    if (token.isBlank()) return@withContext Result.failure(IOException("Missing token"))
                    Result.success(TokenPayload(token, role))
                }
            } catch (e: Exception) {
                Log.e("RemoteServer", "Register exception", e)
                Result.failure(e)
            }
        }

    suspend fun loginUser(email: String, password: String): Result<TokenPayload> =
        withContext(Dispatchers.IO) {
            try {
                val body = JSONObject()
                    .put("email", email)
                    .put("password", password)
                    .toString()
                    .toRequestBody(jsonMediaType)

                val request = Request.Builder()
                    .url("$BASE_URL/auth/login")
                    .post(body)
                    .build()

                client.newCall(request).execute().use { response ->
                    val content = response.body?.string() ?: ""
                    if (!response.isSuccessful) {
                        Log.e("RemoteServer", "Login failed: ${response.code} ${response.message} -> $content")
                        return@withContext Result.failure(IOException("Login failed: ${response.code}"))
                    }

                    val json = JSONObject(content)
                    val token = json.optString("token")
                    val role = json.optString("role", "user")
                    if (token.isBlank()) return@withContext Result.failure(IOException("Missing token"))
                    Result.success(TokenPayload(token, role))
                }
            } catch (e: Exception) {
                Log.e("RemoteServer", "Login exception", e)
                Result.failure(e)
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
                        .put("weatherTemp", trip.weatherTemp)
                        .put("weatherDescription", trip.weatherDescription)
                )
            }

            val body = JSONObject().put("trips", tripArray)
                .toString()
                .toRequestBody(jsonMediaType)

            val request = Request.Builder()
                .url("$BASE_URL/trips/sync")
                .addHeader("Authorization", "Bearer $token")
                .post(body)
                .build()

            client.newCall(request).execute().use { response ->
                val content = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    Log.e("RemoteServer", "Sync failed: ${response.code} ${response.message} -> $content")
                    return@withContext Result.failure(IOException("Sync failed: ${response.code}"))
                }

                val json = JSONObject(content)
                return@withContext Result.success(json.optInt("synced", trips.size))
            }
        } catch (e: Exception) {
            Log.e("RemoteServer", "Sync exception", e)
            Result.failure(e)
        }
    }
}
