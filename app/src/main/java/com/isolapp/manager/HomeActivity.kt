package com.isolapp.manager

import android.content.ComponentName
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import android.provider.MediaStore
import android.provider.Telephony
import android.text.Editable
import android.text.TextWatcher
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.text.Collator
import java.text.Normalizer
import kotlin.math.abs

/**
 * Écran d'accueil au look standard (horloge, dock, barre de recherche, tiroir d'applis).
 * Applis personnelles et isolées y sont mélangées, avec des icônes sans badge :
 * rien ne permet de distinguer une appli isolée.
 */
class HomeActivity : AppCompatActivity() {

    data class Entry(
        val label: String,
        val search: String,
        val icon: Drawable,
        val component: ComponentName,
        val user: UserHandle,
    ) {
        val key get() = "${user.hashCode()}|${component.flattenToString()}"
    }

    private lateinit var launcherApps: LauncherApps
    private lateinit var home: View
    private lateinit var drawer: View
    private lateinit var dock: LinearLayout
    private lateinit var search: EditText
    private lateinit var grid: RecyclerView
    private lateinit var homeGrid: RecyclerView

    private val drawerAdapter = GridAdapter(R.layout.item_drawer, inDrawer = true)
    private val homeAdapter = GridAdapter(R.layout.item_home, inDrawer = false)
    private var all: List<Entry> = emptyList()
    private var byKey: Map<String, Entry> = emptyMap()
    private var drawerOpen = false
    private lateinit var widgetHost: android.appwidget.AppWidgetHost
    private lateinit var widgetManager: android.appwidget.AppWidgetManager
    private var pendingWidgetId = -1

    private val callback = object : LauncherApps.Callback() {
        override fun onPackageRemoved(packageName: String, user: UserHandle) = reload()
        override fun onPackageAdded(packageName: String, user: UserHandle) = reload()
        override fun onPackageChanged(packageName: String, user: UserHandle) = reload()
        override fun onPackagesAvailable(p: Array<out String>, user: UserHandle, replacing: Boolean) = reload()
        override fun onPackagesUnavailable(p: Array<out String>, user: UserHandle, replacing: Boolean) = reload()
    }

