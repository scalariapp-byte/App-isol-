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
    const val ACTION_OPEN_MANAGER = "com.isolapp.manager.action.OPEN_MANAGER"
    const val ACTION_OPEN_STORE = "com.isolapp.manager.action.OPEN_STORE"
    const val ACTION_REMOVE_SPACE = "com.isolapp.manager.action.REMOVE_SPACE"
    const val PLAY_STORE = "com.android.vending"

    /** Paquets nécessaires au Play Store dans l'espace isolé. */
    private val STORE_PACKAGES = listOf(PLAY_STORE, "com.google.android.gms", "com.google.android.gsf")
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
        runCatching { dpm.setOrganizationName(admin, ctx.getString(R.string.profile_name)) }
        runCatching { dpm.setProfileEnabled(admin) }
        runCatching { dpm.clearUserRestriction(admin, UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES) }
        runCatching {
            dpm.clearCrossProfileIntentFilters(admin)
            val filter = IntentFilter().apply {
                addAction(ACTION_CLONE)
                addAction(ACTION_INSTALL_APK)
                addAction(ACTION_OPEN_MANAGER)
                addAction(ACTION_OPEN_STORE)
                addAction(ACTION_REMOVE_SPACE)
                addCategory(Intent.CATEGORY_DEFAULT)
            }
            dpm.addCrossProfileIntentFilter(admin, filter, DevicePolicyManager.FLAG_PARENT_CAN_ACCESS_MANAGED)
        }
        // Play Store disponible dans l'espace : ce qu'on y télécharge s'installe directement dedans.
        for (p in STORE_PACKAGES) {
            runCatching { dpm.installExistingPackage(admin, p) }
            if (p !in Prefs.removed(ctx)) runCatching { dpm.setApplicationHidden(admin, p, false) }
        }
        syncComponents(ctx)
    }

    /** Identifiant utilisateur Android du profil isolé (UserHandle.hashCode() == identifiant). */
    fun isolatedUserId(ctx: Context): Int? = isolatedProfile(ctx)?.hashCode()

    /**
     * WorkActionActivity ne doit exister que côté isolé : côté personnel on la désactive,
     * sinon Android proposerait un choix « Personnel / Isolé » au lieu de transférer la requête.
     */
    fun syncComponents(ctx: Context) {
        val iso = isIsolatedSide(ctx)
        val on = PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        val off = PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        fun set(cls: String, enabled: Boolean) = runCatching {
            ctx.packageManager.setComponentEnabledSetting(
                ComponentName(ctx.packageName, cls), if (enabled) on else off, PackageManager.DONT_KILL_APP
            )
        }
        set(WorkActionActivity::class.java.name, iso)
        // Discrétion : pas d'icône Isolateur dans l'espace isolé ; on le gère depuis le côté personnel.
        set("${ctx.packageName}.Launcher", !iso)
        set(HomeActivity::class.java.name, !iso)
    }
}

object Prefs {
    private fun sp(ctx: Context) = ctx.getSharedPreferences("iso", Context.MODE_PRIVATE)

    fun monitor(ctx: Context) = sp(ctx).getBoolean("monitor", false)
    fun defaultMode(ctx: Context) = sp(ctx).getBoolean("default_mode", false)
    fun setDefaultMode(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("default_mode", v).apply()

    /** Applis système supprimées de l'espace isolé (masquées définitivement, restaurables). */
    fun removed(ctx: Context): Set<String> = HashSet(sp(ctx).getStringSet("removed", emptySet()) ?: emptySet())
    fun setRemoved(ctx: Context, pkg: String, removed: Boolean) {
        val s = removed(ctx).toMutableSet()
        if (removed) s.add(pkg) else s.remove(pkg)
        sp(ctx).edit().putStringSet("removed", s).apply()
    }
    fun setMonitor(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("monitor", v).apply()
    /** Applis épinglées sur l'accueil et dans le dock (clés « utilisateur|composant »). */
    fun pins(ctx: Context): List<String> = list(ctx, "home_pins") ?: emptyList()
    fun setPins(ctx: Context, v: List<String>) = setList(ctx, "home_pins", v)
    fun dock(ctx: Context): List<String>? = list(ctx, "dock")
    fun setDock(ctx: Context, v: List<String>) = setList(ctx, "dock", v)
    private fun list(ctx: Context, k: String): List<String>? =
        sp(ctx).getString(k, null)?.split('\n')?.filter { it.isNotBlank() }
    private fun setList(ctx: Context, k: String, v: List<String>) =
        sp(ctx).edit().putString(k, v.joinToString("\n")).apply()

    fun widgets(ctx: Context): List<Int> = list(ctx, "widgets")?.mapNotNull { it.toIntOrNull() } ?: emptyList()
    fun setWidgets(ctx: Context, v: List<Int>) = setList(ctx, "widgets", v.map { it.toString() })

    fun pendingUninstall(ctx: Context) = sp(ctx).getBoolean("pending_uninstall", false)
    fun setPendingUninstall(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("pending_uninstall", v).apply()

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
