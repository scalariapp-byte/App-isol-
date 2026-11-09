package com.isolapp.manager

import android.app.Activity
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast

/**
 * Côté personnel : demande à l'espace isolé de prendre une copie de l'appli,
 * puis ouvre la désinstallation de l'exemplaire personnel (confirmée par l'utilisateur).
 */
class IsolateActivity : Activity() {
    private lateinit var pkg: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pkg = intent.getStringExtra(Iso.EXTRA_PKG) ?: run { finish(); return }
        val notifId = intent.getIntExtra(Iso.EXTRA_NOTIF_ID, -1)
        if (notifId >= 0) getSystemService(NotificationManager::class.java).cancel(notifId)
        if (savedInstanceState != null) return
        try {
            startActivityForResult(Intent(Iso.ACTION_CLONE).putExtra(Iso.EXTRA_PKG, pkg), REQ_CLONE)
        } catch (e: ActivityNotFoundException) {
            if (Shell.granted()) {
                Thread {
                    val moved = Mover.silentIsolate(this, pkg)
                    runOnUiThread {
                        Toast.makeText(this, if (moved) "Mise à l'abri ✓" else "Échec : ouvre l'onglet Diagnostic", Toast.LENGTH_LONG).show()
                        finish()
                    }
                }.start()
                return
            }
            val ok = Iso.startViaCrossProfileApps(this, Iso.ACTION_CLONE, Bundle().apply { putString(Iso.EXTRA_PKG, pkg) })
            Toast.makeText(
                this, if (ok) "Copiée dans l'espace isolé" else "Liaison avec l'espace cassée : ouvre l'onglet Diagnostic",
                Toast.LENGTH_LONG
            ).show()
            finish()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_CLONE) return
        if (resultCode == RESULT_OK) {
            Toast.makeText(
                this, "Copiée dans l'espace isolé. Confirmez la suppression de l'exemplaire personnel.",
                Toast.LENGTH_LONG
            ).show()
            runCatching {
                startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg")))
            }
        } else {
            Toast.makeText(this, "Échec de la copie dans l'espace isolé", Toast.LENGTH_LONG).show()
        }
        finish()
    }

    companion object {
        private const val REQ_CLONE = 10

        fun intent(ctx: Context, pkg: String, notifId: Int = -1): Intent =
            Intent(ctx, IsolateActivity::class.java)
                .putExtra(Iso.EXTRA_PKG, pkg)
                .putExtra(Iso.EXTRA_NOTIF_ID, notifId)
    }
}
