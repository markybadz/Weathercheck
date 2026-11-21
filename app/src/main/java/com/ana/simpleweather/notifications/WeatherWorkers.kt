package com.ana.simpleweather.notifications

import android.content.Context
import android.text.format.DateFormat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.ana.simpleweather.data.SettingsRepository
import com.ana.simpleweather.data.WeatherApi
import com.ana.simpleweather.data.WeatherCodes
import com.ana.simpleweather.data.WeatherException
import com.ana.simpleweather.data.findSevereWeather
import java.io.IOException
import java.time.LocalTime
import kotlin.math.roundToInt

/**
 * Background workers use the LAST SAVED place (from the last time the app was opened),
 * so they never need background location access.
 */
class DailyWeatherWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val repo = SettingsRepository(applicationContext)
        val settings = repo.currentSettings()
        if (!settings.dailyEnabled) return Result.success()
        val place = repo.currentPlace() ?: return Result.success()

        return try {
            val fetched = WeatherApi.fetchForecast(place.latitude, place.longitude, settings.unit)
            saveCacheSafely(repo, fetched.json, place.latitude, place.longitude)
            val w = fetched.weather
            val today = w.daily.firstOrNull()

            val title = "${place.name}: ${w.temperature.roundToInt()}${w.tempUnit}, " +
                WeatherCodes.describe(w.weatherCode)
            val text = buildString {
                if (today != null) {
                    append("Today ${WeatherCodes.emoji(today.weatherCode)} ")
                    append("high ${today.max.roundToInt()}°, low ${today.min.roundToInt()}°")
                    today.precipitationChance?.let { append(", ${it.roundToInt()}% chance of rain") }
                    append(". ")
                }
                append("Feels like ${w.feelsLike.roundToInt()}°.")
            }
            Notifier.show(applicationContext, Notifier.ID_DAILY, Notifier.CHANNEL_DAILY, title, text)
            Result.success()
        } catch (e: WeatherException) {
            // Retry a few times (e.g. no internet at that moment), then wait for tomorrow.
            if (runAttemptCount < 3) Result.retry() else Result.success()
        }
    }
}

class SevereWeatherWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val repo = SettingsRepository(applicationContext)
        val settings = repo.currentSettings()
        if (!settings.severeEnabled) return Result.success()
        val place = repo.currentPlace() ?: return Result.success()

        return try {
            val fetched = WeatherApi.fetchForecast(place.latitude, place.longitude, settings.unit)
            saveCacheSafely(repo, fetched.json, place.latitude, place.longitude)
            val alert = fetched.response.findSevereWeather(
                is24h = DateFormat.is24HourFormat(applicationContext)
            )
            // Only notify once per type of event per day.
            if (alert != null && alert.key !in repo.notifiedSevereKeys()) {
                val hour = LocalTime.now().hour
                val night = hour >= 22 || hour < 6
                Notifier.show(
                    applicationContext,
                    Notifier.ID_SEVERE,
                    Notifier.CHANNEL_SEVERE,
                    "⚠️ ${alert.title} – ${place.name}",
                    alert.message,
                    silent = settings.quietAtNight && night,
                )
                repo.addNotifiedSevereKey(alert.key)
            }
            Result.success()
        } catch (e: WeatherException) {
            Result.success() // try again at the next scheduled check
        }
    }
}

/** A failed cache write (e.g. disk full) shouldn't stop the notification. */
private suspend fun saveCacheSafely(repo: SettingsRepository, json: String, lat: Double, lon: Double) {
    try {
        repo.saveCache(json, lat, lon)
    } catch (e: IOException) {
        // Keep going; the next successful fetch will refresh the cache.
    }
}
