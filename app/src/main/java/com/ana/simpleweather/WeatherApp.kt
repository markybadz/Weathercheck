package com.ana.simpleweather

import android.app.Application
import com.ana.simpleweather.notifications.Notifier

class WeatherApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifier.createChannels(this)
    }
}
