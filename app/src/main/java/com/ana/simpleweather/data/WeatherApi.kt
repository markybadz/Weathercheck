package com.ana.simpleweather.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Client for Open-Meteo (https://open-meteo.com). Free for non-commercial use,
 * no account and no API key needed, so there are no secrets in the app.
 */
object WeatherApi {

    private const val NO_INTERNET =
        "No internet connection. Check Wi-Fi or mobile data and try again."

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    /** A downloaded forecast: the raw JSON (for the cache) and the parsed, validated data. */
    class Fetched(val json: String, val response: ForecastResponse, val weather: WeatherData)

    /** Downloads the forecast, parses it once and validates it before it is cached. */
    suspend fun fetchForecast(latitude: Double, longitude: Double, unit: TempUnit): Fetched {
        val url = HttpUrl.Builder()
            .scheme("https")
            .host("api.open-meteo.com")
            .addPathSegments("v1/forecast")
            .addQueryParameter("latitude", latitude.toString())
            .addQueryParameter("longitude", longitude.toString())
            .addQueryParameter(
                "current",
                "temperature_2m,apparent_temperature,relative_humidity_2m,weather_code,wind_speed_10m,wind_direction_10m,is_day"
            )
            .addQueryParameter(
                "hourly",
                "temperature_2m,precipitation_probability,weather_code,wind_gusts_10m,is_day"
            )
            .addQueryParameter(
                "daily",
                "weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max," +
                    "precipitation_sum,uv_index_max,sunrise,sunset"
            )
            .addQueryParameter("minutely_15", "precipitation")
            .addQueryParameter("timezone", "auto")
            // 16 days is the longest forecast Open-Meteo offers. Hourly and 15-minute data
            // are capped (starting from the current step) so the cached response stays small.
            .addQueryParameter("forecast_days", "16")
            .addQueryParameter("forecast_hours", "48")
            .addQueryParameter("forecast_minutely_15", "12")
            .addQueryParameter(
                "temperature_unit",
                if (unit == TempUnit.FAHRENHEIT) "fahrenheit" else "celsius"
            )
            .addQueryParameter(
                "wind_speed_unit",
                if (unit == TempUnit.FAHRENHEIT) "mph" else "kmh"
            )
            .build()
        val body = get(url)
        val response = parseForecast(body)
        val weather = try {
            response.toWeatherData()
        } catch (e: WeatherException) {
            throw e
        } catch (e: Exception) {
            throw WeatherException("Couldn't read the weather data. Please try again later.", e)
        }
        return Fetched(body, response, weather)
    }

    fun parseForecast(body: String): ForecastResponse = try {
        json.decodeFromString(ForecastResponse.serializer(), body)
    } catch (e: Exception) {
        throw WeatherException("Couldn't read the weather data. Please try again later.", e)
    }

    fun parseWeather(body: String): WeatherData = try {
        parseForecast(body).toWeatherData()
    } catch (e: WeatherException) {
        throw e
    } catch (e: Exception) {
        throw WeatherException("Couldn't read the weather data. Please try again later.", e)
    }

    suspend fun searchCity(query: String): List<GeoResult> {
        val url = HttpUrl.Builder()
            .scheme("https")
            .host("geocoding-api.open-meteo.com")
            .addPathSegments("v1/search")
            .addQueryParameter("name", query.trim())
            .addQueryParameter("count", "10")
            .addQueryParameter("language", Locale.getDefault().language.ifBlank { "en" })
            .addQueryParameter("format", "json")
            .build()
        val body = get(url)
        return try {
            json.decodeFromString(GeoResponse.serializer(), body).results
        } catch (e: Exception) {
            throw WeatherException("Couldn't read the search results. Please try again.", e)
        }
    }

    private suspend fun get(url: HttpUrl): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "SimpleWeather-Android/1.0")
            .build()
        try {
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val reason = runCatching {
                        json.decodeFromString(ApiError.serializer(), text).reason
                    }.getOrNull()
                    throw WeatherException(
                        when (response.code) {
                            429 -> "Too many requests to the weather service. Please wait a bit and try again."
                            in 500..599 -> "The weather service is having problems right now. Try again in a few minutes."
                            else -> "Weather service error (${response.code})" +
                                (reason?.let { ": $it" } ?: ".")
                        }
                    )
                }
                text
            }
        } catch (e: WeatherException) {
            throw e
        } catch (e: UnknownHostException) {
            throw WeatherException(NO_INTERNET, e)
        } catch (e: SocketTimeoutException) {
            throw WeatherException("The connection timed out. Check your internet and try again.", e)
        } catch (e: IOException) {
            throw WeatherException(NO_INTERNET, e)
        }
    }
}
