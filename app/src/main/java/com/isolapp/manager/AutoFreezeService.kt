package com.isolapp.manager

import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Côté isolé : tourne tant qu'une appli « démarrage auto bloqué » est temporairement dégelée.
 * À l'extinction de l'écran (ou à l'arrêt du téléphone) elle est regelée, donc elle ne pourra pas
 * se lancer au prochain démarrage.
 */
class AutoFreezeService : Service() {

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = freezeAndStop()
    }

    override fun onCreate() {
        super.onCreate()
        val freezeNow = PendingIntent.getService(
            this, 0, Intent(this, AutoFreezeService::class.java).setAction(ACTION_FREEZE_NOW),
            PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(this, App.CH_FREEZE)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("Applis isolées dégelées")
            .setContentText("Elles seront regelées à l'extinction de l'écran")
            .setOngoing(true)
            .addAction(0, "Geler maintenant", freezeNow)
            .build()
        ServiceCompat.startForeground(
            this, NOTIF_ID, n,
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        )
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SHUTDOWN)
        }
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_FREEZE_NOW) freezeAndStop()
        return START_STICKY
    }

    private fun freezeAndStop() {
        Freezer.freezeBlocked(this)
        stopSelf()
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(receiver) }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val NOTIF_ID = 2
        private const val ACTION_FREEZE_NOW = "freeze_now"

        fun start(ctx: Context) = runCatching {
            ContextCompat.startForegroundService(ctx, Intent(ctx, AutoFreezeService::class.java))
        }
    }
}
