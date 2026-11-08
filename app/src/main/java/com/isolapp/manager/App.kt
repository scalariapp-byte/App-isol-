package com.isolapp.manager

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // Couleurs Material You tirées du fond d'écran (Android 12+).
        com.google.android.material.color.DynamicColors.applyToActivitiesIfAvailable(this)
        Iso.syncComponents(this)
        val nm = getSystemService(NotificationManager::class.java)
        // Canaux discrets : priorité minimale = pas d'icône dans la barre d'état.
        runCatching { nm.deleteNotificationChannel("monitor") }
        nm.createNotificationChannels(
            listOf(
                NotificationChannel(CH_MONITOR, getString(R.string.ch_monitor), NotificationManager.IMPORTANCE_MIN),
                NotificationChannel(CH_NEW_APP, getString(R.string.ch_new_app), NotificationManager.IMPORTANCE_DEFAULT),
                NotificationChannel(CH_FREEZE, getString(R.string.ch_freeze), NotificationManager.IMPORTANCE_MIN),
            )
        )
    }

    companion object {
        const val CH_MONITOR = "monitor_silent"
        const val CH_NEW_APP = "new_app"
        const val CH_FREEZE = "freeze"
    }
}
