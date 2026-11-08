package com.isolapp.manager

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Iso.syncComponents(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannels(
            listOf(
                NotificationChannel(CH_MONITOR, getString(R.string.ch_monitor), NotificationManager.IMPORTANCE_MIN),
                NotificationChannel(CH_NEW_APP, getString(R.string.ch_new_app), NotificationManager.IMPORTANCE_HIGH),
                NotificationChannel(CH_FREEZE, getString(R.string.ch_freeze), NotificationManager.IMPORTANCE_LOW),
            )
        )
    }

    companion object {
        const val CH_MONITOR = "monitor"
        const val CH_NEW_APP = "new_app"
        const val CH_FREEZE = "freeze"
    }
}