    private val gestures by lazy {
        GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                val start = e1 ?: return false
                val dy = e2.y - start.y
                val dx = e2.x - start.x
                if (abs(dy) < abs(dx) * 1.2f || abs(dy) < 120) return false
                if (!drawerOpen) {
                    if (dy < 0 && vy < -500) { openDrawer(false); return true }
                    if (dy > 0 && vy > 500) { expandNotifications(); return true }
                } else if (dy > 0 && vy > 500 && !grid.canScrollVertically(-1)) {
                    closeDrawer(); return true
                }
                return false
            }
        })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_home)
        launcherApps = getSystemService(LauncherApps::class.java)
        home = findViewById(R.id.home)
        drawer = findViewById(R.id.drawer)
        dock = findViewById(R.id.dock)
        search = findViewById(R.id.search)
        grid = findViewById(R.id.grid)
        homeGrid = findViewById(R.id.homeGrid)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            home.setPadding(0, bars.top, 0, bars.bottom)
            drawer.setPadding(0, bars.top + 8, 0, 0)
            grid.setPadding(grid.paddingLeft, grid.paddingTop, grid.paddingRight, bars.bottom + 16)
            insets
        }

        val columns = (resources.configuration.screenWidthDp / 88).coerceIn(4, 8)
        grid.layoutManager = GridLayoutManager(this, columns)
        grid.adapter = drawerAdapter
        homeGrid.layoutManager = GridLayoutManager(this, columns)
        homeGrid.adapter = homeAdapter
        homeGrid.addOnItemTouchListener(emptyAreaLongPress())

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) = applyFilter()
        })
        search.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                drawerAdapter.items.firstOrNull()?.let { launch(it, null) }; true
            } else false
        }
        findViewById<View>(R.id.searchPill).setOnClickListener { openDrawer(true) }
        findViewById<View>(R.id.chevron).setOnClickListener { openDrawer(false) }
        findViewById<View>(R.id.settings).setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if (drawerOpen) closeDrawer() }
        })

        widgetManager = android.appwidget.AppWidgetManager.getInstance(this)
        widgetHost = android.appwidget.AppWidgetHost(this, WIDGET_HOST_ID)
        renderWidgets()

        drawer.post { drawer.translationY = drawer.height.toFloat() }
        launcherApps.registerCallback(callback, Handler(Looper.getMainLooper()))
        reload()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        gestures.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    override fun onStart() {
        super.onStart()
        runCatching { widgetHost.startListening() }
    }

    override fun onStop() {
        runCatching { widgetHost.stopListening() }
        super.onStop()
    }

    // ---------- Widgets ----------

    private fun pickWidget() {
        pendingWidgetId = widgetHost.allocateAppWidgetId()
        val i = Intent(android.appwidget.AppWidgetManager.ACTION_APPWIDGET_PICK)
            .putExtra(android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_ID, pendingWidgetId)
        runCatching { startActivityForResult(i, REQ_PICK_WIDGET) }.onFailure {
            widgetHost.deleteAppWidgetId(pendingWidgetId)
            Toast.makeText(this, "Sélecteur de widgets indisponible", Toast.LENGTH_SHORT).show()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val id = data?.getIntExtra(android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_ID, pendingWidgetId)
            ?: pendingWidgetId
        if (resultCode != RESULT_OK) {
            if (id != -1) widgetHost.deleteAppWidgetId(id)
            return
        }
        when (requestCode) {
            REQ_PICK_WIDGET -> {
                val info = widgetManager.getAppWidgetInfo(id) ?: return
                if (info.configure != null) {
                    runCatching {
                        widgetHost.startAppWidgetConfigureActivityForResult(this, id, 0, REQ_CONFIG_WIDGET, null)
                    }.onFailure { addWidget(id) }
                } else addWidget(id)
            }
            REQ_CONFIG_WIDGET -> addWidget(id)
        }
    }

    private fun addWidget(id: Int) {
        Prefs.setWidgets(this, Prefs.widgets(this) + id)
        renderWidgets()
    }

    private fun renderWidgets() {
        val box = findViewById<LinearLayout>(R.id.widgets)
        box.removeAllViews()
        val d = resources.displayMetrics.density
        for (id in Prefs.widgets(this)) {
            val info = widgetManager.getAppWidgetInfo(id) ?: continue
            val v = widgetHost.createView(applicationContext, id, info)
            val h = maxOf(info.minHeight, (60 * d).toInt()) // minHeight est déjà en pixels
            box.addView(v, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, h).apply {
                bottomMargin = (8 * d).toInt()
            })
        }
    }

    private fun removeWidgetDialog() {
        val ids = Prefs.widgets(this)
        if (ids.isEmpty()) return
        val names = ids.map { widgetManager.getAppWidgetInfo(it)?.loadLabel(packageManager) ?: "Widget" }
        MaterialAlertDialogBuilder(this)
            .setTitle("Retirer un widget")
            .setItems(names.toTypedArray()) { _, i ->
                widgetHost.deleteAppWidgetId(ids[i])
                Prefs.setWidgets(this, ids - ids[i])
                renderWidgets()
            }
            .show()
    }

    override fun onDestroy() {
        launcherApps.unregisterCallback(callback)
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (drawerOpen) closeDrawer() // appui sur « Accueil »
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    // ---------- Tiroir ----------

    private fun openDrawer(focusSearch: Boolean) {
        drawerOpen = true
        drawer.visibility = View.VISIBLE
        drawer.animate().translationY(0f).setDuration(260).setInterpolator(DecelerateInterpolator(2f)).start()
        home.animate().alpha(0f).setDuration(200).start()
        if (focusSearch) {
            search.requestFocus()
            getSystemService(InputMethodManager::class.java).showSoftInput(search, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun closeDrawer() {
        drawerOpen = false
        search.text?.clear()
        search.clearFocus()
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(search.windowToken, 0)
        drawer.animate().translationY(drawer.height.toFloat()).setDuration(220)
            .setInterpolator(DecelerateInterpolator()).withEndAction {
                drawer.visibility = View.INVISIBLE
                grid.scrollToPosition(0)
            }.start()
        home.animate().alpha(1f).setDuration(220).start()
    }

    /** Volet des notifications au glissement vers le bas, comme un écran d'accueil standard. */
    private fun expandNotifications() {
        runCatching {
            val sbm = getSystemService("statusbar")
            Class.forName("android.app.StatusBarManager").getMethod("expandNotificationsPanel").invoke(sbm)
        }
    }

    // ---------- Chargement ----------

    private fun reload() {
        Thread {
            val list = buildEntries()
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                all = list
                byKey = list.associateBy { it.key }
                ensureDefaultDock()
                applyFilter()
                renderHome()
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
        val others = byUser.filterKeys { it != me }.values.flatten()
        val personalSystem = personal
            .filter { (it.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0 }
            .map { it.componentName.packageName }.toSet()
        val otherPkgs = others.map { it.componentName.packageName }.toSet()

        // Une appli présente des deux côtés n'apparaît qu'une fois : version personnelle
        // pour les applis système (Paramètres, Appareil photo…), version isolée sinon.
        val chosen = ArrayList<LauncherActivityInfo>()
        personal.filterTo(chosen) {
            it.componentName.packageName !in otherPkgs || it.componentName.packageName in personalSystem
        }
        others.filterTo(chosen) { it.componentName.packageName !in personalSystem }

        val collator = Collator.getInstance()
        return chosen
            .filter { it.componentName.packageName != packageName }
            .map { info ->
                val label = info.label.toString()
                Entry(label, normalize(label), info.getIcon(density), info.componentName, info.user)
            }
            .sortedWith { a, b -> collator.compare(a.label, b.label) }
    }

    private fun normalize(s: String) =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase()

    private fun applyFilter() {
        val q = normalize(search.text?.toString().orEmpty().trim())
        drawerAdapter.items = if (q.isEmpty()) all else all.filter { it.search.contains(q) }
    }

    /** Premier lancement : dock Téléphone, Messages, Navigateur, Appareil photo, Play Store. */
    private fun ensureDefaultDock() {
        if (Prefs.dock(this) != null || all.isEmpty()) return
        val pm = packageManager
        fun resolve(i: Intent) = runCatching {
            pm.resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
        }.getOrNull()?.takeIf { it != "android" }
        val pkgs = listOfNotNull(
            resolve(Intent(Intent.ACTION_DIAL)),
            runCatching { Telephony.Sms.getDefaultSmsPackage(this) }.getOrNull(),
            resolve(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.example.com"))),
            resolve(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)),
            Iso.PLAY_STORE,
        ).distinct()
        val keys = pkgs.mapNotNull { p -> all.firstOrNull { it.component.packageName == p }?.key }.take(DOCK_MAX)
        Prefs.setDock(this, keys)
    }

    private fun renderHome() {
        homeAdapter.items = Prefs.pins(this).mapNotNull { byKey[it] }
        dock.removeAllViews()
        val size = (56 * resources.displayMetrics.density).toInt()
        for (e in Prefs.dock(this).orEmpty().mapNotNull { byKey[it] }) {
            val cell = layoutInflater.inflate(R.layout.item_dock, dock, false)
            cell.findViewById<ImageView>(R.id.icon).apply {
                setImageDrawable(e.icon)
                layoutParams = layoutParams.apply { width = size; height = size }
            }
            cell.contentDescription = e.label
            cell.setOnClickListener { launch(e, it) }
            cell.setOnLongClickListener { showMenu(e, it, Place.DOCK); true }
            dock.addView(cell)
        }
    }

    // ---------- Actions ----------

    private enum class Place { DRAWER, HOME, DOCK }

    private fun launch(e: Entry, view: View?) {
        val bounds = view?.let { v ->
            val loc = IntArray(2).also { v.getLocationOnScreen(it) }
            Rect(loc[0], loc[1], loc[0] + v.width, loc[1] + v.height)
        }
        try {
            launcherApps.startMainActivity(e.component, e.user, bounds, null)
        } catch (t: Throwable) {
            val um = getSystemService(UserManager::class.java)
            if (e.user != Process.myUserHandle() && runCatching { um.isQuietModeEnabled(e.user) }.getOrDefault(false)) {
                // Espace en pause : l'écran d'accueil par défaut a le droit de le réactiver.
                runCatching { um.requestQuietModeEnabled(false, e.user) }
            } else {
                Toast.makeText(this, "Impossible d'ouvrir ${e.label}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showMenu(e: Entry, anchor: View, place: Place) {
        val pins = Prefs.pins(this)
        val dockKeys = Prefs.dock(this).orEmpty()
        PopupMenu(this, anchor).apply {
            if (place != Place.HOME && e.key !in pins) menu.add(0, 1, 0, "Ajouter à l'accueil")
            if (place == Place.HOME) menu.add(0, 2, 0, "Retirer de l'accueil")
            if (place != Place.DOCK && e.key !in dockKeys && dockKeys.size < DOCK_MAX) menu.add(0, 3, 1, "Ajouter au dock")
            if (place == Place.DOCK) menu.add(0, 4, 1, "Retirer du dock")
            menu.add(0, 5, 2, R.string.app_info)
            menu.add(0, 6, 3, R.string.delete)
            setOnMenuItemClickListener {
                when (it.itemId) {
                    1 -> { Prefs.setPins(this@HomeActivity, pins + e.key); renderHome() }
                    2 -> { Prefs.setPins(this@HomeActivity, pins - e.key); renderHome() }
                    3 -> { Prefs.setDock(this@HomeActivity, dockKeys + e.key); renderHome() }
                    4 -> { Prefs.setDock(this@HomeActivity, dockKeys - e.key); renderHome() }
                    5 -> runCatching { launcherApps.startAppDetailsActivity(e.component, e.user, null, null) }
                    6 -> uninstall(e)
                }
                true
            }
            show()
        }
    }

    /** Appui long sur une zone vide de l'accueil : fond d'écran et réglages. */
    private fun emptyAreaLongPress() = object : RecyclerView.SimpleOnItemTouchListener() {
        val detector = GestureDetector(this@HomeActivity, object : GestureDetector.SimpleOnGestureListener() {
            override fun onLongPress(ev: MotionEvent) {
                if (homeGrid.findChildViewUnder(ev.x, ev.y) != null) return
                PopupMenu(this@HomeActivity, homeGrid, android.view.Gravity.CENTER).apply {
                    menu.add(0, 3, 0, "Ajouter un widget")
                    if (Prefs.widgets(this@HomeActivity).isNotEmpty()) menu.add(0, 4, 1, "Retirer un widget")
                    menu.add(0, 1, 2, "Fond d'écran")
                    menu.add(0, 2, 3, "Réglages de l'écran d'accueil")
                    setOnMenuItemClickListener {
                        when (it.itemId) {
                            1 -> runCatching {
                                startActivity(Intent.createChooser(Intent(Intent.ACTION_SET_WALLPAPER), "Fond d'écran"))
                            }
                            2 -> startActivity(Intent(this@HomeActivity, MainActivity::class.java))
                            3 -> pickWidget()
                            4 -> removeWidgetDialog()
                        }
                        true
                    }
                    show()
                }
            }
        })

        override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
            detector.onTouchEvent(e); return false
        }
    }

    private fun uninstall(e: Entry) {
        val pkg = e.component.packageName
        if (e.user == Process.myUserHandle()) {
            runCatching { startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg"))) }
            return
        }
        if (!Shell.granted()) {
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

    // ---------- Grilles ----------

    private inner class GridAdapter(private val layout: Int, private val inDrawer: Boolean) :
        RecyclerView.Adapter<GridAdapter.VH>() {
        var items: List<Entry> = emptyList()
            set(v) { field = v; notifyDataSetChanged() }

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val icon: ImageView = v.findViewById(R.id.icon)
            val label: TextView = v.findViewById(R.id.label)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(layout, parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(h: VH, position: Int) {
            val e = items[position]
            h.icon.setImageDrawable(e.icon)
            h.label.text = e.label
            h.itemView.contentDescription = e.label
            h.itemView.setOnClickListener { launch(e, it) }
            h.itemView.setOnLongClickListener {
                showMenu(e, it, if (inDrawer) Place.DRAWER else Place.HOME); true
            }
        }
    }

    companion object {
        private const val DOCK_MAX = 5
        private const val WIDGET_HOST_ID = 1024
        private const val REQ_PICK_WIDGET = 1
        private const val REQ_CONFIG_WIDGET = 2
    }
}
