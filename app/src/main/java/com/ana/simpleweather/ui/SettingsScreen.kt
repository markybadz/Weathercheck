package com.ana.simpleweather.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.format.DateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ana.simpleweather.data.TempUnit
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private enum class NotifyKind { DAILY, SEVERE }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: WeatherViewModel, onBack: () -> Unit) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val is24h = DateFormat.is24HourFormat(context)

    var pending by remember { mutableStateOf<NotifyKind?>(null) }
    var showBlockedHint by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }

    fun turnOn(kind: NotifyKind) = when (kind) {
        NotifyKind.DAILY -> vm.setDailyEnabled(true)
        NotifyKind.SEVERE -> vm.setSevereEnabled(true)
    }

    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val kind = pending
        pending = null
        if (granted && kind != null) {
            showBlockedHint = false
            turnOn(kind)
        } else {
            showBlockedHint = true
        }
    }

    // Android 13+ needs the POST_NOTIFICATIONS permission; ask only when a toggle is switched on.
    fun enable(kind: NotifyKind) {
        val needsPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        if (needsPermission) {
            pending = kind
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            showBlockedHint = !NotificationManagerCompat.from(context).areNotificationsEnabled()
            turnOn(kind)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                Modifier.widthIn(max = 640.dp).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Header("Units")
                UnitOption(
                    "Celsius (°C), wind in km/h",
                    selected = settings.unit == TempUnit.CELSIUS,
                ) { vm.setUnit(TempUnit.CELSIUS) }
                UnitOption(
                    "Fahrenheit (°F), wind in mph",
                    selected = settings.unit == TempUnit.FAHRENHEIT,
                ) { vm.setUnit(TempUnit.FAHRENHEIT) }

                Header("Notifications")
                val timeText = LocalTime.of(settings.dailyHour, settings.dailyMinute)
                    .format(DateTimeFormatter.ofPattern(if (is24h) "HH:mm" else "h:mm a", Locale.getDefault()))
                SwitchRow(
                    title = "Daily weather update",
                    subtitle = "A short forecast every day around $timeText",
                    checked = settings.dailyEnabled,
                ) { on -> if (on) enable(NotifyKind.DAILY) else vm.setDailyEnabled(false) }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { showTimePicker = true }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Notification time", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    Text(timeText, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                }
                SwitchRow(
                    title = "Severe weather warnings",
                    subtitle = "Checks the forecast about every 3 hours for thunderstorms, heavy rain or snow, and strong winds",
                    checked = settings.severeEnabled,
                ) { on -> if (on) enable(NotifyKind.SEVERE) else vm.setSevereEnabled(false) }
                SwitchRow(
                    title = "Quiet at night",
                    subtitle = "Severe weather warnings between 10 PM and 6 AM arrive without sound or vibration",
                    checked = settings.quietAtNight,
                ) { on -> vm.setQuietAtNight(on) }

                if (showBlockedHint) {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                "Notifications are blocked for this app, so updates can't be shown. " +
                                    "You can allow them in your phone's settings.",
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                            Button(onClick = {
                                context.startActivity(
                                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            }) { Text("Open notification settings") }
                        }
                    }
                }

                Header("How background updates work")
                InfoText(
                    "• Notifications use the place you last viewed in the app. The app never tracks " +
                        "your location in the background, so it only needs \"while using the app\" location permission."
                )
                InfoText(
                    "• To save battery, Android groups background tasks together, so a daily update can arrive " +
                        "a few minutes late. Battery saver or \"restricted\" battery settings may delay it more."
                )
                InfoText(
                    "• Severe weather warnings are worked out from the forecast. They are NOT official government " +
                        "warnings. Always follow your national weather service (for example, PAGASA in the Philippines)."
                )
                OutlinedButton(onClick = {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse("https://www.pagasa.dost.gov.ph/"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }) { Text("Open PAGASA weather advisories") }
                InfoText("• Weather data: Open-Meteo.com (free, no account needed).")
            }
        }
    }

    if (showTimePicker) {
        val pickerState = rememberTimePickerState(
            initialHour = settings.dailyHour,
            initialMinute = settings.dailyMinute,
            is24Hour = is24h,
        )
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            title = { Text("Daily update time") },
            text = { TimePicker(state = pickerState) },
            confirmButton = {
                TextButton(onClick = {
                    vm.setDailyTime(pickerState.hour, pickerState.minute)
                    showTimePicker = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showTimePicker = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun Header(text: String) {
    Text(
        text,
        modifier = Modifier.padding(top = 8.dp),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun UnitOption(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect, role = Role.RadioButton)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun InfoText(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
