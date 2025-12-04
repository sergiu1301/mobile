package com.example.travelmate.network

import android.util.Log
import com.example.travelmate.data.Trip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

object ApiService {
    private const val BASE_URL = "http://10.0.2.2:8000"
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private var tokenProvider: (() -> String?)? = null

    private val authInterceptor = Interceptor { chain ->
        val original = chain.request()
        val builder = original.newBuilder()
        val token = tokenProvider?.invoke()
        if (!token.isNullOrBlank()) {
            builder.addHeader("Authorization", "Bearer $token")
        }
        chain.proceed(builder.build())
    }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(12, TimeUnit.SECONDS)
        .addInterceptor(authInterceptor)
        .build()

    data class TokenPayload(val token: String, val role: String = "user")

    fun attachTokenProvider(provider: () -> String?) {
        tokenProvider = provider
    }

    fun clearTokenProvider() {
        tokenProvider = null
    }

    suspend fun login(email: String, password: String): Result<TokenPayload> =
        post("/auth/login", JSONObject().put("email", email).put("password", password))
            .mapCatching { json ->
                val token = json.optString("token")
                if (token.isBlank()) throw IOException("Missing token")
                TokenPayload(token, json.optString("role", "user"))
            }

    suspend fun register(
        name: String,
        email: String,
        password: String
    ): Result<TokenPayload> = post(
        "/auth/register",
        JSONObject().put("name", name).put("email", email).put("password", password)
    ).mapCatching { json ->
        val token = json.optString("token")
        if (token.isBlank()) throw IOException("Missing token")
        TokenPayload(token, json.optString("role", "user"))
    }

    suspend fun syncTrips(trips: List<Trip>): Result<Int> {
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
                    .put("ownerEmail", trip.ownerEmail)
            )
        }

        return post("/trips/sync", JSONObject().put("trips", tripArray), requireAuth = true)
            .mapCatching { json -> json.optInt("synced", trips.size) }
    }

    private suspend fun post(
        path: String,
        body: JSONObject,
        requireAuth: Boolean = false
    ): Result<JSONObject> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$BASE_URL$path")
                .post(body.toString().toRequestBody(jsonMediaType))
                .build()

            client.newCall(request).execute().use { response ->
                val content = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    Log.e("ApiService", "Request failed ${response.code} ${response.message} -> $content")
                    val error = if (requireAuth && response.code == 401) "Session expired" else "Request failed"
                    return@withContext Result.failure(IOException(error))
                }

                if (content.isBlank()) return@withContext Result.success(JSONObject())
                Result.success(JSONObject(content))
            }
        } catch (e: Exception) {
            Log.e("ApiService", "Exception during request", e)
            Result.failure(e)
        }
    }
}
