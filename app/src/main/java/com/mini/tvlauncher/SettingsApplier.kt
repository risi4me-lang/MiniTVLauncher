package com.mini.tvlauncher

import android.app.Activity
import android.content.SharedPreferences
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ImageView
import android.widget.TextClock
import android.widget.TextView
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar
import kotlin.concurrent.thread

class SettingsApplier(
    private val activity: Activity,
    private val prefs: SharedPreferences,
    private val greetingView: TextView,
    private val clockView: TextClock,
    private val dateView: TextClock,
    private val weatherContainer: View,
    private val weatherIcon: ImageView,
    private val weatherText: TextView
) {
    private val weatherHandler = Handler(Looper.getMainLooper())
    private val weatherRunnable = object : Runnable {
        override fun run() {
            fetchWeatherBackground()
            weatherHandler.postDelayed(this, 30 * 60 * 1000L)
        }
    }

    fun applySettings() {
        val userName = prefs.getString("user_name", "")?.trim() ?: ""
        val is12h = prefs.getBoolean("clock_is_12h", true)
        val showSeconds = prefs.getBoolean("clock_show_seconds", false)
        val showDate = prefs.getBoolean("clock_show_date", true)

        updateGreeting(userName)

        val pattern = if (is12h) {
            if (showSeconds) "h:mm:ss a" else "h:mm a"
        } else {
            if (showSeconds) "HH:mm:ss" else "HH:mm"
        }
        clockView.format12Hour = pattern
        clockView.format24Hour = pattern

        dateView.visibility = if (showDate) View.VISIBLE else View.GONE
    }

    private fun updateGreeting(userName: String) {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val timeGreeting = when (hour) {
            in 4..11 -> "Good morning"
            in 12..16 -> "Good afternoon"
            in 17..21 -> "Good evening"
            else -> "Good night"
        }
        greetingView.text = if (userName.isNotEmpty()) "$timeGreeting, $userName" else timeGreeting
    }

    fun startWeatherPolling() {
        weatherHandler.removeCallbacks(weatherRunnable)
        weatherHandler.post(weatherRunnable)
    }

    fun stopWeatherPolling() {
        weatherHandler.removeCallbacks(weatherRunnable)
    }

    fun fetchWeatherBackground() {
        val cityName = prefs.getString("weather_city", "")?.trim() ?: ""
        val unit = prefs.getString("weather_unit", "c") ?: "c"

        if (cityName.isEmpty()) {
            activity.runOnUiThread { weatherContainer.visibility = View.GONE }
            return
        }

        showCachedWeatherOrHide(cityName, unit)

        thread {
            try {
                val geoUrl = "https://geocoding-api.open-meteo.com/v1/search?name=" + Uri.encode(cityName) + "&count=1"
                val geoJson = httpGet(geoUrl) ?: throw Exception("Geocode network failed")
                val geoObj = JSONObject(geoJson)
                val results = geoObj.optJSONArray("results") ?: throw Exception("No geocode results")
                if (results.length() == 0) throw Exception("City not found")
                val first = results.getJSONObject(0)
                val lat = first.getDouble("latitude")
                val lon = first.getDouble("longitude")
                val matchedName = first.optString("name", cityName)

                val unitParam = if (unit == "f") "&temperature_unit=fahrenheit" else ""
                val forecastUrl = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current_weather=true$unitParam"
                val forecastJson = httpGet(forecastUrl) ?: throw Exception("Forecast network failed")
                val forecastObj = JSONObject(forecastJson)
                val currentWeather = forecastObj.getJSONObject("current_weather")
                val temp = currentWeather.getDouble("temperature")
                val code = currentWeather.getInt("weathercode")

                prefs.edit()
                    .putFloat("cached_weather_temp", temp.toFloat())
                    .putInt("cached_weather_code", code)
                    .putString("cached_weather_city", matchedName)
                    .putString("cached_weather_unit", unit)
                    .apply()

                activity.runOnUiThread {
                    updateWeatherUi(temp, code, matchedName, unit)
                }
            } catch (_: Exception) {
                activity.runOnUiThread {
                    showCachedWeatherOrHide(cityName, unit)
                }
            }
        }
    }

    private fun showCachedWeatherOrHide(cityName: String, unit: String) {
        val cachedCity = prefs.getString("cached_weather_city", "") ?: ""
        val cachedUnit = prefs.getString("cached_weather_unit", "c") ?: "c"
        if (cachedCity.equals(cityName, ignoreCase = true) && cachedUnit == unit && prefs.contains("cached_weather_temp")) {
            val temp = prefs.getFloat("cached_weather_temp", 0f).toDouble()
            val code = prefs.getInt("cached_weather_code", 0)
            updateWeatherUi(temp, code, cachedCity, cachedUnit)
        } else {
            weatherContainer.visibility = View.GONE
        }
    }

    private fun updateWeatherUi(temp: Double, code: Int, city: String, unit: String) {
        val unitLabel = if (unit == "f") "°F" else "°C"
        val formattedTemp = Math.round(temp).toString() + unitLabel
        weatherText.text = "$formattedTemp  $city"
        weatherIcon.setImageResource(getWeatherIconRes(code))
        weatherContainer.visibility = View.VISIBLE
    }

    private fun getWeatherIconRes(code: Int): Int {
        return when (code) {
            0 -> R.drawable.ic_weather_sunny
            1, 2, 3 -> R.drawable.ic_weather_cloudy
            45, 48 -> R.drawable.ic_weather_fog
            51, 53, 55, 61, 63, 65, 80, 81, 82 -> R.drawable.ic_weather_rain
            71, 73, 75, 77, 85, 86 -> R.drawable.ic_weather_snow
            95, 96, 99 -> R.drawable.ic_weather_thunder
            else -> R.drawable.ic_weather_cloudy
        }
    }

    private fun httpGet(urlStr: String): String? {
        return try {
            val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 8000
                requestMethod = "GET"
            }
            if (conn.responseCode == 200) {
                conn.inputStream.use { it.bufferedReader().readText() }
            } else null
        } catch (_: Exception) { null }
    }
}
