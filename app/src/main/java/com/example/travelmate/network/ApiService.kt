package com.example.travelmate.network

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

object ApiService {
    private const val BASE_URL = "http://10.0.2.2:8000"
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private val client: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(10, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val builder = chain.request().newBuilder()
            tokenProvider?.invoke()?.let { token ->
                builder.addHeader("Authorization", "Bearer $token")
            }
            chain.proceed(builder.build())
        }
        .build()

    private var tokenProvider: (() -> String?)? = null

    fun setTokenProvider(provider: () -> String?) {
        tokenProvider = provider
    }

    fun clearTokenProvider() {
        tokenProvider = null
    }

    suspend fun postJson(
        endpoint: String,
        payload: JSONObject,
        requiresAuth: Boolean = false,
        tokenOverride: String? = null
    ): Result<JSONObject> = withContext(Dispatchers.IO) {
        try {
            val body = payload.toString().toRequestBody(jsonMediaType)
            val requestBuilder = Request.Builder()
                .url("$BASE_URL$endpoint")
                .post(body)
                .addHeader("Content-Type", jsonMediaType.toString())

            val token = tokenOverride ?: tokenProvider?.invoke()
            if (requiresAuth && !token.isNullOrBlank()) {
                requestBuilder.addHeader("Authorization", "Bearer $token")
            } else if (requiresAuth) {
                return@withContext Result.failure(IOException("Missing auth token"))
            }

            client.newCall(requestBuilder.build()).execute().use { response ->
                val content = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    Log.e("ApiService", "POST $endpoint failed: ${response.code} -> $content")
                    return@withContext Result.failure(IOException("${response.code}"))
                }

                return@withContext Result.success(JSONObject(content.ifBlank { "{}" }))
            }
        } catch (e: Exception) {
            Log.e("ApiService", "POST $endpoint exception", e)
            Result.failure(e)
        }
    }
}
