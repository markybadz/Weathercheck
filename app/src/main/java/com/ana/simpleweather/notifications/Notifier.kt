package com.ana.simpleweather.notifications

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.ana.simpleweather.MainActivity
import com.ana.simpleweather.R

object Notifier {
    const val CHANNEL_DAILY = "daily_weather"
    const val CHANNEL_SEVERE = "severe_weather"
    const val ID_DAILY = 1001
    const val ID_SEVERE = 1002

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_DAILY,
                "Daily weather update",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = "A short forecast once a day at the time you choose." }
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SEVERE,
                "Severe weather warnings",
                NotificationManager.IMPORTANCE_HIGH
            ).apply { description = "Thunderstorms, heavy rain or snow, and strong winds in the forecast." }
        )
    }

    fun canNotify(context: Context): Boolean {
        val permissionOk = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        return permissionOk && NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    @SuppressLint("MissingPermission") // checked by canNotify()
    fun show(context: Context, id: Int, channel: String, title: String, text: String) {
        if (!canNotify(context)) return
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            context, id, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(
                if (channel == CHANNEL_SEVERE) NotificationCompat.PRIORITY_HIGH
                else NotificationCompat.PRIORITY_DEFAULT
            )
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(id, notification)
    }
}
