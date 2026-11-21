package com.ana.simpleweather.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.LocalDateTime

enum class TempUnit { CELSIUS, FAHRENHEIT }

/** Thrown with a message that is safe to show to the user. */
class WeatherException(message: String, cause: Throwable? = null) : Exception(message, cause)

// ---------- Open-Meteo forecast response (only the fields the app uses) ----------

@Serializable
data class ForecastResponse(
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    @SerialName("current_units") val currentUnits: Map<String, String> = emptyMap(),
    @SerialName("hourly_units") val hourlyUnits: Map<String, String> = emptyMap(),
    val current: CurrentDto? = null,
    val hourly: HourlyDto? = null,
    val daily: DailyDto? = null,
)

@Serializable
data class CurrentDto(
    val time: String,
    @SerialName("temperature_2m") val temperature: Double? = null,
    @SerialName("apparent_temperature") val apparentTemperature: Double? = null,
    @SerialName("relative_humidity_2m") val humidity: Double? = null,
    @SerialName("wind_speed_10m") val windSpeed: Double? = null,
    @SerialName("weather_code") val weatherCode: Int? = null,
    @SerialName("is_day") val isDay: Int? = null,
)

@Serializable
data class HourlyDto(
    val time: List<String> = emptyList(),
    @SerialName("temperature_2m") val temperature: List<Double?> = emptyList(),
    @SerialName("precipitation_probability") val precipitationProbability: List<Double?> = emptyList(),
    @SerialName("weather_code") val weatherCode: List<Int?> = emptyList(),
    @SerialName("wind_gusts_10m") val windGusts: List<Double?> = emptyList(),
)

@Serializable
data class DailyDto(
    val time: List<String> = emptyList(),
    @SerialName("weather_code") val weatherCode: List<Int?> = emptyList(),
    @SerialName("temperature_2m_max") val tempMax: List<Double?> = emptyList(),
    @SerialName("temperature_2m_min") val tempMin: List<Double?> = emptyList(),
    @SerialName("precipitation_probability_max") val precipitationProbabilityMax: List<Double?> = emptyList(),
)

@Serializable
data class ApiError(val error: Boolean = true, val reason: String? = null)

// ---------- Open-Meteo geocoding (city search) ----------

@Serializable
data class GeoResponse(val results: List<GeoResult> = emptyList())

@Serializable
data class GeoResult(
    val id: Long = 0,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val country: String? = null,
    val admin1: String? = null,
) {
    val region: String get() = listOfNotNull(admin1, country).distinct().joinToString(", ")
    val displayName: String get() = listOfNotNull(name, admin1, country).distinct().joinToString(", ")
}

// ---------- App-side models used by the UI ----------

data class WeatherData(
    val temperature: Double,
    val feelsLike: Double,
    val weatherCode: Int,
    val isDay: Boolean,
    val humidity: Double,
    val windSpeed: Double,
    val precipitationChance: Double?,
    val tempUnit: String,
    val windUnit: String,
    val hourly: List<HourForecast>,
    val daily: List<DayForecast>,
)

data class HourForecast(
    val time: LocalDateTime,
    val temperature: Double,
    val weatherCode: Int,
    val precipitationChance: Double?,
)

data class DayForecast(
    val date: LocalDate,
    val weatherCode: Int,
    val max: Double,
    val min: Double,
    val precipitationChance: Double?,
)

/** Index of the hourly slot that contains "now" at the forecast location. */
internal fun ForecastResponse.currentHourIndex(): Int {
    val times = hourly?.time ?: return 0
    val now = current?.time?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() }
        ?: return 0
    val idx = times.indexOfLast { t ->
        runCatching { !LocalDateTime.parse(t).isAfter(now) }.getOrDefault(false)
    }
    return if (idx < 0) 0 else idx
}

fun ForecastResponse.toWeatherData(): WeatherData {
    val c = current ?: throw WeatherException("The weather service returned incomplete data.")
    val temp = c.temperature ?: throw WeatherException("The weather service returned incomplete data.")
    val start = currentHourIndex()

    val hours = mutableListOf<HourForecast>()
    hourly?.let { h ->
        val end = minOf(start + 24, h.time.size)
        for (i in start until end) {
            val t = h.temperature.getOrNull(i) ?: continue
            hours += HourForecast(
                time = LocalDateTime.parse(h.time[i]),
                temperature = t,
                weatherCode = h.weatherCode.getOrNull(i) ?: 0,
                precipitationChance = h.precipitationProbability.getOrNull(i),
            )
        }
    }

    val days = mutableListOf<DayForecast>()
    daily?.let { d ->
        for (i in d.time.indices) {
            val max = d.tempMax.getOrNull(i) ?: continue
            val min = d.tempMin.getOrNull(i) ?: continue
            days += DayForecast(
                date = LocalDate.parse(d.time[i]),
                weatherCode = d.weatherCode.getOrNull(i) ?: 0,
                max = max,
                min = min,
                precipitationChance = d.precipitationProbabilityMax.getOrNull(i),
            )
        }
    }

    return WeatherData(
        temperature = temp,
        feelsLike = c.apparentTemperature ?: temp,
        weatherCode = c.weatherCode ?: 0,
        isDay = (c.isDay ?: 1) == 1,
        humidity = c.humidity ?: 0.0,
        windSpeed = c.windSpeed ?: 0.0,
        precipitationChance = hourly?.precipitationProbability?.getOrNull(start),
        tempUnit = currentUnits["temperature_2m"] ?: "°",
        windUnit = currentUnits["wind_speed_10m"] ?: "",
        hourly = hours,
        daily = days,
    )
}
