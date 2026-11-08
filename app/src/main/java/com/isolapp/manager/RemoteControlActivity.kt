package com.isolapp.manager

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle

/**
 * API pour une autre appli (protégée par la permission CONTROL, accordée par l'utilisateur) :
 *   action  com.isolapp.manager.action.SET_BOOT
 *   extras  "pkg" (String), "blocked" (Boolean)
 *   résultat RESULT_OK, ou RESULT_CANCELED + extra "error" = "shizuku" | "critical" | "invalid" | "failed"
 * Bloquer = désactiver les récepteurs de démarrage de l'appli (pm disable, via Shizuku).
 */
class RemoteControlActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pkg = intent.getStringExtra("pkg")
        val blocked = intent.getBooleanExtra("blocked", true)
        when {
            intent.action != ACTION || pkg.isNullOrBlank() -> fail("invalid")
            Critical.isCritical(this, pkg) -> fail("critical")
            !Shell.granted() -> fail("shizuku")
            else -> Thread {
                val receivers = bootReceivers(pkg)
                val ok = receivers.isNotEmpty() && receivers.all { Shell.setComponentEnabled(pkg, it, 0, !blocked) }
                runOnUiThread { if (ok) { setResult(RESULT_OK); finish() } else fail("failed") }
            }.start()
        }
    }

    private fun bootReceivers(pkg: String): Set<String> {
        val flags = PackageManager.MATCH_DISABLED_COMPONENTS or PackageManager.MATCH_DIRECT_BOOT_AWARE or
            PackageManager.MATCH_DIRECT_BOOT_UNAWARE
        return BOOT_ACTIONS.flatMap { a ->
            runCatching { packageManager.queryBroadcastReceivers(Intent(a).setPackage(pkg), flags) }
                .getOrDefault(emptyList())
        }.mapNotNull { it.activityInfo?.let { ai -> ComponentName(ai.packageName, ai.name).className } }.toSet()
    }

    private fun fail(error: String) {
        setResult(RESULT_CANCELED, Intent().putExtra("error", error))
        finish()
    }

    companion object {
        const val ACTION = "com.isolapp.manager.action.SET_BOOT"
        private val BOOT_ACTIONS = listOf(
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_LOCKED_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON", "com.htc.intent.action.QUICKBOOT_POWERON",
        )
    }
}
