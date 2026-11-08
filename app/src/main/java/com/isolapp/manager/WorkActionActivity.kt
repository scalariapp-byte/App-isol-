package com.isolapp.manager

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat

/**
 * S'exécute dans l'espace isolé. Reçoit les requêtes du profil personnel :
 *  - CLONE : rend disponible dans l'espace isolé une appli déjà installée côté personnel ;
 *  - INSTALL_APK : choisit un fichier APK et l'installe directement dans l'espace isolé.
 */
class WorkActionActivity : Activity() {

    private var resultReceiver: BroadcastReceiver? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!Iso.isIsolatedSide(this)) {
            setResult(RESULT_CANCELED); finish(); return
        }
        if (savedInstanceState != null) return
        when (intent.action) {
            Iso.ACTION_CLONE -> clone(intent.getStringExtra(Iso.EXTRA_PKG))
            Iso.ACTION_INSTALL_APK -> pickApk()
            Iso.ACTION_OPEN_MANAGER -> {
                startActivity(Intent(this, IsolatedActivity::class.java))
                finish()
            }
            Iso.ACTION_OPEN_STORE -> {
                Iso.ensureIsolatedSetup(this)
                val i = packageManager.getLaunchIntentForPackage(Iso.PLAY_STORE)
                if (i != null) startActivity(i)
                else Toast.makeText(this, "Play Store absent de l'espace isolé", Toast.LENGTH_LONG).show()
                finish()
            }
            else -> finish()
        }
    }

    private fun clone(pkg: String?) {
        val ok = pkg != null && runCatching {
            Iso.dpm(this).installExistingPackage(Iso.admin(this), pkg)
        }.getOrDefault(false)
        if (ok) Freezer.setFrozen(this, pkg!!, false)
        setResult(if (ok) RESULT_OK else RESULT_CANCELED)
        finish()
    }

    private fun pickApk() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("application/vnd.android.package-archive")
        runCatching { startActivityForResult(i, REQ_PICK) }.onFailure {
            Toast.makeText(this, "Aucun sélecteur de fichiers disponible", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode != REQ_PICK || resultCode != RESULT_OK || uri == null) {
            finish(); return
        }
        install(uri)
    }

    private fun install(uri: Uri) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                when (i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                    PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                        // Installation non silencieuse : Android demande une confirmation.
                        IntentCompat.getParcelableExtra(i, Intent.EXTRA_INTENT, Intent::class.java)
                            ?.let { runCatching { startActivity(it) } }
                    }
                    PackageInstaller.STATUS_SUCCESS -> {
                        Toast.makeText(c, "Installée dans l'espace isolé", Toast.LENGTH_LONG).show()
                        finish()
                    }
                    else -> {
                        val msg = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "erreur inconnue"
                        Toast.makeText(c, "Échec de l'installation : $msg", Toast.LENGTH_LONG).show()
                        finish()
                    }
                }
            }
        }
        resultReceiver = receiver
        ContextCompat.registerReceiver(
            this, receiver, IntentFilter(ACTION_INSTALL_RESULT), ContextCompat.RECEIVER_NOT_EXPORTED
        )
        Toast.makeText(this, "Installation en cours…", Toast.LENGTH_SHORT).show()

        Thread {
            val error = runCatching {
                val installer = packageManager.packageInstaller
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                val id = installer.createSession(params)
                installer.openSession(id).use { session ->
                    contentResolver.openInputStream(uri)!!.use { input ->
                        session.openWrite("base.apk", 0, -1).use { out ->
                            input.copyTo(out)
                            session.fsync(out)
                        }
                    }
                    val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                        (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                    val pi = PendingIntent.getBroadcast(
                        this, id, Intent(ACTION_INSTALL_RESULT).setPackage(packageName), flags
                    )
                    session.commit(pi.intentSender)
                }
            }.exceptionOrNull()
            if (error != null) runOnUiThread {
                Toast.makeText(this, "Échec : ${error.message}", Toast.LENGTH_LONG).show()
                finish()
            }
        }.start()
    }

    override fun onDestroy() {
        resultReceiver?.let { runCatching { unregisterReceiver(it) } }
        super.onDestroy()
    }

    companion object {
        private const val REQ_PICK = 1
        private const val ACTION_INSTALL_RESULT = "com.isolapp.manager.INSTALL_RESULT"
    }
}
