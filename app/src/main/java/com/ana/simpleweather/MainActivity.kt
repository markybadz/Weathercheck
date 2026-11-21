package com.ana.simpleweather

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ana.simpleweather.ui.SettingsScreen
import com.ana.simpleweather.ui.WeatherScreen
import com.ana.simpleweather.ui.WeatherViewModel
import com.ana.simpleweather.ui.theme.SimpleWeatherTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SimpleWeatherTheme {
                AppRoot()
            }
        }
    }
}

@Composable
private fun AppRoot(vm: WeatherViewModel = viewModel()) {
    var showSettings by rememberSaveable { mutableStateOf(false) }
    if (showSettings) {
        BackHandler { showSettings = false }
        SettingsScreen(vm = vm, onBack = { showSettings = false })
    } else {
        WeatherScreen(vm = vm, onOpenSettings = { showSettings = true })
    }
}
