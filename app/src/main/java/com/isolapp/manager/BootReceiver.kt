package com.isolapp.manager

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (Iso.isIsolatedSide(context)) {
            Iso.ensureIsolatedSetup(context)
            // Filet de sécurité : si le téléphone s'est éteint sans regel, on regèle tout de suite.
            Freezer.freezeBlocked(context)
        } else {
            Iso.syncComponents(context)
            if (Prefs.monitor(context)) MonitorService.start(context)
        }
    }
}
