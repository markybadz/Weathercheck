package com.ana.simpleweather.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Duration
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
    @SerialName("minutely_15") val minutely15: MinutelyDto? = null,
)

@Serializable
data class CurrentDto(
    val time: String,
    @SerialName("temperature_2m") val temperature: Double? = null,
    @SerialName("apparent_temperature") val apparentTemperature: Double? = null,
    @SerialName("relative_humidity_2m") val humidity: Double? = null,
    @SerialName("wind_speed_10m") val windSpeed: Double? = null,
    @SerialName("wind_direction_10m") val windDirection: Double? = null,
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
    @SerialName("is_day") val isDay: List<Int?> = emptyList(),
)

@Serializable
data class DailyDto(
    val time: List<String> = emptyList(),
    @SerialName("weather_code") val weatherCode: List<Int?> = emptyList(),
    @SerialName("temperature_2m_max") val tempMax: List<Double?> = emptyList(),
    @SerialName("temperature_2m_min") val tempMin: List<Double?> = emptyList(),
    @SerialName("precipitation_probability_max") val precipitationProbabilityMax: List<Double?> = emptyList(),
    @SerialName("precipitation_sum") val precipitationSum: List<Double?> = emptyList(),
    @SerialName("uv_index_max") val uvIndexMax: List<Double?> = emptyList(),
    val sunrise: List<String?> = emptyList(),
    val sunset: List<String?> = emptyList(),
)

/** 15-minute steps, used for the "rain in the next 2 hours" line. */
@Serializable
data class MinutelyDto(
    val time: List<String> = emptyList(),
    val precipitation: List<Double?> = emptyList(),
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
    /** Compass direction the wind blows from, e.g. "NE"; null if unknown. */
    val windDirection: String?,
    val precipitationChance: Double?,
    val tempUnit: String,
    val windUnit: String,
    val hourly: List<HourForecast>,
    val daily: List<DayForecast>,
    /** Short-term rain outlook, e.g. "Rain likely in about 30 min". Null if the data is missing. */
    val nowcast: String? = null,
)

data class HourForecast(
    val time: LocalDateTime,
    val temperature: Double,
    val weatherCode: Int,
    val precipitationChance: Double?,
    val isDay: Boolean,
)

data class DayForecast(
    val date: LocalDate,
    val weatherCode: Int,
    val max: Double,
    val min: Double,
    val precipitationChance: Double?,
    /** Total rain/snow for the day in mm; null if unknown. */
    val precipitationSum: Double? = null,
    val uvIndexMax: Double? = null,
    val sunrise: LocalDateTime? = null,
    val sunset: LocalDateTime? = null,
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
                isDay = h.isDay.getOrNull(i)?.let { it == 1 } ?: (LocalDateTime.parse(h.time[i]).hour in 6..17),
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
                precipitationSum = d.precipitationSum.getOrNull(i),
                uvIndexMax = d.uvIndexMax.getOrNull(i),
                sunrise = d.sunrise.getOrNull(i)?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() },
                sunset = d.sunset.getOrNull(i)?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() },
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
        windDirection = c.windDirection?.let(::compassPoint),
        precipitationChance = hourly?.precipitationProbability?.getOrNull(start),
        tempUnit = currentUnits["temperature_2m"] ?: "°",
        windUnit = currentUnits["wind_speed_10m"] ?: "",
        hourly = hours,
        daily = days,
        nowcast = rainNowcast(),
    )
}

private val COMPASS = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")

/** 0-360 degrees to one of 8 compass points (the direction the wind comes from). */
internal fun compassPoint(degrees: Double): String =
    COMPASS[(((degrees % 360 + 360) % 360 + 22.5) / 45).toInt() % 8]

/** WHO UV index categories. */
fun uvCategory(uv: Double): String = when {
    uv < 3 -> "Low"
    uv < 6 -> "Moderate"
    uv < 8 -> "High"
    uv < 11 -> "Very high"
    else -> "Extreme"
}

/** Precipitation per 15 minutes (mm) that counts as "raining". */
private const val RAIN_MM_PER_15_MIN = 0.1

/**
 * Looks at the 15-minute precipitation for the next 2 hours and describes when
 * rain starts or stops. Times are in the location's time zone, like [CurrentDto.time].
 */
internal fun ForecastResponse.rainNowcast(steps: Int = 8): String? {
    val m = minutely15 ?: return null
    val now = current?.time?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() } ?: return null
    val start = m.time.indexOfLast { t ->
        runCatching { !LocalDateTime.parse(t).isAfter(now) }.getOrDefault(false)
    }
    if (start < 0) return null
    val end = minOf(start + steps, m.time.size)
    if (end - start < 2) return null

    fun wet(i: Int) = (m.precipitation.getOrNull(i) ?: 0.0) >= RAIN_MM_PER_15_MIN
    fun minutesUntil(i: Int): Long =
        Duration.between(now, LocalDateTime.parse(m.time[i])).toMinutes().coerceAtLeast(5)

    val rainingNow = wet(start)
    for (i in start + 1 until end) {
        if (wet(i) != rainingNow) {
            val mins = minutesUntil(i)
            return if (rainingNow) "Rain likely to stop in about $mins min"
            else "Rain likely in about $mins min"
        }
    }
    return if (rainingNow) "Rain likely to continue for the next 2 hours"
    else "No rain expected in the next 2 hours"
}
