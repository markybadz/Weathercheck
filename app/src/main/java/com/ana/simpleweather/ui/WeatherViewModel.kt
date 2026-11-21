package com.ana.simpleweather.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ana.simpleweather.data.AppSettings
import com.ana.simpleweather.data.CachedForecast
import com.ana.simpleweather.data.GeoResult
import com.ana.simpleweather.data.LocationProvider
import com.ana.simpleweather.data.SavedPlace
import com.ana.simpleweather.data.SettingsRepository
import com.ana.simpleweather.data.TempUnit
import com.ana.simpleweather.data.WeatherApi
import com.ana.simpleweather.data.WeatherData
import com.ana.simpleweather.data.WeatherException
import com.ana.simpleweather.notifications.WorkScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException
import kotlin.math.abs

data class WeatherUiState(
    val loading: Boolean = false,
    val placeName: String? = null,
    val weather: WeatherData? = null,
    val lastUpdated: Long? = null,
    val isStale: Boolean = false,
    val error: String? = null,
    /** Location permission was denied with "Don't ask again"; offer the app's system settings. */
    val locationBlocked: Boolean = false,
    val showWelcome: Boolean = false,
    val showSearch: Boolean = false,
    val searching: Boolean = false,
    val searchResults: List<GeoResult> = emptyList(),
    val searchError: String? = null,
)

class WeatherViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = SettingsRepository(app)
    private val location = LocationProvider(app)

    private val _state = MutableStateFlow(WeatherUiState())
    val state: StateFlow<WeatherUiState> = _state.asStateFlow()

    val settings: StateFlow<AppSettings> =
        repo.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    private var loadJob: Job? = null
    private var searchJob: Job? = null
    private var autoRefreshJob: Job? = null
    private val startupJob: Job

    init {
        startupJob = viewModelScope.launch {
            WorkScheduler.apply(app, repo.currentSettings(), rescheduleDaily = false)
            val place = repo.currentPlace()
            when {
                place != null -> {
                    // Show saved data instantly, then refresh in the background.
                    repo.cached()?.takeIf { it.isFor(place) }?.let { showCached(it, place, null) }
                    refresh()
                }
                location.hasPermission() -> useDeviceLocation()
                else -> _state.update { it.copy(showWelcome = true) }
            }
        }
    }

    /** Runs one loading task at a time; a new request cancels the previous one. */
    private fun launchLoad(block: suspend CoroutineScope.() -> Unit) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch(block = block)
    }

    // ---------- Real-time updates ----------

    /**
     * Keeps the forecast fresh while the app is on screen: refreshes right away if the
     * data is older than [AUTO_REFRESH_MS], then again every [AUTO_REFRESH_MS].
     * Open-Meteo updates current conditions every 15 minutes, so checking more often
     * would only repeat the same data. Called from the activity's onStart.
     */
    fun startAutoRefresh() {
        autoRefreshJob?.cancel()
        autoRefreshJob = viewModelScope.launch {
            startupJob.join() // startup already loads once; don't race it
            while (isActive) {
                val s = _state.value
                val age = s.lastUpdated?.let { System.currentTimeMillis() - it }
                val due = age == null || age >= AUTO_REFRESH_MS || s.isStale
                val busy = s.loading || loadJob?.isActive == true
                if (due && !busy && !s.showSearch && repo.currentPlace() != null) refresh()
                val wait = if (age == null || due) AUTO_REFRESH_MS else AUTO_REFRESH_MS - age
                delay(wait.coerceAtLeast(MIN_RETRY_MS))
            }
        }
    }

    /** Stops the foreground refresh loop (activity onStop), so no data is used in the background. */
    fun stopAutoRefresh() {
        autoRefreshJob?.cancel()
        autoRefreshJob = null
    }

    fun refresh() = launchLoad {
        val place = repo.currentPlace()
        if (place == null) {
            _state.update { it.copy(showWelcome = true) }
            return@launchLoad
        }
        var target = place
        if (place.fromDevice && location.hasPermission() && location.isLocationEnabled()) {
            _state.update { it.copy(loading = true, error = null) }
            val loc = location.currentLocation()
            if (loc != null && movedFar(place, loc.latitude, loc.longitude)) {
                val name = location.placeName(loc.latitude, loc.longitude) ?: "Current location"
                target = SavedPlace(loc.latitude, loc.longitude, name, fromDevice = true)
                repo.savePlace(target)
            }
        }
        load(target)
    }

    fun useDeviceLocation() = launchLoad {
        if (!location.hasPermission()) {
            _state.update {
                it.copy(error = "Location permission is needed to use your current location. You can search for a city instead.")
            }
            return@launchLoad
        }
        if (!location.isLocationEnabled()) {
            _state.update {
                it.copy(
                    error = "Location is turned off on your phone. Turn it on in Quick Settings, or search for a city.",
                    showWelcome = it.weather == null,
                )
            }
            return@launchLoad
        }
        _state.update { it.copy(loading = true, error = null, locationBlocked = false, showWelcome = false) }
        val loc = location.currentLocation()
        if (loc == null) {
            _state.update {
                it.copy(
                    loading = false,
                    error = "Couldn't find your location right now. Try again in a moment, or search for a city.",
                    showWelcome = it.weather == null,
                )
            }
            return@launchLoad
        }
        val name = location.placeName(loc.latitude, loc.longitude) ?: "Current location"
        val place = SavedPlace(loc.latitude, loc.longitude, name, fromDevice = true)
        repo.savePlace(place)
        load(place)
    }

    /** @param permanently true when Android will no longer show the permission prompt. */
    fun onLocationPermissionDenied(permanently: Boolean) {
        _state.update {
            if (permanently) {
                it.copy(
                    error = "Location access is turned off for this app. Allow it in the app's settings, " +
                        "or search for a city instead.",
                    locationBlocked = true,
                )
            } else {
                it.copy(
                    error = "Location permission was denied. Search for a city to see its weather.",
                    locationBlocked = false,
                    showSearch = true,
                )
            }
        }
    }

    fun selectCity(result: GeoResult) = launchLoad {
        val place = SavedPlace(result.latitude, result.longitude, result.displayName, fromDevice = false)
        repo.savePlace(place)
        _state.update { it.copy(showSearch = false, searchResults = emptyList(), searchError = null) }
        load(place)
    }

    private suspend fun load(place: SavedPlace) {
        _state.update {
            it.copy(loading = true, error = null, placeName = place.name, showWelcome = false)
        }
        val unit = repo.currentSettings().unit
        try {
            val fetched = WeatherApi.fetchForecast(place.latitude, place.longitude, unit)
            try {
                repo.saveCache(fetched.json, place.latitude, place.longitude)
            } catch (e: IOException) {
                // A failed cache write (e.g. storage full) shouldn't hide fresh data.
            }
            val data = fetched.weather
            _state.update {
                it.copy(
                    loading = false,
                    weather = data,
                    lastUpdated = System.currentTimeMillis(),
                    isStale = false,
                    error = null,
                )
            }
        } catch (e: WeatherException) {
            val cache = repo.cached()?.takeIf { it.isFor(place) }
            if (cache != null) {
                showCached(cache, place, e.message)
            } else {
                _state.update { it.copy(loading = false, weather = null, lastUpdated = null, error = e.message) }
            }
        }
    }

    private fun showCached(cache: CachedForecast, place: SavedPlace, error: String?) {
        val data = runCatching { WeatherApi.parseWeather(cache.json) }.getOrNull()
        _state.update {
            it.copy(
                loading = false,
                placeName = place.name,
                weather = data ?: it.weather,
                lastUpdated = if (data != null) cache.fetchedAt else it.lastUpdated,
                isStale = data != null && error != null,
                error = error,
            )
        }
    }

    // ---------- City search ----------

    fun openSearch() = _state.update { it.copy(showSearch = true, searchError = null) }

    fun closeSearch() {
        searchJob?.cancel()
        _state.update { it.copy(showSearch = false, searching = false, searchResults = emptyList(), searchError = null) }
    }

    fun search(query: String) {
        if (query.isBlank()) return
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            _state.update { it.copy(searching = true, searchError = null) }
            try {
                val results = WeatherApi.searchCity(query)
                _state.update {
                    it.copy(
                        searching = false,
                        searchResults = results,
                        searchError = if (results.isEmpty()) "No places found for \"${query.trim()}\". Check the spelling or try a nearby city." else null,
                    )
                }
            } catch (e: WeatherException) {
                _state.update { it.copy(searching = false, searchResults = emptyList(), searchError = e.message) }
            }
        }
    }

    fun dismissError() = _state.update { it.copy(error = null, locationBlocked = false) }

    // ---------- Settings ----------

    fun setUnit(unit: TempUnit) {
        if (unit == settings.value.unit) return
        viewModelScope.launch {
            repo.setUnit(unit)
            refresh()
        }
    }

    fun setDailyEnabled(enabled: Boolean) = updateSchedule(rescheduleDaily = true) {
        repo.setDailyEnabled(enabled)
    }

    fun setDailyTime(hour: Int, minute: Int) = updateSchedule(rescheduleDaily = true) {
        repo.setDailyTime(hour, minute)
    }

    fun setSevereEnabled(enabled: Boolean) = updateSchedule(rescheduleDaily = false) {
        repo.setSevereEnabled(enabled)
    }

    fun setQuietAtNight(enabled: Boolean) {
        viewModelScope.launch { repo.setQuietAtNight(enabled) }
    }

    private fun updateSchedule(rescheduleDaily: Boolean, change: suspend () -> Unit) {
        viewModelScope.launch {
            change()
            WorkScheduler.apply(getApplication(), repo.currentSettings(), rescheduleDaily)
        }
    }

    private fun CachedForecast.isFor(place: SavedPlace) =
        abs(latitude - place.latitude) < 0.01 && abs(longitude - place.longitude) < 0.01

    private companion object {
        const val AUTO_REFRESH_MS = 15 * 60 * 1000L
        const val MIN_RETRY_MS = 60 * 1000L
    }

    /** ~1 km threshold so tiny GPS jitter doesn't change the place name. */
    private fun movedFar(place: SavedPlace, lat: Double, lon: Double) =
        abs(place.latitude - lat) > 0.01 || abs(place.longitude - lon) > 0.01
}
