package com.example.travelmate.network

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object WeatherService {

    private const val API_KEY = "04a3ac082da7cbf357995baa093cb45e"

    suspend fun getWeather(city: String, cnt: Int = 8): Pair<String, String>? = withContext(Dispatchers.IO) {
        try {
            val safeCity = city.trim().replace(" ", "+")
            val urlStr =
                "https://api.openweathermap.org/data/2.5/forecast?q=$safeCity&cnt=$cnt&units=metric&appid=$API_KEY"
            Log.d("WeatherDebug", "🌍 Requesting weather for city: $safeCity")
            Log.d("WeatherDebug", "Full request URL: $urlStr")

            val url = URL(urlStr)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 5000
            connection.readTimeout = 5000

            val code = connection.responseCode
            Log.d("WeatherDebug", "HTTP Response Code: $code")

            if (code != 200) {
                val errorMsg = connection.errorStream?.bufferedReader()?.use { it.readText() }
                Log.e("WeatherDebug", "❌ HTTP Error ($code): ${errorMsg ?: "No message"}")
                return@withContext null
            }

            val data = connection.inputStream.bufferedReader().use { it.readText() }
            Log.d("WeatherDebug", "✅ Raw API response (first 250 chars): ${data.take(250)}...")

            val json = JSONObject(data)
            if (json.optString("cod") != "200") {
                Log.e("WeatherDebug", "❌ API returned error code: ${json.optString("cod")}, message: ${json.optString("message")}")
                return@withContext null
            }

            val first = json.getJSONArray("list").getJSONObject(0)
            val main = first.getJSONObject("main")
            val weather = first.getJSONArray("weather").getJSONObject(0)

            val temp = main.getDouble("temp").toInt().toString() + "°C"
            val description = weather.getString("main")

            Log.d("WeatherDebug", "🌤️ Parsed weather data → temp=$temp, description=$description")
            Pair(temp, description)
        } catch (e: Exception) {
            Log.e("WeatherDebug", "💥 Exception while fetching weather: ${e.message}", e)
            null
        }
    }
}
