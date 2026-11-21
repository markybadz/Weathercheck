package com.ana.simpleweather.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertTrue
import org.junit.Test

private val sample = """
{"latitude":14.6,"longitude":121.0,"timezone":"Asia/Manila","utc_offset_seconds":28800,"elevation":12.0,
"current_units":{"time":"iso8601","interval":"seconds","temperature_2m":"°C","apparent_temperature":"°C","relative_humidity_2m":"%","weather_code":"wmo code","wind_speed_10m":"km/h","is_day":""},
"current":{"time":"2026-11-04T09:15","interval":900,"temperature_2m":29.4,"apparent_temperature":33.1,"relative_humidity_2m":74,"weather_code":2,"wind_speed_10m":9.7,"is_day":1},
"hourly_units":{"time":"iso8601","temperature_2m":"°C","precipitation_probability":"%","weather_code":"wmo code","wind_gusts_10m":"km/h"},
"hourly":{"time":["2026-11-04T08:00","2026-11-04T09:00","2026-11-04T10:00","2026-11-04T11:00"],
"temperature_2m":[28.1,29.4,30.2,null],"precipitation_probability":[10,20,35,60],"weather_code":[1,2,3,95],"wind_gusts_10m":[15.0,18.2,22.0,40.0]},
"daily_units":{},
"daily":{"time":["2026-11-04","2026-11-05"],"weather_code":[95,61],"temperature_2m_max":[32.0,31.1],"temperature_2m_min":[25.2,24.9],"precipitation_probability_max":[80,null]},
"minutely_15":{"time":["2026-11-04T09:15","2026-11-04T09:30","2026-11-04T09:45","2026-11-04T10:00","2026-11-04T10:15"],"precipitation":[0.0,0.0,0.3,0.5,0.0]}}
"""

private fun check(c: Boolean, m: String) = assertTrue(m, c)

class WeatherParsingTest {
    @Test
    fun parsesForecastAndDetectsSevereWeather() {
    val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    val r = json.decodeFromString(ForecastResponse.serializer(), sample)
    val w = r.toWeatherData()
    check(w.temperature == 29.4 && w.tempUnit == "°C" && w.windUnit == "km/h", "current + units")
    check(w.humidity == 74.0 && w.feelsLike == 33.1, "humidity/feels-like")
    check(w.precipitationChance == 20.0, "rain chance from current hour (09:00)")
    check(w.hourly.size == 2 && w.hourly.first().time.hour == 9, "hourly starts at current hour, skips null temp")
    check(w.daily.size == 2 && w.daily[1].precipitationChance == null, "daily parsed")
    val a = r.findSevereWeather()
    check(a != null && a.key == "Thunderstorm-2026-11-04", "thunderstorm detected: ${a?.title} / ${a?.message}")
    val calm = r.copy(hourly = r.hourly!!.copy(weatherCode = listOf(1,2,3,3)))
    check(calm.findSevereWeather() == null, "no alert for calm weather")
    val windy = calm.copy(hourly = calm.hourly!!.copy(windGusts = listOf(10.0, 20.0, 75.0, 10.0)))
    check(windy.findSevereWeather()?.key == "wind-2026-11-04", "strong gust detected")
    val geo = json.decodeFromString(GeoResponse.serializer(), """{"generationtime_ms":0.5}""")
    check(geo.results.isEmpty(), "geocoding with no results")
    val g2 = json.decodeFromString(GeoResponse.serializer(), """{"results":[{"id":1,"name":"Manila","latitude":14.6,"longitude":120.98,"country":"Philippines","admin1":"Metro Manila"}]}""")
    check(g2.results[0].displayName == "Manila, Metro Manila, Philippines", "geo display name")
    check(WeatherCodes.describe(95) == "Thunderstorm", "weather codes")
    check(w.nowcast == "Rain likely in about 30 min", "nowcast rain start: ${w.nowcast}")
    val wet = r.copy(minutely15 = r.minutely15!!.copy(precipitation = listOf(0.4, 0.2, 0.0, 0.0, 0.0)))
    check(wet.rainNowcast() == "Rain likely to stop in about 30 min", "nowcast rain stop: ${wet.rainNowcast()}")
    val dry = r.copy(minutely15 = r.minutely15!!.copy(precipitation = listOf(0.0, 0.0, 0.0, 0.0, 0.0)))
    check(dry.rainNowcast() == "No rain expected in the next 2 hours", "nowcast dry")
    check(r.copy(minutely15 = null).rainNowcast() == null, "nowcast missing data")
    }
}
