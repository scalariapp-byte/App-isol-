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

    private lateinit var heroTitle: TextView
    private lateinit var heroSub: TextView
    private lateinit var btnCreate: MaterialButton
    private lateinit var rowButtons: LinearLayout
    private lateinit var options: LinearLayout
    private lateinit var btnHome: MaterialButton
    private lateinit var homeSub: TextView
    private lateinit var swAuto: MaterialSwitch
    private lateinit var swDefault: MaterialSwitch
    private lateinit var shizukuStatus: TextView
    private lateinit var shizukuDot: View
    private lateinit var btnShizuku: MaterialButton
    private lateinit var cardSteps: View
    private lateinit var steps: List<TextView>
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
        setSupportActionBar(findViewById(R.id.toolbar))
        findViewById<com.google.android.material.appbar.CollapsingToolbarLayout>(R.id.collapsing).title =
            getString(R.string.app_name)

        val header = layoutInflater.inflate(R.layout.header_main, null, false)
        header.layoutParams = RecyclerView.LayoutParams(
            RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT
        )
        heroTitle = header.findViewById(R.id.heroTitle)
        heroSub = header.findViewById(R.id.heroSub)
        btnCreate = header.findViewById(R.id.btnCreate)
        rowButtons = header.findViewById(R.id.rowButtons)
        options = header.findViewById(R.id.options)
        btnHome = header.findViewById(R.id.btnHome)
        homeSub = header.findViewById(R.id.homeSub)
        swAuto = header.findViewById(R.id.swAuto)
        swDefault = header.findViewById(R.id.swDefault)
        shizukuStatus = header.findViewById(R.id.shizukuStatus)
        shizukuDot = header.findViewById(R.id.shizukuDot)
        btnShizuku = header.findViewById(R.id.btnShizuku)
        cardSteps = header.findViewById(R.id.cardSteps)
        steps = listOf(R.id.step1, R.id.step2, R.id.step3, R.id.step4).map { header.findViewById(it) }

        adapter = AppAdapter(onAction = ::onAppAction, onMore = ::onAppMore)
        findViewById<RecyclerView>(R.id.list).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = androidx.recyclerview.widget.ConcatAdapter(HeaderAdapter(header), this@MainActivity.adapter)
        }

        btnCreate.setOnClickListener {
            val p = Iso.isolatedProfile(this)
            if (p != null) resume(p) else createSpace()
        }
        header.findViewById<MaterialButton>(R.id.btnManage).setOnClickListener { crossProfile(Iso.ACTION_OPEN_MANAGER) }
        header.findViewById<MaterialButton>(R.id.btnStore).setOnClickListener { crossProfile(Iso.ACTION_OPEN_STORE) }
        swAuto.setOnCheckedChangeListener { _, on -> setAuto(on) }
        swDefault.setOnCheckedChangeListener { _, on -> setDefaultMode(on) }
        btnShizuku.setOnClickListener { onShizukuButton() }
        btnHome.setOnClickListener { chooseHome() }
        header.findViewById<com.google.android.material.chip.Chip>(R.id.chipSystem)
            .setOnCheckedChangeListener { _, c -> showSystem = c; refresh() }
        header.findViewById<com.google.android.material.chip.Chip>(R.id.chipRemoved)
            .setOnCheckedChangeListener { _, c -> showRemoved = c; refresh() }

        Updater.check(this)
        if (!getSharedPreferences("iso", MODE_PRIVATE).getBoolean("guide_seen", false)) showGuide(0)
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
        if (::heroTitle.isInitialized) refresh()
    }

    // ---------- État général ----------

    private fun refresh() {
        val profile = Iso.isolatedProfile(this)
        val has = profile != null
        if (!has && Prefs.pendingUninstall(this)) {
            // Espace supprimé : on termine par la désinstallation d'Isolateur lui-même.
            Prefs.setPendingUninstall(this, false)
            runCatching { startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$packageName"))) }
        }
        val um = getSystemService(android.os.UserManager::class.java)
        val paused = profile != null && runCatching { um.isQuietModeEnabled(profile) }.getOrDefault(false)
        heroTitle.text = when { paused -> "Espace isolé en pause"; has -> "Espace isolé actif"; else -> "Aucun espace isolé" }
        heroSub.text = if (has) "Tes applis isolées ont leurs propres données, séparées du reste."
        else "Crée-le pour y placer des applis : données, comptes et fichiers séparés."
        btnCreate.visibility = if (has && !paused) View.GONE else View.VISIBLE
        btnCreate.text = if (paused) "Réactiver l'espace isolé" else getString(R.string.create_space)
        if (paused) heroSub.text = "Les applis isolées sont arrêtées. Appuie sur Réactiver."
        listOf(rowButtons, options).forEach { it.visibility = if (has) View.VISIBLE else View.GONE }
        val isHome = isDefaultHome()
        btnHome.text = if (isHome) "Actif ✓" else "Activer"
        btnHome.isEnabled = !isHome
        setChecked(swAuto, Prefs.monitor(this))
        setChecked(swDefault, Prefs.defaultMode(this))
        refreshShizuku()
        refreshSteps(has, paused, isHome)
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
        shizukuDot.backgroundTintList = android.content.res.ColorStateList.valueOf(
            if (button == 0) 0xFF2E7D32.toInt() else if (text == R.string.shizuku_off) 0xFF9E9E9E.toInt() else 0xFFF9A825.toInt()
        )
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

    /** Xiaomi : force les gestes plein écran même avec un lanceur tiers (réglage caché, via Shizuku). */
    private fun xiaomiGestures() {
        if (!Shell.granted()) {
            Toast.makeText(this, "Nécessite Shizuku actif", Toast.LENGTH_LONG).show(); return
        }
        val on = Shell.run("settings get global force_fsg_nav_bar").second.trim() == "1"
        MaterialAlertDialogBuilder(this)
            .setTitle("Gestes plein écran")
            .setMessage(
                if (on) "Les gestes forcés sont actifs. Les désactiver ?"
                else "Active les gestes plein écran avec l'écran d'accueil Isolateur. " +
                    "L'animation de retour à l'accueil peut être moins fluide qu'avec le lanceur Xiaomi."
            )
            .setPositiveButton(if (on) "Désactiver" else "Activer") { _, _ ->
                val v = if (on) 0 else 1
                Shell.run("settings put global force_fsg_nav_bar $v")
                Toast.makeText(
                    this, if (on) "Gestes forcés désactivés" else "Activé : choisis « Gestes » dans Système de navigation",
                    Toast.LENGTH_LONG
                ).show()
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    /** Sort l'espace isolé de pause (autorisé si Isolateur est l'écran d'accueil par défaut). */
    private fun resume(p: android.os.UserHandle) {
        val ok = runCatching {
            getSystemService(android.os.UserManager::class.java).requestQuietModeEnabled(false, p)
        }.isSuccess
        if (!ok) Toast.makeText(
            this, "Utilise la tuile « Applis pro » des réglages rapides, ou mets Isolateur en écran d'accueil.",
            Toast.LENGTH_LONG
        ).show()
        btnCreate.postDelayed({ refresh() }, 1500)
    }

    private fun confirmRemoveSpace() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Tout supprimer ?")
            .setMessage(
                "L'espace isolé sera supprimé avec TOUTES ses applis et leurs données (comptes, photos, " +
                    "messages qui s'y trouvent). C'est irréversible.\n\n" +
                    "Le Play Store personnel sera réactivé, puis Isolateur te proposera de se désinstaller."
            )
            .setPositiveButton("Tout supprimer") { _, _ -> removeSpace() }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun removeSpace() {
        // On rend le téléphone dans son état normal avant de partir.
        if (Prefs.defaultMode(this) && Shell.granted()) Shell.setEnabled(Iso.PLAY_STORE, 0, true)
        Prefs.setDefaultMode(this, false)
        Prefs.setMonitor(this, false)
        MonitorService.stop(this)
        Prefs.setPendingUninstall(this, true)
        try {
            startActivity(Intent(Iso.ACTION_REMOVE_SPACE))
        } catch (e: ActivityNotFoundException) {
            Prefs.setPendingUninstall(this, false)
            MaterialAlertDialogBuilder(this)
                .setTitle("Suppression manuelle")
                .setMessage(
                    "Va dans Paramètres → Comptes (ou « Utilisateurs et comptes ») → Profil professionnel → " +
                        "« Supprimer le profil professionnel ». Désinstalle ensuite Isolateur."
                )
                .setPositiveButton("Ouvrir les Paramètres") { _, _ ->
                    runCatching { startActivity(Intent(Settings.ACTION_SYNC_SETTINGS)) }
                }
                .setNegativeButton("Fermer", null)
                .show()
        }
    }

    // ---------- Aide ----------

    private val guide = listOf(
        "C'est quoi ?" to "Isolateur crée un « espace isolé » dans ton téléphone : un coffre où tes applis vivent à part, avec leurs propres données. Elles ne voient pas le reste du téléphone.",
        "Étape 1 : créer l'espace" to "Appuie sur « Créer l'espace isolé » et suis les écrans d'Android jusqu'au bout. C'est à faire une seule fois.",
        "Étape 2 : mettre des applis dedans" to "Dans la liste en bas, appuie sur « Mettre à l'abri » à côté d'une appli. Ou installe-la directement avec le bouton « Play Store » : elle ira dans l'espace isolé.",
        "Étape 3 : empêcher une appli de se lancer seule" to "Ouvre « Mes applis isolées » et éteins l'interrupteur de l'appli. Elle dort : plus rien en fond, plus de notification. « Ouvrir » la réveille.",
        "Optionnel : cacher l'isolation" to "Sur la carte « Cacher l'onglet Travail », appuie sur « Activer » : ton écran d'accueil mélange toutes les applis, sans 💼.",
        "Optionnel : super-pouvoirs" to "L'appli gratuite Shizuku permet de supprimer les applis système et d'isoler sans confirmation. Pas obligatoire.",
    )

    private fun showGuide(i: Int) {
        val (title, text) = guide[i]
        MaterialAlertDialogBuilder(this)
            .setTitle("${i + 1}/${guide.size} · $title")
            .setMessage(text)
            .setCancelable(false)
            .setPositiveButton(if (i == guide.lastIndex) "J'ai compris" else "Suivant") { _, _ ->
                if (i < guide.lastIndex) showGuide(i + 1)
                else getSharedPreferences("iso", MODE_PRIVATE).edit().putBoolean("guide_seen", true).apply()
            }
            .setNegativeButton("Passer") { _, _ ->
                getSharedPreferences("iso", MODE_PRIVATE).edit().putBoolean("guide_seen", true).apply()
            }
            .show()
    }

    /** Carte « Par où commencer » : chaque étape cochée quand elle est faite. */
    private fun refreshSteps(has: Boolean, paused: Boolean, isHome: Boolean) {
        val items = listOf(
            Triple(has && !paused, if (paused) "Réactiver l'espace isolé" else "Créer l'espace isolé") {
                Iso.isolatedProfile(this)?.let { resume(it) } ?: createSpace()
            },
            Triple(hasIsolatedApp(), "Mettre une appli à l'abri") {
                Toast.makeText(this, "Appuie sur « Mettre à l'abri » dans la liste en bas", Toast.LENGTH_LONG).show()
            },
            Triple(isHome, "Cacher l'onglet Travail (optionnel)") { chooseHome() },
            Triple(Shell.granted(), "Super-pouvoirs Shizuku (optionnel)") { onShizukuButton() },
        )
        items.forEachIndexed { i, (done, label, action) ->
            steps[i].text = (if (done) "✅  " else "${i + 1}.  ") + label
            steps[i].alpha = if (done) 0.6f else 1f
            steps[i].setOnClickListener { if (has || i == 0) action() }
        }
        cardSteps.visibility = if (items.all { it.first }) View.GONE else View.VISIBLE
    }

    private fun hasIsolatedApp(): Boolean {
        val p = Iso.isolatedProfile(this) ?: return false
        return runCatching {
            getSystemService(LauncherApps::class.java).getActivityList(null, p).any {
                it.componentName.packageName != packageName &&
                    (it.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) == 0
            }
        }.getOrDefault(false)
    }

    private fun isDefaultHome(): Boolean = runCatching {
        packageManager.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY
        )?.activityInfo?.packageName == packageName
    }.getOrDefault(false)

    private class HeaderAdapter(private val view: View) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int) =
            object : RecyclerView.ViewHolder(view) {}
        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {}
        override fun getItemCount() = 1
        override fun getItemViewType(position: Int) = -1
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
        menuInflater.inflate(R.menu.main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.m_help -> showGuide(0)
            R.id.m_install -> crossProfile(Iso.ACTION_INSTALL_APK)
            R.id.m_remove_space -> confirmRemoveSpace()
            R.id.m_gestures -> xiaomiGestures()
            R.id.m_files -> {
                // Appli liée : son écran « Démarrage automatique » bloque le démarrage via Isolateur.
                val i = packageManager.getLaunchIntentForPackage("com.scalari.fichiers")
                    ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/scalariapp-byte/Gestionnaire-de-fichiers-android/releases/latest"))
                runCatching { startActivity(i) }
            }
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }
}
