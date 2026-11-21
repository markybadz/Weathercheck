package com.ana.simpleweather.notifications

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.ana.simpleweather.data.AppSettings
import java.time.Duration
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

object WorkScheduler {
    private const val DAILY_WORK = "daily_weather_update"
    private const val SEVERE_WORK = "severe_weather_check"

    private val needsNetwork = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    /**
     * Makes the scheduled background work match the settings.
     * @param rescheduleDaily true when the user changed the daily time/toggle.
     */
    fun apply(context: Context, settings: AppSettings, rescheduleDaily: Boolean) {
        val wm = WorkManager.getInstance(context)

        if (settings.dailyEnabled) {
            val request = PeriodicWorkRequestBuilder<DailyWeatherWorker>(1, TimeUnit.DAYS)
                .setInitialDelay(
                    millisUntil(settings.dailyHour, settings.dailyMinute),
                    TimeUnit.MILLISECONDS
                )
                .setConstraints(needsNetwork)
                .build()
            wm.enqueueUniquePeriodicWork(
                DAILY_WORK,
                if (rescheduleDaily) ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE
                else ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        } else {
            wm.cancelUniqueWork(DAILY_WORK)
        }

        if (settings.severeEnabled) {
            val request = PeriodicWorkRequestBuilder<SevereWeatherWorker>(3, TimeUnit.HOURS)
                .setConstraints(needsNetwork)
                .build()
            wm.enqueueUniquePeriodicWork(SEVERE_WORK, ExistingPeriodicWorkPolicy.KEEP, request)
        } else {
            wm.cancelUniqueWork(SEVERE_WORK)
        }
    }

    private fun millisUntil(hour: Int, minute: Int): Long {
        val now = ZonedDateTime.now()
        var next = now.withHour(hour).withMinute(minute).withSecond(0).withNano(0)
        if (!next.isAfter(now)) next = next.plusDays(1)
        return Duration.between(now, next).toMillis()
    }
}
