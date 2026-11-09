package com.isolapp.manager

import android.app.Activity
import android.content.Intent
import android.net.Uri
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.net.HttpURLConnection
import java.net.URL

/** Vérifie sur GitHub si une version plus récente est publiée. */
object Updater {
    private const val API = "https://api.github.com/repos/scalariapp-byte/App-isol-/releases/tags/latest"
    private const val APK = "https://github.com/scalariapp-byte/App-isol-/releases/download/latest/Isolateur.apk"
    private var lastCheck = 0L

    fun check(activity: Activity, manual: Boolean = false) {
        // Vérifie à chaque retour dans l'appli (au plus une fois toutes les 2 min, limite de l'API GitHub).
        val now = System.currentTimeMillis()
        if (!manual && now - lastCheck < 120_000) return
        lastCheck = now
        fun info(msg: String) { if (manual) activity.runOnUiThread { android.widget.Toast.makeText(activity, msg, android.widget.Toast.LENGTH_SHORT).show() } }
        Thread {
            val remote = runCatching {
                val c = URL(API).openConnection() as HttpURLConnection
                c.connectTimeout = 8000; c.readTimeout = 8000
                c.setRequestProperty("Accept", "application/vnd.github+json")
                val body = c.inputStream.bufferedReader().use { it.readText() }
                Regex("versionCode=(\\d+)").find(body)?.groupValues?.get(1)?.toLong()
            }.getOrNull() ?: return@Thread info("Impossible de vérifier (pas de connexion ?)")
            val local = activity.packageManager.getPackageInfo(activity.packageName, 0).longVersionCode
            // versionCode publié = numéro de build ; versionCode installé = 100 + numéro de build
            if (100 + remote <= local) return@Thread info("Tu as la dernière version ✓")
            activity.runOnUiThread {
                if (activity.isFinishing) return@runOnUiThread
                MaterialAlertDialogBuilder(activity)
                    .setTitle("Mise à jour disponible")
                    .setMessage("La version 1.$remote est disponible (installée : 1.${local - 100}).")
                    .setPositiveButton("Télécharger") { _, _ ->
                        runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(APK))) }
                    }
                    .setNegativeButton("Plus tard", null)
                    .show()
            }
        }.start()
    }
}
