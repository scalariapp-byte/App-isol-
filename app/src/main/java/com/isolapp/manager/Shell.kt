package com.isolapp.manager

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.provider.Settings
import rikka.shizuku.Shizuku

/**
 * Commandes avec les droits ADB via Shizuku (sans root). Nécessaire pour :
 *  - supprimer des applis système du profil personnel (pm uninstall --user 0) ;
 *  - déplacer une appli vers l'espace isolé sans aucune confirmation ;
 *  - désactiver le Play Store personnel (mode par défaut).
 */
object Shell {
    const val SHIZUKU_PKG = "moe.shizuku.privileged.api"
    const val REQ_PERMISSION = 42

    private val PKG_RE = Regex("^[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)*$")

    fun installed(ctx: Context) =
        runCatching { ctx.packageManager.getPackageInfo(SHIZUKU_PKG, 0); true }.getOrDefault(false)

    fun running() = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    fun granted() = running() && runCatching {
        !Shizuku.isPreV11() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    fun request() = runCatching { Shizuku.requestPermission(REQ_PERMISSION) }

    /** Exécute une commande shell avec les droits ADB. Renvoie (code de sortie, sortie). */
    fun run(cmd: String): Pair<Int, String> = try {
        val m = Shizuku::class.java.getDeclaredMethod(
            "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java
        ).apply { isAccessible = true }
        val p = m.invoke(null, arrayOf("sh", "-c", "$cmd 2>&1"), null, null) as Process
        val out = p.inputStream.bufferedReader().readText().trim()
        p.waitFor() to out
    } catch (e: Throwable) {
        -1 to (e.cause?.message ?: e.message ?: "erreur")
    }

    private fun ok(cmd: String) = run(cmd).let { (code, out) -> code == 0 && !out.contains("Failure", true) }

    private fun valid(pkg: String) = PKG_RE.matches(pkg)

    fun uninstallForUser(pkg: String, user: Int, keepData: Boolean = true) =
        valid(pkg) && ok("pm uninstall ${if (keepData) "-k " else ""}--user $user $pkg")

    fun installExisting(pkg: String, user: Int) = valid(pkg) && ok("pm install-existing --user $user $pkg")

    private val CLS_RE = Regex("^[A-Za-z0-9_.$]+$")

    /** Active/désactive un composant précis (ex. récepteur de démarrage) d'une appli. */
    fun setComponentEnabled(pkg: String, cls: String, user: Int, enabled: Boolean) =
        valid(pkg) && CLS_RE.matches(cls) &&
            ok("pm ${if (enabled) "enable" else "disable"} --user $user '$pkg/$cls'")

    fun setEnabled(pkg: String, user: Int, enabled: Boolean) =
        valid(pkg) && ok(if (enabled) "pm enable --user $user $pkg" else "pm disable-user --user $user $pkg")
}

/** Paquets dont la suppression peut rendre le téléphone inutilisable (boucle de démarrage, plus d'écran…). */
object Critical {
    private val EXACT = setOf(
        "android", "com.android.systemui", "com.android.settings", "com.android.phone", "com.android.shell",
        "com.android.packageinstaller", "com.google.android.packageinstaller",
        "com.android.permissioncontroller", "com.google.android.permissioncontroller",
        "com.android.externalstorage", "com.android.documentsui", "com.google.android.documentsui",
        "com.android.server.telecom", "com.android.bluetooth", "com.android.nfc", "com.android.se",
        "com.android.location.fused", "com.android.ext.services", "com.google.android.ext.services",
        "com.android.keychain", "com.android.certinstaller", "com.android.managedprovisioning",
        "com.android.inputdevices", "com.android.wifi.resources", "com.google.android.gms",
        "com.google.android.gsf", Shell.SHIZUKU_PKG,
    )
    private val PREFIXES = listOf(
        "com.android.providers.", "com.android.networkstack", "com.google.android.networkstack",
        "com.android.wifi", "com.android.cellbroadcast", "com.google.android.cellbroadcast",
        "com.android.modulemetadata", "com.google.android.modulemetadata", "com.android.adservices",
        "com.android.ondevicepersonalization", "com.android.connectivity", "com.android.tethering",
    )

    fun isCritical(ctx: Context, pkg: String): Boolean {
        if (pkg == ctx.packageName || pkg in EXACT || PREFIXES.any { pkg.startsWith(it) }) return true
        val home = runCatching {
            ctx.packageManager.resolveActivity(
                android.content.Intent(android.content.Intent.ACTION_MAIN)
                    .addCategory(android.content.Intent.CATEGORY_HOME), 0
            )?.activityInfo?.packageName
        }.getOrNull()
        val ime = runCatching {
            Settings.Secure.getString(ctx.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
                ?.substringBefore('/')
        }.getOrNull()
        return pkg == home || pkg == ime
    }

    fun isSystem(ai: ApplicationInfo) = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0
}

/** Déplacement d'une appli personnelle vers l'espace isolé. */
object Mover {
    // Gemini et l'appli Google refusent de fonctionner dans un profil professionnel.
    val NEVER_MOVE = setOf(
        Shell.SHIZUKU_PKG, "com.google.android.apps.bard", "com.google.android.googlequicksearchbox",
    )

    /** Sans aucune confirmation (Shizuku requis) : copie dans l'espace puis suppression côté personnel. */
    fun silentIsolate(ctx: Context, pkg: String): Boolean {
        if (pkg == ctx.packageName || pkg in NEVER_MOVE || !Shell.granted()) return false
        val user = Iso.isolatedUserId(ctx) ?: return false
        if (!Shell.installExisting(pkg, user)) return false
        Shell.uninstallForUser(pkg, 0, keepData = false)
        return true
    }
}
