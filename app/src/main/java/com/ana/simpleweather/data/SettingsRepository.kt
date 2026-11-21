package com.ana.simpleweather.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException
import java.time.LocalDate

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

data class AppSettings(
    val unit: TempUnit = TempUnit.CELSIUS,
    val dailyEnabled: Boolean = false,
    val dailyHour: Int = 7,
    val dailyMinute: Int = 0,
    val severeEnabled: Boolean = false,
    /** Severe alerts between 10 PM and 6 AM arrive without sound or vibration. */
    val quietAtNight: Boolean = true,
)

/** The place whose weather is shown. [fromDevice] = true means "my location". */
data class SavedPlace(
    val latitude: Double,
    val longitude: Double,
    val name: String,
    val fromDevice: Boolean,
)

data class CachedForecast(
    val json: String,
    val fetchedAt: Long,
    val latitude: Double,
    val longitude: Double,
)

class SettingsRepository(context: Context) {

    private val store = context.applicationContext.dataStore

    private object Keys {
        val UNIT = stringPreferencesKey("unit")
        val DAILY = booleanPreferencesKey("daily_enabled")
        val DAILY_HOUR = intPreferencesKey("daily_hour")
        val DAILY_MINUTE = intPreferencesKey("daily_minute")
        val SEVERE = booleanPreferencesKey("severe_enabled")
        val LAT = doublePreferencesKey("place_lat")
        val LON = doublePreferencesKey("place_lon")
        val NAME = stringPreferencesKey("place_name")
        val FROM_DEVICE = booleanPreferencesKey("place_from_device")
        val CACHE_JSON = stringPreferencesKey("cache_json")
        val CACHE_TIME = longPreferencesKey("cache_time")
        val CACHE_LAT = doublePreferencesKey("cache_lat")
        val CACHE_LON = doublePreferencesKey("cache_lon")
        val SEVERE_KEYS = stringPreferencesKey("notified_severe_keys")
        val QUIET_NIGHT = booleanPreferencesKey("quiet_at_night")
    }

    private val data: Flow<Preferences> = store.data.catch { e ->
        if (e is IOException) emit(emptyPreferences()) else throw e
    }

    val settings: Flow<AppSettings> = data.map { p ->
        AppSettings(
            unit = if (p[Keys.UNIT] == TempUnit.FAHRENHEIT.name) TempUnit.FAHRENHEIT else TempUnit.CELSIUS,
            dailyEnabled = p[Keys.DAILY] ?: false,
            dailyHour = p[Keys.DAILY_HOUR] ?: 7,
            dailyMinute = p[Keys.DAILY_MINUTE] ?: 0,
            severeEnabled = p[Keys.SEVERE] ?: false,
            quietAtNight = p[Keys.QUIET_NIGHT] ?: true,
        )
    }

    val place: Flow<SavedPlace?> = data.map { p ->
        val lat = p[Keys.LAT]
        val lon = p[Keys.LON]
        if (lat == null || lon == null) null
        else SavedPlace(lat, lon, p[Keys.NAME] ?: "Saved place", p[Keys.FROM_DEVICE] ?: false)
    }

    suspend fun currentSettings(): AppSettings = settings.first()
    suspend fun currentPlace(): SavedPlace? = place.first()

    suspend fun cached(): CachedForecast? {
        val p = data.first()
        val json = p[Keys.CACHE_JSON] ?: return null
        return CachedForecast(
            json = json,
            fetchedAt = p[Keys.CACHE_TIME] ?: 0L,
            latitude = p[Keys.CACHE_LAT] ?: return null,
            longitude = p[Keys.CACHE_LON] ?: return null,
        )
    }

    suspend fun setUnit(unit: TempUnit) = store.edit { it[Keys.UNIT] = unit.name }
    suspend fun setDailyEnabled(enabled: Boolean) = store.edit { it[Keys.DAILY] = enabled }
    suspend fun setSevereEnabled(enabled: Boolean) = store.edit { it[Keys.SEVERE] = enabled }
    suspend fun setQuietAtNight(enabled: Boolean) = store.edit { it[Keys.QUIET_NIGHT] = enabled }
    suspend fun setDailyTime(hour: Int, minute: Int) = store.edit {
        it[Keys.DAILY_HOUR] = hour
        it[Keys.DAILY_MINUTE] = minute
    }

    suspend fun savePlace(place: SavedPlace) = store.edit {
        it[Keys.LAT] = place.latitude
        it[Keys.LON] = place.longitude
        it[Keys.NAME] = place.name
        it[Keys.FROM_DEVICE] = place.fromDevice
    }

    suspend fun saveCache(json: String, latitude: Double, longitude: Double) = store.edit {
        it[Keys.CACHE_JSON] = json
        it[Keys.CACHE_TIME] = System.currentTimeMillis()
        it[Keys.CACHE_LAT] = latitude
        it[Keys.CACHE_LON] = longitude
    }

    // Severe alerts already shown, as "<type>-<yyyy-mm-dd>" keys, so each type of event
    // is announced once per day even if different events alternate.
    suspend fun notifiedSevereKeys(): Set<String> =
        data.first()[Keys.SEVERE_KEYS]?.split('|')?.filter { it.isNotBlank() }?.toSet() ?: emptySet()

    suspend fun addNotifiedSevereKey(key: String) = store.edit { p ->
        val cutoff = LocalDate.now().minusDays(2)
        val kept = (p[Keys.SEVERE_KEYS]?.split('|').orEmpty() + key)
            .filter { k ->
                val day = runCatching { LocalDate.parse(k.takeLast(10)) }.getOrNull()
                day != null && !day.isBefore(cutoff)
            }
            .distinct()
        p[Keys.SEVERE_KEYS] = kept.joinToString("|")
    }
}
