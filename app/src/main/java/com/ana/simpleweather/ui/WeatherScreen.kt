package com.ana.simpleweather.ui

import android.Manifest
import android.content.Context
import android.text.format.DateFormat
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ana.simpleweather.data.DayForecast
import com.ana.simpleweather.data.GeoResult
import com.ana.simpleweather.data.HourForecast
import com.ana.simpleweather.data.WeatherCodes
import com.ana.simpleweather.data.WeatherData
import com.ana.simpleweather.data.hasLocationPermission
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

/** Content is capped at 640dp wide and centered, so it reads well on tablets too. */
private val ContentWidth = Modifier.widthIn(max = 640.dp).fillMaxWidth()

private fun Double.deg() = "${roundToInt()}°"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WeatherScreen(vm: WeatherViewModel, onOpenSettings: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.any { it }) vm.useDeviceLocation() else vm.onLocationPermissionDenied()
    }
    // Permission is only requested when the user asks to use their location.
    val requestLocation: () -> Unit = {
        if (hasLocationPermission(context)) vm.useDeviceLocation()
        else permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            state.placeName ?: "Simple Weather",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        state.lastUpdated?.let {
                            // Ticks every 30 s so "Updated 5 min ago" stays accurate.
                            val now by produceState(System.currentTimeMillis()) {
                                while (true) {
                                    delay(30_000)
                                    value = System.currentTimeMillis()
                                }
                            }
                            Text(
                                updatedText(context, it, now),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = vm::openSearch) {
                        Icon(Icons.Filled.Search, contentDescription = "Search for a city")
                    }
                    IconButton(onClick = requestLocation) {
                        Icon(Icons.Filled.LocationOn, contentDescription = "Use my location")
                    }
                    IconButton(onClick = vm::refresh, enabled = !state.loading) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                },
            )
        }
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.loading && state.weather != null,
            onRefresh = vm::refresh,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (state.loading) {
                    item { LinearProgressIndicator(ContentWidth) }
                }
                state.error?.let { message ->
                    item {
                        MessageCard(
                            message = if (state.isStale) "$message\nShowing the last saved forecast." else message,
                            onDismiss = vm::dismissError,
                        )
                    }
                }
                if (state.showWelcome && state.weather == null) {
                    item { WelcomeCard(onUseLocation = requestLocation, onSearch = vm::openSearch) }
                }
                val weather = state.weather
                if (weather != null) {
                    item { CurrentCard(weather) }
                    item { SectionTitle("Next 24 hours") }
                    item { HourlyRow(weather.hourly, is24h = DateFormat.is24HourFormat(context)) }
                    item { SectionTitle("16-day forecast") }
                    item { DailyCard(weather.daily) }
                    if (weather.daily.size > 7) {
                        item {
                            Text(
                                "Forecasts beyond about a week are less certain.",
                                modifier = ContentWidth,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                } else if (state.loading) {
                    item {
                        Box(ContentWidth.padding(32.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                }
                item {
                    Text(
                        "Weather data by Open-Meteo.com",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    if (state.showSearch) {
        CitySearchDialog(
            searching = state.searching,
            results = state.searchResults,
            error = state.searchError,
            onSearch = vm::search,
            onSelect = vm::selectCity,
            onDismiss = vm::closeSearch,
        )
    }
}

/** "Updated just now", "Updated 12 min ago", "Updated 3:05 PM", or with a date if not today. */
private fun updatedText(context: Context, at: Long, now: Long): String {
    val minutes = (now - at) / 60_000
    val time = DateFormat.getTimeFormat(context).format(Date(at))
    return when {
        minutes < 1 -> "Updated just now"
        minutes < 60 -> "Updated $minutes min ago"
        DateUtils.isToday(at) -> "Updated $time"
        else -> "Updated ${DateFormat.getMediumDateFormat(context).format(Date(at))}, $time"
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        modifier = ContentWidth,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun MessageCard(message: String, onDismiss: () -> Unit) {
    Card(
        modifier = ContentWidth,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Row(Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
            Spacer(Modifier.width(12.dp))
            Text(
                message,
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onErrorContainer,
                style = MaterialTheme.typography.bodyMedium,
            )
            IconButton(onClick = onDismiss) {
                Icon(Icons.Filled.Close, contentDescription = "Dismiss", tint = MaterialTheme.colorScheme.onErrorContainer)
            }
        }
    }
}

@Composable
private fun WelcomeCard(onUseLocation: () -> Unit, onSearch: () -> Unit) {
    Card(ContentWidth) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Welcome! 🌤️", style = MaterialTheme.typography.titleLarge)
            Text(
                "To show local weather, the app can use your approximate (city-level) location. " +
                    "It's only checked when you open or refresh the app. You can also search for a city instead.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(onClick = onUseLocation, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.LocationOn, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Use my location")
            }
            OutlinedButton(onClick = onSearch, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Search, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Search for a city")
            }
        }
    }
}

@Composable
private fun CurrentCard(w: WeatherData) {
    Card(
        modifier = ContentWidth,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(WeatherCodes.emoji(w.weatherCode, w.isDay), fontSize = 56.sp)
            Text(
                "${w.temperature.roundToInt()}${w.tempUnit}",
                style = MaterialTheme.typography.displayLarge,
                fontWeight = FontWeight.Light,
            )
            Text(WeatherCodes.describe(w.weatherCode), style = MaterialTheme.typography.titleMedium)
            Text(
                "Feels like ${w.feelsLike.deg()}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            w.nowcast?.let {
                Text(
                    "☔ $it",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.padding(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Stat("Humidity", "${w.humidity.roundToInt()}%")
                Stat("Wind", "${w.windSpeed.roundToInt()} ${w.windUnit}")
                Stat("Rain chance", w.precipitationChance?.let { "${it.roundToInt()}%" } ?: "–")
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun HourlyRow(hours: List<HourForecast>, is24h: Boolean) {
    val formatter = DateTimeFormatter.ofPattern(if (is24h) "HH:mm" else "h a", Locale.getDefault())
    LazyRow(modifier = ContentWidth, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(hours, key = { it.time.toString() }) { h ->
            Card {
                Column(
                    Modifier
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                        .widthIn(min = 52.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        if (h == hours.first()) "Now" else h.time.format(formatter),
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Text(WeatherCodes.emoji(h.weatherCode, h.time.hour in 6..17), fontSize = 24.sp)
                    Text(h.temperature.deg(), style = MaterialTheme.typography.titleMedium)
                    Text(
                        h.precipitationChance?.let { "💧${it.roundToInt()}%" } ?: "",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun DailyCard(days: List<DayForecast>) {
    // Weekday plus day of month ("Tue 25"), since 16 days repeat weekdays.
    val dayFormatter = DateTimeFormatter.ofPattern("EEE d", Locale.getDefault())
    Card(ContentWidth) {
        Column(Modifier.padding(vertical = 4.dp)) {
            days.forEachIndexed { index, d ->
                if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (index == 0) "Today" else d.date.format(dayFormatter),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(WeatherCodes.emoji(d.weatherCode), fontSize = 22.sp)
                    Text(
                        d.precipitationChance?.let { "💧${it.roundToInt()}%" } ?: "",
                        modifier = Modifier.width(64.dp),
                        textAlign = TextAlign.End,
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Text(
                        "${d.max.deg()} / ${d.min.deg()}",
                        modifier = Modifier.width(96.dp),
                        textAlign = TextAlign.End,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
        }
    }
}

@Composable
private fun CitySearchDialog(
    searching: Boolean,
    results: List<GeoResult>,
    error: String?,
    onSearch: (String) -> Unit,
    onSelect: (GeoResult) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Search for a city") },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("City name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onSearch(query) }),
                    trailingIcon = {
                        IconButton(onClick = { onSearch(query) }) {
                            Icon(Icons.Filled.Search, contentDescription = "Search")
                        }
                    },
                )
                if (searching) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (error != null) {
                    Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                Column(
                    Modifier
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    results.forEach { r ->
                        ListItem(
                            headlineContent = { Text(r.name) },
                            supportingContent = { if (r.region.isNotEmpty()) Text(r.region) },
                            modifier = Modifier.clickable { onSelect(r) },
                        )
                    }
                }
            }
        },
    )
}
