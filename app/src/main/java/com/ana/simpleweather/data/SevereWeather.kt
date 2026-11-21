package com.ana.simpleweather.data

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Open-Meteo does not publish official government warnings, so the app derives
 * "severe weather" from the forecast itself: thunderstorms, heavy rain/snow,
 * freezing rain, and strong wind gusts in the next few hours.
 */
data class SevereAlert(val key: String, val title: String, val message: String)

fun ForecastResponse.findSevereWeather(hoursAhead: Int = 12, is24h: Boolean = true): SevereAlert? {
    val timeFormat = DateTimeFormatter.ofPattern(if (is24h) "HH:mm" else "h a", Locale.getDefault())
    val h = hourly ?: return null
    val start = currentHourIndex()
    val end = minOf(start + hoursAhead, h.time.size)
    val gustUnit = hourlyUnits["wind_gusts_10m"] ?: "km/h"
    val gustThreshold = if (gustUnit.contains("mph")) 43.0 else 70.0 // ~70 km/h = damaging gusts

    for (i in start until end) {
        val time = runCatching { LocalDateTime.parse(h.time[i]) }.getOrNull() ?: continue
        val day = time.toLocalDate().toString()
        val hourText = time.format(timeFormat)
        val code = h.weatherCode.getOrNull(i)

        val type: String? = when (code) {
            95 -> "Thunderstorm"
            96, 99 -> "Thunderstorm with hail"
            65, 82 -> "Heavy rain"
            75, 86 -> "Heavy snow"
            57, 67 -> "Freezing rain"
            else -> null
        }
        if (type != null) {
            return SevereAlert(
                key = "$type-$day",
                title = "$type expected",
                message = "$type is forecast around $hourText. Plan ahead and stay safe.",
            )
        }

        val gust = h.windGusts.getOrNull(i)
        if (gust != null && gust >= gustThreshold) {
            return SevereAlert(
                key = "wind-$day",
                title = "Strong winds expected",
                message = "Wind gusts up to ${gust.toInt()} $gustUnit are forecast around $hourText.",
            )
        }
    }
    return null
}
