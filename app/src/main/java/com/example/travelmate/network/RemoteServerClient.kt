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
        .callTimeout(10, TimeUnit.SECONDS)
        .build()

    data class TokenPayload(
        val token: String,
        val role: String
    )

    // ---------------------------------------------------------
    // REGISTER (server does NOT return userId)
    // ---------------------------------------------------------
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

                client.newCall(request).execute().use { resp ->
                    val content = resp.body?.string() ?: ""

                    if (!resp.isSuccessful) {
                        Log.e("SERVER", "REGISTER FAIL $content")
                        return@withContext Result.failure(IOException("Register failed"))
                    }

                    val json = JSONObject(content)
                    val token = json.optString("token")
                    val role = json.optString("role", "user")

                    if (token.isBlank())
                        return@withContext Result.failure(IOException("Invalid server response"))

                    Result.success(TokenPayload(token, role))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    // ---------------------------------------------------------
    // LOGIN (server does NOT return userId)
    // ---------------------------------------------------------
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

                client.newCall(request).execute().use { resp ->
                    val content = resp.body?.string() ?: ""

                    if (!resp.isSuccessful) {
                        Log.e("SERVER", "LOGIN FAIL $content")
                        return@withContext Result.failure(IOException("Login failed"))
                    }

                    val json = JSONObject(content)

                    return@withContext Result.success(
                        TokenPayload(
                            token = json.optString("token"),
                            role = json.optString("role", "user")
                        )
                    )
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    // ---------------------------------------------------------
    // PING
    // ---------------------------------------------------------
    suspend fun ping(): Result<Boolean> =
        withContext(Dispatchers.IO) {
            try {
                val req = Request.Builder()
                    .url("$BASE_URL/ping")
                    .get()
                    .build()

                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful)
                        return@withContext Result.failure(Exception("Ping failed"))

                    Result.success(true)
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    // ---------------------------------------------------------
    // GET TRIPS FROM SERVER
    // ---------------------------------------------------------
    suspend fun getTrips(token: String): Result<List<Trip>> =
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url("$BASE_URL/trips")
                    .addHeader("Authorization", "Bearer $token")
                    .get()
                    .build()

                client.newCall(request).execute().use { response ->
                    val content = response.body?.string() ?: ""

                    if (!response.isSuccessful) {
                        Log.e("SERVER", "getTrips FAIL: ${response.code} -> $content")
                        return@withContext Result.failure(IOException("GetTrips failed"))
                    }

                    val tripsJson = JSONObject(content).optJSONArray("trips") ?: JSONArray()

                    val tripList = mutableListOf<Trip>()

                    for (i in 0 until tripsJson.length()) {
                        val t = tripsJson.getJSONObject(i)

                        tripList.add(
                            Trip(
                                id = t.getInt("id"),
                                title = t.getString("title"),
                                destination = t.getString("destination"),
                                startDate = t.getString("startDate"),
                                endDate = t.getString("endDate"),
                                notes = t.getString("notes"),
                                ownerEmail = t.getString("ownerEmail"),  // 🔥 important!
                                weatherTemp = t.optString("weatherTemp", null),
                                weatherDescription = t.optString("weatherDescription", null),
                                isSynced = true
                            )
                        )
                    }

                    Result.success(tripList)
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    // ---------------------------------------------------------
    // SYNC — sends ownerEmail to the server (correct!)
    // ---------------------------------------------------------
    suspend fun syncTrips(token: String, trips: List<Trip>): Result<Int> =
        withContext(Dispatchers.IO) {
            try {
                val arr = JSONArray()

                trips.forEach { trip ->
                    arr.put(
                        JSONObject()
                            .put("id", trip.id)
                            .put("title", trip.title)
                            .put("destination", trip.destination)
                            .put("startDate", trip.startDate)
                            .put("endDate", trip.endDate)
                            .put("notes", trip.notes)
                            .put("ownerEmail", trip.ownerEmail)  // ✔ correct field
                            .put("weatherTemp", trip.weatherTemp)
                            .put("weatherDescription", trip.weatherDescription)
                    )
                }

                val body = JSONObject()
                    .put("trips", arr)
                    .toString()
                    .toRequestBody(jsonMediaType)

                val request = Request.Builder()
                    .url("$BASE_URL/trips/sync")
                    .addHeader("Authorization", "Bearer $token")
                    .post(body)
                    .build()

                client.newCall(request).execute().use { resp ->
                    val content = resp.body?.string() ?: ""

                    if (!resp.isSuccessful) {
                        Log.e("SERVER", "SYNC FAIL $content")
                        return@withContext Result.failure(IOException("Sync failed"))
                    }

                    val json = JSONObject(content)
                    Result.success(json.optInt("synced", 0))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
}
