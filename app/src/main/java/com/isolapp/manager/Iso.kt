package com.isolapp.manager

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.CrossProfileApps
import android.content.pm.PackageManager
import android.os.UserHandle
import android.os.UserManager

/** Accès au profil isolé (profil professionnel Android dont l'appli est propriétaire). */
object Iso {
    const val ACTION_CLONE = "com.isolapp.manager.action.CLONE"
    const val ACTION_INSTALL_APK = "com.isolapp.manager.action.INSTALL_APK"
    const val EXTRA_PKG = "pkg"
    const val EXTRA_NOTIF_ID = "notif_id"

    fun admin(ctx: Context) = ComponentName(ctx, AdminReceiver::class.java)

    fun dpm(ctx: Context): DevicePolicyManager =
        ctx.getSystemService(DevicePolicyManager::class.java)

    /** Vrai si ce processus tourne dans l'espace isolé (on y est propriétaire du profil). */
    fun isIsolatedSide(ctx: Context): Boolean = dpm(ctx).isProfileOwnerApp(ctx.packageName)

    /** Depuis le profil personnel : handle du profil isolé où notre appli est installée. */
    fun isolatedProfile(ctx: Context): UserHandle? =
        runCatching {
            ctx.getSystemService(CrossProfileApps::class.java).targetUserProfiles.firstOrNull()
        }.getOrNull()

    /** Configuration idempotente du profil isolé ; à appeler uniquement côté isolé. */
    fun ensureIsolatedSetup(ctx: Context) {
        if (!isIsolatedSide(ctx)) return
        val dpm = dpm(ctx)
        val admin = admin(ctx)
        runCatching { dpm.setProfileName(admin, ctx.getString(R.string.profile_name)) }
        runCatching { dpm.setProfileEnabled(admin) }
        runCatching { dpm.clearUserRestriction(admin, UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES) }
        runCatching {
            dpm.clearCrossProfileIntentFilters(admin)
            val filter = IntentFilter().apply {
                addAction(ACTION_CLONE)
                addAction(ACTION_INSTALL_APK)
                addCategory(Intent.CATEGORY_DEFAULT)
            }
            dpm.addCrossProfileIntentFilter(admin, filter, DevicePolicyManager.FLAG_PARENT_CAN_ACCESS_MANAGED)
        }
        syncComponents(ctx)
    }

    /**
     * WorkActionActivity ne doit exister que côté isolé : côté personnel on la désactive,
     * sinon Android proposerait un choix « Personnel / Isolé » au lieu de transférer la requête.
     */
    fun syncComponents(ctx: Context) {
        val state = if (isIsolatedSide(ctx)) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        runCatching {
            ctx.packageManager.setComponentEnabledSetting(
                ComponentName(ctx, WorkActionActivity::class.java), state, PackageManager.DONT_KILL_APP
            )
        }
    }
}

object Prefs {
    private fun sp(ctx: Context) = ctx.getSharedPreferences("iso", Context.MODE_PRIVATE)

    fun monitor(ctx: Context) = sp(ctx).getBoolean("monitor", false)
    fun setMonitor(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("monitor", v).apply()

    /** Applis isolées dont le démarrage automatique est bloqué (gardées gelées). */
    fun blocked(ctx: Context): Set<String> = HashSet(sp(ctx).getStringSet("blocked", emptySet()) ?: emptySet())
    fun setBlocked(ctx: Context, pkg: String, blocked: Boolean) {
        val s = blocked(ctx).toMutableSet()
        if (blocked) s.add(pkg) else s.remove(pkg)
        sp(ctx).edit().putStringSet("blocked", s).apply()
    }
}

/** Gel = application masquée par le propriétaire du profil : elle ne peut plus rien exécuter. */
object Freezer {
    fun isFrozen(ctx: Context, pkg: String): Boolean =
        runCatching { Iso.dpm(ctx).isApplicationHidden(Iso.admin(ctx), pkg) }.getOrDefault(false)

    fun setFrozen(ctx: Context, pkg: String, frozen: Boolean): Boolean {
        if (pkg == ctx.packageName) return false
        return runCatching { Iso.dpm(ctx).setApplicationHidden(Iso.admin(ctx), pkg, frozen) }.getOrDefault(false)
    }

    /** Regèle toutes les applis dont le démarrage auto est bloqué. Renvoie le nombre gelé. */
    fun freezeBlocked(ctx: Context): Int =
        Prefs.blocked(ctx).count { !isFrozen(ctx, it) && setFrozen(ctx, it, true) }
}
