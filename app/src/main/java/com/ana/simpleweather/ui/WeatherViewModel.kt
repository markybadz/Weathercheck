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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.abs

data class WeatherUiState(
    val loading: Boolean = false,
    val placeName: String? = null,
    val weather: WeatherData? = null,
    val lastUpdated: Long? = null,
    val isStale: Boolean = false,
    val error: String? = null,
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

    init {
        viewModelScope.launch {
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
        _state.update { it.copy(loading = true, error = null, showWelcome = false) }
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

    fun onLocationPermissionDenied() {
        _state.update {
            it.copy(
                error = "Location permission was denied. Search for a city to see its weather.",
                showSearch = true,
            )
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
            val json = WeatherApi.fetchForecastJson(place.latitude, place.longitude, unit)
            repo.saveCache(json, place.latitude, place.longitude)
            val data = WeatherApi.parseWeather(json)
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

    fun dismissError() = _state.update { it.copy(error = null) }

    // ---------- Settings ----------

    fun setUnit(unit: TempUnit) {
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

    private fun updateSchedule(rescheduleDaily: Boolean, change: suspend () -> Unit) {
        viewModelScope.launch {
            change()
            WorkScheduler.apply(getApplication(), repo.currentSettings(), rescheduleDaily)
        }
    }

    private fun CachedForecast.isFor(place: SavedPlace) =
        abs(latitude - place.latitude) < 0.01 && abs(longitude - place.longitude) < 0.01

    /** ~1 km threshold so tiny GPS jitter doesn't change the place name. */
    private fun movedFar(place: SavedPlace, lat: Double, lon: Double) =
        abs(place.latitude - lat) > 0.01 || abs(place.longitude - lon) > 0.01
}
