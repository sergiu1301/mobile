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

    private val client = OkHttpClient.Builder()
        .callTimeout(10, TimeUnit.SECONDS)
        .build()

    // FINAL + UNIVERSAL STRUCT
    data class TokenPayload(
        val token: String,
        val role: String,
        val email: String
    )

    // ============================================================
    // GOOGLE LOGIN
    // ============================================================
    suspend fun googleLogin(email: String, name: String): Result<TokenPayload> =
        withContext(Dispatchers.IO) {
            try {
                val body = JSONObject()
                    .put("email", email)
                    .put("name", name)
                    .toString()
                    .toRequestBody(jsonMediaType)

                val request = Request.Builder()
                    .url("$BASE_URL/auth/google-login")
                    .post(body)
                    .build()

                client.newCall(request).execute().use { resp ->
                    val json = JSONObject(resp.body?.string() ?: "")

                    if (!resp.isSuccessful)
                        return@withContext Result.failure(IOException("Google login failed"))

                    return@withContext Result.success(
                        TokenPayload(
                            token = json.getString("token"),
                            role = json.getString("role"),
                            email = json.getString("email")
                        )
                    )
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    // ============================================================
    // REGISTER
    // ============================================================
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
                    val json = JSONObject(resp.body?.string() ?: "")

                    if (!resp.isSuccessful)
                        return@withContext Result.failure(IOException("Register failed"))

                    return@withContext Result.success(
                        TokenPayload(
                            token = json.getString("token"),
                            role = json.optString("role", "user"),
                            email = email // serverul nu trimite email, îl avem deja
                        )
                    )
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    // ============================================================
    // LOGIN
    // ============================================================
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
                    val json = JSONObject(resp.body?.string() ?: "")

                    if (!resp.isSuccessful)
                        return@withContext Result.failure(IOException("Login failed"))

                    return@withContext Result.success(
                        TokenPayload(
                            token = json.getString("token"),
                            role = json.optString("role", "user"),
                            email = email // server nu trimite email, îl știm
                        )
                    )
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    // ============================================================
    // PING
    // ============================================================
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

    // ============================================================
    // GET TRIPS
    // ============================================================
    suspend fun getTrips(token: String): Result<List<Trip>> =
        withContext(Dispatchers.IO) {
            try {
                val req = Request.Builder()
                    .url("$BASE_URL/trips")
                    .addHeader("Authorization", "Bearer $token")
                    .get()
                    .build()

                client.newCall(req).execute().use { resp ->
                    val content = resp.body?.string() ?: ""
                    if (!resp.isSuccessful)
                        return@withContext Result.failure(IOException("Get trips failed"))

                    val arr = JSONObject(content).optJSONArray("trips") ?: JSONArray()
                    val result = mutableListOf<Trip>()

                    for (i in 0 until arr.length()) {
                        val t = arr.getJSONObject(i)
                        result.add(
                            Trip(
                                id = t.getInt("id"),
                                title = t.getString("title"),
                                destination = t.getString("destination"),
                                startDate = t.getString("startDate"),
                                endDate = t.getString("endDate"),
                                notes = t.getString("notes"),
                                ownerEmail = t.getString("ownerEmail"),
                                weatherTemp = t.optString("weatherTemp", null),
                                weatherDescription = t.optString("weatherDescription", null),
                                isSynced = true
                            )
                        )
                    }

                    Result.success(result)
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    // ============================================================
    // SYNC TRIPS
    // ============================================================
    suspend fun syncTrips(token: String, trips: List<Trip>): Result<Int> =
        withContext(Dispatchers.IO) {
            try {
                val arr = JSONArray()

                trips.forEach {
                    arr.put(
                        JSONObject()
                            .put("id", it.id)
                            .put("title", it.title)
                            .put("destination", it.destination)
                            .put("startDate", it.startDate)
                            .put("endDate", it.endDate)
                            .put("notes", it.notes)
                            .put("weatherTemp", it.weatherTemp)
                            .put("weatherDescription", it.weatherDescription)
                    )
                }

                val body = JSONObject()
                    .put("trips", arr)
                    .toString()
                    .toRequestBody(jsonMediaType)

                val req = Request.Builder()
                    .url("$BASE_URL/trips/sync")
                    .addHeader("Authorization", "Bearer $token")
                    .addHeader("Content-Type", "application/json")
                    .post(body)
                    .build()

                client.newCall(req).execute().use { resp ->
                    val json = JSONObject(resp.body?.string() ?: "")
                    if (!resp.isSuccessful)
                        return@withContext Result.failure(IOException("Sync failed"))

                    Result.success(json.optInt("synced", 0))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    suspend fun getUsers(token: String): Result<List<ServerUserDTO>> =
        withContext(Dispatchers.IO) {
            try {
                val req = Request.Builder()
                    .url("$BASE_URL/admin/users")
                    .addHeader("Authorization", "Bearer $token")
                    .get()
                    .build()

                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful)
                        return@withContext Result.failure(Exception("Get users failed"))

                    val json = JSONObject(resp.body?.string() ?: "")
                    val arr = json.getJSONArray("users")

                    val list = mutableListOf<ServerUserDTO>()
                    for (i in 0 until arr.length()) {
                        val u = arr.getJSONObject(i)
                        list.add(
                            ServerUserDTO(
                                email = u.getString("email"),
                                role = u.getString("role"),
                                isBlocked = u.getInt("isBlocked") == 1
                            )
                        )
                    }

                    Result.success(list)
                }

            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    suspend fun updateUserRole(token: String, email: String, newRole: String): Result<Boolean> =
        withContext(Dispatchers.IO) {
            try {
                val body = JSONObject()
                    .put("role", newRole)
                    .toString()
                    .toRequestBody(jsonMediaType)

                val req = Request.Builder()
                    .url("$BASE_URL/admin/users/$email/role")
                    .addHeader("Authorization", "Bearer $token")
                    .patch(body)
                    .build()

                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful)
                        return@withContext Result.failure(Exception("Role update failed"))

                    Result.success(true)
                }

            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    suspend fun updateBlockStatus(token: String, email: String, block: Boolean): Result<Boolean> =
        withContext(Dispatchers.IO) {
            try {
                val body = JSONObject()
                    .put("block", block)
                    .toString()
                    .toRequestBody(jsonMediaType)

                val req = Request.Builder()
                    .url("$BASE_URL/admin/users/$email/block")
                    .addHeader("Authorization", "Bearer $token")
                    .patch(body)
                    .build()

                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful)
                        return@withContext Result.failure(Exception("Block update failed"))

                    Result.success(true)
                }

            } catch (e: Exception) {
                Result.failure(e)
            }
        }

}
