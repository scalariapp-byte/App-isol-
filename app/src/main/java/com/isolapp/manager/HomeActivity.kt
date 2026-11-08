package com.isolapp.manager

import android.content.ComponentName
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.text.Collator
import java.text.Normalizer

/**
 * Écran d'accueil : applis personnelles et isolées affichées ensemble, triées par nom,
 * avec leurs icônes sans badge et sans onglet séparé. Rien n'indique qu'une appli est isolée.
 */
class HomeActivity : AppCompatActivity() {

    data class Entry(
        val label: String,
        val key: String,
        val icon: Drawable,
        val component: ComponentName,
        val user: UserHandle,
    )

    private lateinit var launcherApps: LauncherApps
    private lateinit var search: EditText
    private lateinit var grid: RecyclerView
    private val adapter = HomeAdapter()
    private var all: List<Entry> = emptyList()

    private val callback = object : LauncherApps.Callback() {
        override fun onPackageRemoved(packageName: String, user: UserHandle) = reload()
        override fun onPackageAdded(packageName: String, user: UserHandle) = reload()
        override fun onPackageChanged(packageName: String, user: UserHandle) = reload()
        override fun onPackagesAvailable(p: Array<out String>, user: UserHandle, replacing: Boolean) = reload()
        override fun onPackagesUnavailable(p: Array<out String>, user: UserHandle, replacing: Boolean) = reload()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)
        launcherApps = getSystemService(LauncherApps::class.java)
        search = findViewById(R.id.search)
        grid = findViewById(R.id.grid)

        val columns = (resources.configuration.screenWidthDp / 90).coerceIn(4, 8)
        grid.layoutManager = GridLayoutManager(this, columns)
        grid.adapter = adapter

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) = applyFilter()
        })
        search.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                adapter.items.firstOrNull()?.let { launch(it, null) }
                true
            } else false
        }
        findViewById<View>(R.id.settings).setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
        }
        // Un écran d'accueil ne se ferme pas avec « Retour ».
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = resetView()
        })

        launcherApps.registerCallback(callback, Handler(Looper.getMainLooper()))
        reload()
    }

    override fun onDestroy() {
        launcherApps.unregisterCallback(callback)
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        resetView() // appui sur « Accueil » alors qu'on y est déjà
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun resetView() {
        search.text?.clear()
        search.clearFocus()
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(search.windowToken, 0)
        grid.scrollToPosition(0)
    }

    // ---------- Chargement ----------

    private fun reload() {
        Thread {
            val list = buildEntries()
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                all = list
                applyFilter()
            }
        }.start()
    }

    private fun buildEntries(): List<Entry> {
        val me = Process.myUserHandle()
        val density = resources.displayMetrics.densityDpi
        val profiles = runCatching { launcherApps.profiles }.getOrDefault(listOf(me))
        val byUser = profiles.associateWith { u ->
            runCatching { launcherApps.getActivityList(null, u) }.getOrDefault(emptyList())
        }
        val personal = byUser[me].orEmpty()
        val personalSystem = personal
            .filter { (it.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0 }
            .map { it.componentName.packageName }.toSet()
        val otherPkgs = byUser.filterKeys { it != me }.values.flatten().map { it.componentName.packageName }.toSet()

        val chosen = ArrayList<LauncherActivityInfo>()
        // Une appli présente des deux côtés n'apparaît qu'une fois :
        // version personnelle pour les applis système (Paramètres, Appareil photo…), version isolée sinon.
        personal.filterTo(chosen) { it.componentName.packageName !in otherPkgs || it.componentName.packageName in personalSystem }
        byUser.filterKeys { it != me }.values.flatten()
            .filterTo(chosen) { it.componentName.packageName !in personalSystem }

        val collator = Collator.getInstance()
        return chosen
            .filter { it.componentName.packageName != packageName }
            .map { info ->
                val label = info.label.toString()
                Entry(
                    label = label,
                    key = normalize(label),
                    icon = info.getIcon(density), // icône sans badge
                    component = info.componentName,
                    user = info.user,
                )
            }
            .sortedWith { a, b -> collator.compare(a.label, b.label) }
    }

    private fun normalize(s: String) =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase()

    private fun applyFilter() {
        val q = normalize(search.text?.toString().orEmpty().trim())
        adapter.items = if (q.isEmpty()) all else all.filter { it.key.contains(q) }
    }

    // ---------- Actions ----------

    private fun launch(e: Entry, view: View?) {
        val bounds = view?.let { v ->
            val loc = IntArray(2).also { v.getLocationOnScreen(it) }
            Rect(loc[0], loc[1], loc[0] + v.width, loc[1] + v.height)
        }
        try {
            launcherApps.startMainActivity(e.component, e.user, bounds, null)
        } catch (t: Throwable) {
            // Espace isolé en pause : l'écran d'accueil par défaut a le droit de le réactiver.
            val um = getSystemService(UserManager::class.java)
            if (e.user != Process.myUserHandle() && runCatching { um.isQuietModeEnabled(e.user) }.getOrDefault(false)) {
                runCatching { um.requestQuietModeEnabled(false, e.user) }
            } else {
                Toast.makeText(this, "Impossible d'ouvrir ${e.label}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showMenu(e: Entry, anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add(0, 1, 0, R.string.app_info)
            menu.add(0, 2, 1, R.string.delete)
            setOnMenuItemClickListener {
                when (it.itemId) {
                    1 -> runCatching { launcherApps.startAppDetailsActivity(e.component, e.user, null, null) }
                    2 -> uninstall(e)
                }
                true
            }
            show()
        }
    }

    private fun uninstall(e: Entry) {
        val pkg = e.component.packageName
        if (e.user == Process.myUserHandle()) {
            runCatching { startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg"))) }
            return
        }
        if (!Shell.granted()) {
            // Sans Shizuku : la page système de l'appli (dans son profil) propose « Désinstaller ».
            runCatching { launcherApps.startAppDetailsActivity(e.component, e.user, null, null) }
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Désinstaller ${e.label} ?")
            .setPositiveButton(R.string.delete) { _, _ ->
                Thread {
                    val ok = Shell.uninstallForUser(pkg, e.user.hashCode(), keepData = false)
                    runOnUiThread { if (!ok) Toast.makeText(this, "Échec", Toast.LENGTH_SHORT).show(); reload() }
                }.start()
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    // ---------- Grille ----------

    private inner class HomeAdapter : RecyclerView.Adapter<HomeAdapter.VH>() {
        var items: List<Entry> = emptyList()
            set(v) { field = v; notifyDataSetChanged() }

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val icon: ImageView = v.findViewById(R.id.icon)
            val label: TextView = v.findViewById(R.id.label)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_home, parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(h: VH, position: Int) {
            val e = items[position]
            h.icon.setImageDrawable(e.icon)
            h.label.text = e.label
            h.itemView.contentDescription = e.label
            h.itemView.setOnClickListener { launch(e, it) }
            h.itemView.setOnLongClickListener { showMenu(e, it); true }
        }
    }
}
