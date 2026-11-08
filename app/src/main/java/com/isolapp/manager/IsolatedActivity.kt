package com.isolapp.manager

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/** Gestionnaire des applis de l'espace isolé (s'exécute dans le profil isolé). */
class IsolatedActivity : AppCompatActivity() {

    private lateinit var adapter: AppAdapter
    private lateinit var progress: ProgressBar
    private var showSystem = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!Iso.isIsolatedSide(this)) { finish(); return }
        Iso.ensureIsolatedSetup(this)
        setContentView(R.layout.activity_isolated)
        setSupportActionBar(findViewById(R.id.toolbar))
        progress = findViewById(R.id.progress)
        adapter = AppAdapter(onAction = ::launch, onAuto = ::setAutostart, onMore = ::showMenu)
        findViewById<RecyclerView>(R.id.list).apply {
            layoutManager = LinearLayoutManager(this@IsolatedActivity)
            adapter = this@IsolatedActivity.adapter
        }
    }

    override fun onResume() {
        super.onResume()
        if (::adapter.isInitialized) load()
    }

    private fun load() {
        progress.visibility = View.VISIBLE
        val sys = showSystem
        Thread {
            val pm = packageManager
            val blocked = Prefs.blocked(this)
            val removed = Prefs.removed(this)
            val rows = pm.getInstalledApplications(PackageManager.MATCH_UNINSTALLED_PACKAGES)
                .asSequence()
                .filter { it.packageName != packageName }
                .map { it to Freezer.isFrozen(this, it.packageName) }
                .filter { (ai, frozen) -> (ai.flags and ApplicationInfo.FLAG_INSTALLED) != 0 || frozen }
                .filter { (ai, _) ->
                    sys || ai.packageName in removed || (ai.flags and ApplicationInfo.FLAG_SYSTEM) == 0 ||
                        (ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
                }
                .map { (ai, frozen) ->
                    val isRemoved = ai.packageName in removed
                    val isBlocked = ai.packageName in blocked
                    val system = Critical.isSystem(ai)
                    val state = when {
                        isRemoved -> "🗑 Supprimée"
                        frozen -> "❄ Gelée"
                        else -> "▶ Active"
                    }
                    val auto = if (isBlocked && !isRemoved) " · démarrage auto bloqué" else ""
                    AppRow(
                        pkg = ai.packageName,
                        label = pm.getApplicationLabel(ai).toString(),
                        icon = runCatching { pm.getApplicationIcon(ai) }.getOrNull(),
                        sub = (if (system) "Système · " else "") + state + auto,
                        actionText = getString(if (isRemoved) R.string.restore else R.string.launch),
                        showAuto = !isRemoved,
                        autoAllowed = !isBlocked,
                        showMore = !isRemoved,
                        system = system,
                        removed = isRemoved,
                    )
                }
                .sortedWith(compareBy({ it.removed }, { it.label.lowercase() }))
                .toList()
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                progress.visibility = View.GONE
                adapter.rows = rows
            }
        }.start()
    }

    private fun launch(row: AppRow) {
        if (row.removed) {
            Prefs.setRemoved(this, row.pkg, false)
            Freezer.setFrozen(this, row.pkg, false)
            load(); return
        }
        if (Freezer.isFrozen(this, row.pkg)) Freezer.setFrozen(this, row.pkg, false)
        if (row.pkg in Prefs.blocked(this)) AutoFreezeService.start(this)
        val i = packageManager.getLaunchIntentForPackage(row.pkg)
        if (i == null) {
            Toast.makeText(this, "Cette appli n'a pas d'écran de lancement", Toast.LENGTH_SHORT).show()
            load(); return
        }
        runCatching { startActivity(i) }
    }

    /** allowed = false : l'appli est gelée et le reste ; elle ne peut plus démarrer avec le téléphone. */
    private fun setAutostart(row: AppRow, allowed: Boolean) {
        Prefs.setBlocked(this, row.pkg, !allowed)
        val ok = Freezer.setFrozen(this, row.pkg, !allowed)
        if (!ok) Toast.makeText(this, "Action refusée par le système pour ${row.label}", Toast.LENGTH_SHORT).show()
        load()
    }

    private fun showMenu(row: AppRow, anchor: View) {
        val frozen = Freezer.isFrozen(this, row.pkg)
        PopupMenu(this, anchor).apply {
            menu.add(0, 1, 0, if (frozen) R.string.unfreeze else R.string.freeze)
            menu.add(0, 2, 1, R.string.app_info)
            menu.add(0, 3, 2, R.string.uninstall)
            setOnMenuItemClickListener {
                when (it.itemId) {
                    1 -> { Freezer.setFrozen(this@IsolatedActivity, row.pkg, !frozen); load() }
                    2 -> {
                        if (frozen) Freezer.setFrozen(this@IsolatedActivity, row.pkg, false)
                        runCatching {
                            startActivity(
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${row.pkg}"))
                            )
                        }
                    }
                    3 -> uninstall(row, frozen)
                }
                true
            }
            show()
        }
    }

    private fun uninstall(row: AppRow, frozen: Boolean) {
        if (row.system) {
            // Une appli système ne peut pas être désinstallée d'un profil : on la masque définitivement.
            if (Critical.isCritical(this, row.pkg)) {
                Toast.makeText(this, "${row.label} est indispensable, suppression bloquée", Toast.LENGTH_LONG).show()
                return
            }
            if (Freezer.setFrozen(this, row.pkg, true)) {
                Prefs.setRemoved(this, row.pkg, true)
                Prefs.setBlocked(this, row.pkg, false)
            } else {
                Toast.makeText(this, "Action refusée par le système", Toast.LENGTH_SHORT).show()
            }
            load()
            return
        }
        if (frozen) Freezer.setFrozen(this, row.pkg, false)
        Prefs.setBlocked(this, row.pkg, false)
        runCatching { startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:${row.pkg}"))) }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.isolated, menu)
        menu.findItem(R.id.m_system).isChecked = showSystem
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.m_install -> startActivity(
                Intent(this, WorkActionActivity::class.java).setAction(Iso.ACTION_INSTALL_APK)
            )
            R.id.m_freeze_all -> {
                val n = Freezer.freezeBlocked(this)
                Toast.makeText(this, "$n appli(s) gelée(s)", Toast.LENGTH_SHORT).show()
                load()
            }
            R.id.m_system -> {
                showSystem = !showSystem
                item.isChecked = showSystem
                load()
            }
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }
}
