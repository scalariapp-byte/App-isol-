package com.isolapp.manager

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import rikka.shizuku.Shizuku

/** Écran côté personnel. */
class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var btnCreate: MaterialButton
    private lateinit var rowButtons: LinearLayout
    private lateinit var swAuto: MaterialSwitch
    private lateinit var swDefault: MaterialSwitch
    private lateinit var shizukuStatus: TextView
    private lateinit var btnShizuku: MaterialButton
    private lateinit var adapter: AppAdapter

    private var showSystem = false
    private var showRemoved = false

    private val provision = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) Toast.makeText(this, "Espace isolé créé", Toast.LENGTH_LONG).show()
        refresh()
    }
    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val shizukuPermListener = Shizuku.OnRequestPermissionResultListener { _, _ -> runOnUiThread { refresh() } }
    private val shizukuBinderListener = Shizuku.OnBinderReceivedListener { runOnUiThread { refresh() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Iso.isIsolatedSide(this)) {
            Iso.ensureIsolatedSetup(this)
            startActivity(Intent(this, IsolatedActivity::class.java))
            finish()
            return
        }
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.status)
        btnCreate = findViewById(R.id.btnCreate)
        rowButtons = findViewById(R.id.rowButtons)
        swAuto = findViewById(R.id.swAuto)
        swDefault = findViewById(R.id.swDefault)
        shizukuStatus = findViewById(R.id.shizukuStatus)
        btnShizuku = findViewById(R.id.btnShizuku)

        adapter = AppAdapter(onAction = ::onAppAction, onMore = ::onAppMore)
        findViewById<RecyclerView>(R.id.list).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = this@MainActivity.adapter
        }

        btnCreate.setOnClickListener { createSpace() }
        findViewById<MaterialButton>(R.id.btnManage).setOnClickListener { crossProfile(Iso.ACTION_OPEN_MANAGER) }
        findViewById<MaterialButton>(R.id.btnStore).setOnClickListener { crossProfile(Iso.ACTION_OPEN_STORE) }
        swAuto.setOnCheckedChangeListener { _, on -> setAuto(on) }
        swDefault.setOnCheckedChangeListener { _, on -> setDefaultMode(on) }
        btnShizuku.setOnClickListener { onShizukuButton() }
        findViewById<MaterialButton>(R.id.btnHome).setOnClickListener { chooseHome() }

        Shizuku.addRequestPermissionResultListener(shizukuPermListener)
        Shizuku.addBinderReceivedListenerSticky(shizukuBinderListener)
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(shizukuPermListener)
        Shizuku.removeBinderReceivedListener(shizukuBinderListener)
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        if (::status.isInitialized) refresh()
    }

    // ---------- État général ----------

    private fun refresh() {
        val profile = Iso.isolatedProfile(this)
        val has = profile != null
        status.setText(if (has) R.string.space_ok else R.string.no_space)
        btnCreate.visibility = if (has) View.GONE else View.VISIBLE
        listOf(rowButtons, findViewById<View>(R.id.btnHome), swAuto, swDefault).forEach { it.visibility = if (has) View.VISIBLE else View.GONE }
        setChecked(swAuto, Prefs.monitor(this))
        setChecked(swDefault, Prefs.defaultMode(this))
        refreshShizuku()
        invalidateOptionsMenu()
        if (Prefs.monitor(this)) MonitorService.start(this)
        loadApps(profile)
    }

    /** Change l'état d'un interrupteur sans déclencher son écouteur. */
    private fun setChecked(sw: MaterialSwitch, v: Boolean) {
        if (sw.isChecked == v) return
        sw.tag = "silent"; sw.isChecked = v; sw.tag = null
    }

    private fun refreshShizuku() {
        val (text, button) = when {
            !Shell.installed(this) -> R.string.shizuku_off to R.string.shizuku_get
            !Shell.running() -> R.string.shizuku_stopped to R.string.shizuku_open
            !Shell.granted() -> R.string.shizuku_need_perm to R.string.shizuku_grant
            else -> R.string.shizuku_ok to 0
        }
        shizukuStatus.setText(text)
        btnShizuku.visibility = if (button == 0) View.GONE else View.VISIBLE
        if (button != 0) btnShizuku.setText(button)
    }

    private fun onShizukuButton() {
        when {
            !Shell.installed(this) -> openUrl("https://play.google.com/store/apps/details?id=${Shell.SHIZUKU_PKG}")
            !Shell.running() -> packageManager.getLaunchIntentForPackage(Shell.SHIZUKU_PKG)?.let { startActivity(it) }
            else -> Shell.request()
        }
    }

    private fun setAuto(on: Boolean) {
        if (swAuto.tag == "silent") return
        Prefs.setMonitor(this, on)
        if (on) {
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                    this, Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            MonitorService.start(this)
            if (!Shell.granted()) Toast.makeText(
                this, "Sans Shizuku : une notification te proposera d'isoler chaque nouvelle appli", Toast.LENGTH_LONG
            ).show()
        } else {
            MonitorService.stop(this)
        }
    }

    private fun setDefaultMode(on: Boolean) {
        if (swDefault.tag == "silent") return
        if (!Shell.granted()) {
            setChecked(swDefault, !on)
            Toast.makeText(this, "Nécessite Shizuku (voir en bas)", Toast.LENGTH_LONG).show()
            return
        }
        if (on) {
            MaterialAlertDialogBuilder(this)
                .setTitle("Mode par défaut")
                .setMessage(
                    "Le Play Store personnel sera désactivé : le seul Play Store visible sera celui de " +
                        "l'espace isolé, donc tout ce que tu télécharges s'y installe directement.\n\n" +
                        "Les applis personnelles existantes ne seront plus mises à jour tant que ce mode est actif. " +
                        "Ajoute ton compte Google au premier lancement du Play Store isolé.\n\n" +
                        "Les nouvelles applis installées autrement (APK, autre magasin) seront aussi déplacées automatiquement."
                )
                .setPositiveButton("Activer") { _, _ ->
                    if (Shell.setEnabled(Iso.PLAY_STORE, 0, false)) {
                        Prefs.setDefaultMode(this, true)
                        setChecked(swAuto, true); setAuto(true)
                        crossProfile(Iso.ACTION_OPEN_STORE)
                    } else {
                        Toast.makeText(this, "Échec de la désactivation du Play Store", Toast.LENGTH_LONG).show()
                    }
                    refresh()
                }
                .setNegativeButton("Annuler") { _, _ -> refresh() }
                .setOnCancelListener { refresh() }
                .show()
        } else {
            Shell.setEnabled(Iso.PLAY_STORE, 0, true)
            Prefs.setDefaultMode(this, false)
            refresh()
        }
    }

    // ---------- Liste des applis personnelles ----------

    private fun loadApps(profile: android.os.UserHandle?) {
        if (profile == null) { adapter.rows = emptyList(); return }
        val sys = showSystem
        val rem = showRemoved
        Thread {
            val pm = packageManager
            val isolated: Set<String> = runCatching {
                getSystemService(LauncherApps::class.java)
                    .getActivityList(null, profile).map { it.componentName.packageName }.toSet()
            }.getOrDefault(emptySet())
            val flags = if (rem) PackageManager.MATCH_UNINSTALLED_PACKAGES else 0
            val rows = pm.getInstalledApplications(flags)
                .asSequence()
                .filter { it.packageName != packageName }
                .mapNotNull { ai ->
                    val installed = (ai.flags and ApplicationInfo.FLAG_INSTALLED) != 0
                    val system = Critical.isSystem(ai)
                    val launchable = installed && pm.getLaunchIntentForPackage(ai.packageName) != null
                    if (installed && !sys && (system || !launchable)) return@mapNotNull null
                    if (!installed && !rem) return@mapNotNull null
                    val inIso = ai.packageName in isolated
                    val sub = when {
                        !installed && inIso -> "Uniquement dans l'espace isolé"
                        !installed -> "🗑 Supprimée du profil personnel"
                        else -> (if (system) "Système · " else "") +
                            (if (inIso) "aussi dans l'espace isolé" else "profil personnel")
                    }
                    AppRow(
                        pkg = ai.packageName,
                        label = pm.getApplicationLabel(ai).toString(),
                        icon = runCatching { pm.getApplicationIcon(ai) }.getOrNull(),
                        sub = sub,
                        actionText = when {
                            !installed -> getString(R.string.restore)
                            launchable && !inIso -> getString(R.string.isolate)
                            else -> null
                        },
                        showMore = installed,
                        system = system,
                        removed = !installed,
                    )
                }
                .sortedWith(compareBy({ it.removed }, { it.label.lowercase() }))
                .toList()
            runOnUiThread { if (!isFinishing) adapter.rows = rows }
        }.start()
    }

    private fun onAppAction(row: AppRow) {
        if (row.removed) { restore(row); return }
        if (Shell.granted()) {
            Thread {
                val ok = Mover.silentIsolate(this, row.pkg)
                runOnUiThread {
                    Toast.makeText(
                        this, if (ok) "${row.label} déplacée dans l'espace isolé" else "Échec du déplacement",
                        Toast.LENGTH_SHORT
                    ).show()
                    refresh()
                }
            }.start()
        } else {
            startActivity(IsolateActivity.intent(this, row.pkg))
        }
    }

    private fun onAppMore(row: AppRow, anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add(0, 1, 0, R.string.delete)
            menu.add(0, 2, 1, R.string.app_info)
            setOnMenuItemClickListener {
                when (it.itemId) {
                    1 -> uninstall(row)
                    2 -> runCatching {
                        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${row.pkg}")))
                    }
                }
                true
            }
            show()
        }
    }

    private fun uninstall(row: AppRow) {
        if (Critical.isCritical(this, row.pkg)) {
            MaterialAlertDialogBuilder(this)
                .setTitle("Suppression bloquée")
                .setMessage("${row.label} est indispensable au fonctionnement du téléphone (risque de blocage au démarrage). Elle ne peut pas être supprimée.")
                .setPositiveButton("OK", null)
                .show()
            return
        }
        if (!row.system) {
            runCatching { startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:${row.pkg}"))) }
            return
        }
        if (!Shell.granted()) {
            Toast.makeText(this, "Supprimer une appli système nécessite Shizuku (voir en haut)", Toast.LENGTH_LONG).show()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Désinstaller ${row.label} ?")
            .setMessage("Appli système : elle sera supprimée du profil personnel. Elle reste restaurable via « Afficher les applis supprimées ».")
            .setPositiveButton(R.string.delete) { _, _ ->
                Thread {
                    val ok = Shell.uninstallForUser(row.pkg, 0, keepData = true)
                    runOnUiThread {
                        Toast.makeText(this, if (ok) "${row.label} supprimée" else "Échec", Toast.LENGTH_SHORT).show()
                        refresh()
                    }
                }.start()
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun restore(row: AppRow) {
        if (!Shell.granted()) {
            Toast.makeText(this, "La restauration nécessite Shizuku", Toast.LENGTH_LONG).show()
            return
        }
        Thread {
            val ok = Shell.installExisting(row.pkg, 0)
            runOnUiThread {
                Toast.makeText(this, if (ok) "${row.label} restaurée" else "Échec", Toast.LENGTH_SHORT).show()
                refresh()
            }
        }.start()
    }

    // ---------- Divers ----------

    private fun crossProfile(action: String) {
        try {
            startActivity(Intent(action))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "Espace isolé indisponible (en pause ?)", Toast.LENGTH_LONG).show()
        }
    }

    /** Propose Isolateur comme écran d'accueil par défaut. */
    private fun chooseHome() {
        val intent = if (Build.VERSION.SDK_INT >= 29) {
            val rm = getSystemService(android.app.role.RoleManager::class.java)
            if (rm.isRoleHeld(android.app.role.RoleManager.ROLE_HOME)) {
                Toast.makeText(this, "Déjà ton écran d'accueil", Toast.LENGTH_SHORT).show()
                return
            }
            rm.createRequestRoleIntent(android.app.role.RoleManager.ROLE_HOME)
        } else {
            Intent(Settings.ACTION_HOME_SETTINGS)
        }
        runCatching { startActivity(intent) }.onFailure {
            runCatching { startActivity(Intent(Settings.ACTION_HOME_SETTINGS)) }
        }
    }

    private fun openUrl(url: String) = runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }

    private fun createSpace() {
        val dpm = Iso.dpm(this)
        if (!dpm.isProvisioningAllowed(DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE)) {
            Toast.makeText(
                this,
                "Impossible : un profil professionnel existe déjà (un seul autorisé) ou l'appareil ne le permet pas",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        val i = Intent(DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE)
            .putExtra(DevicePolicyManager.EXTRA_PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME, Iso.admin(this))
        try {
            provision.launch(i)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "Création de profil non prise en charge sur cet appareil", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        if (Iso.isolatedProfile(this) == null) return false
        menuInflater.inflate(R.menu.main, menu)
        menu.findItem(R.id.m_system).isChecked = showSystem
        menu.findItem(R.id.m_removed).isChecked = showRemoved
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.m_install -> crossProfile(Iso.ACTION_INSTALL_APK)
            R.id.m_system -> { showSystem = !showSystem; item.isChecked = showSystem; refresh() }
            R.id.m_removed -> { showRemoved = !showRemoved; item.isChecked = showRemoved; refresh() }
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }
}
