package com.ana.simpleweather.notifications

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.ana.simpleweather.data.SettingsRepository
import com.ana.simpleweather.data.WeatherApi
import com.ana.simpleweather.data.WeatherCodes
import com.ana.simpleweather.data.WeatherException
import com.ana.simpleweather.data.findSevereWeather
import com.ana.simpleweather.data.toWeatherData
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
            val json = WeatherApi.fetchForecastJson(place.latitude, place.longitude, settings.unit)
            repo.saveCache(json, place.latitude, place.longitude)
            val w = WeatherApi.parseForecast(json).toWeatherData()
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
            val json = WeatherApi.fetchForecastJson(place.latitude, place.longitude, settings.unit)
            repo.saveCache(json, place.latitude, place.longitude)
            val alert = WeatherApi.parseForecast(json).findSevereWeather()
            // Only notify once per type of event per day.
            if (alert != null && alert.key != repo.lastSevereKey()) {
                Notifier.show(
                    applicationContext,
                    Notifier.ID_SEVERE,
                    Notifier.CHANNEL_SEVERE,
                    "⚠️ ${alert.title} – ${place.name}",
                    alert.message
                )
                repo.setLastSevereKey(alert.key)
            }
            Result.success()
        } catch (e: WeatherException) {
            Result.success() // try again at the next scheduled check
        }
    }
}
