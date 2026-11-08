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
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Côté personnel : écoute les nouvelles installations (Play Store, APK, autre magasin…)
 * et propose immédiatement de placer l'appli dans l'espace isolé.
 * Android interdit l'écoute de PACKAGE_ADDED en arrière-plan sans service : d'où ce service discret.
 */
class MonitorService : Service() {

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return
            val pkg = i.data?.schemeSpecificPart ?: return
            if (pkg == packageName) return
            notifyNewApp(c, pkg)
        }
    }

    override fun onCreate() {
        super.onCreate()
        val n = NotificationCompat.Builder(this, App.CH_MONITOR)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("Surveillance des installations active")
            .setContentText("Les nouvelles applis vous seront proposées pour l'isolation")
            .setOngoing(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()
        ServiceCompat.startForeground(
            this, NOTIF_ID, n,
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        )
        val filter = IntentFilter(Intent.ACTION_PACKAGE_ADDED).apply { addDataScheme("package") }
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onDestroy() {
        runCatching { unregisterReceiver(receiver) }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val NOTIF_ID = 1

        fun start(ctx: Context) = runCatching {
            ContextCompat.startForegroundService(ctx, Intent(ctx, MonitorService::class.java))
        }

        fun stop(ctx: Context) = ctx.stopService(Intent(ctx, MonitorService::class.java))

        fun notifyNewApp(ctx: Context, pkg: String) {
            val pm = ctx.packageManager
            val label = runCatching {
                pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
            }.getOrDefault(pkg)
            val id = 1000 + (pkg.hashCode() and 0xFFFF)
            val isolate = PendingIntent.getActivity(
                ctx, id, IsolateActivity.intent(ctx, pkg, id),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val ignore = PendingIntent.getBroadcast(
                ctx, id, Intent(ctx, NotificationActionReceiver::class.java).putExtra(Iso.EXTRA_NOTIF_ID, id),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val n = NotificationCompat.Builder(ctx, App.CH_NEW_APP)
                .setSmallIcon(R.drawable.ic_notif)
                .setContentTitle("$label vient d'être installée")
                .setContentText("La placer dans l'espace isolé ?")
                .setAutoCancel(true)
                .setContentIntent(isolate)
                .addAction(0, ctx.getString(R.string.isolate), isolate)
                .addAction(0, ctx.getString(R.string.ignore), ignore)
                .build()
            runCatching { NotificationManagerCompat.from(ctx).notify(id, n) }
        }
    }
}

class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra(Iso.EXTRA_NOTIF_ID, -1)
        if (id >= 0) NotificationManagerCompat.from(context).cancel(id)
    }
}
