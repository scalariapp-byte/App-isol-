package com.isolapp.manager

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.CrossProfileApps
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch

/** Écran côté personnel. */
class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var btnCreate: MaterialButton
    private lateinit var btnManage: MaterialButton
    private lateinit var btnInstallApk: MaterialButton
    private lateinit var swMonitor: MaterialSwitch
    private lateinit var adapter: AppAdapter

    private val provision = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) {
            Toast.makeText(this, "Espace isolé créé", Toast.LENGTH_LONG).show()
        }
        refresh()
    }

    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Iso.isIsolatedSide(this)) {
            // Icône lancée depuis l'espace isolé (icône avec badge) : on ouvre le gestionnaire.
            Iso.ensureIsolatedSetup(this)
            startActivity(Intent(this, IsolatedActivity::class.java))
            finish()
            return
        }
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.status)
        btnCreate = findViewById(R.id.btnCreate)
        btnManage = findViewById(R.id.btnManage)
        btnInstallApk = findViewById(R.id.btnInstallApk)
        swMonitor = findViewById(R.id.swMonitor)

        adapter = AppAdapter(onAction = ::onAppAction)
        findViewById<RecyclerView>(R.id.list).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = this@MainActivity.adapter
        }

        btnCreate.setOnClickListener { createSpace() }
        btnManage.setOnClickListener { openManager() }
        btnInstallApk.setOnClickListener {
            try {
                startActivity(Intent(Iso.ACTION_INSTALL_APK))
            } catch (e: ActivityNotFoundException) {
                Toast.makeText(this, "Espace isolé indisponible", Toast.LENGTH_LONG).show()
            }
        }
        swMonitor.isChecked = Prefs.monitor(this)
        swMonitor.setOnCheckedChangeListener { _, on ->
            Prefs.setMonitor(this, on)
            if (on) {
                if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                        this, Manifest.permission.POST_NOTIFICATIONS
                    ) != PackageManager.PERMISSION_GRANTED
                ) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                MonitorService.start(this)
            } else {
                MonitorService.stop(this)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::status.isInitialized) refresh()
    }

    private fun refresh() {
        val profile = Iso.isolatedProfile(this)
        val has = profile != null
        status.setText(if (has) R.string.space_ok else R.string.no_space)
        btnCreate.visibility = if (has) View.GONE else View.VISIBLE
        listOf(btnManage, btnInstallApk, swMonitor).forEach {
            it.visibility = if (has) View.VISIBLE else View.GONE
        }
        if (!has) { adapter.rows = emptyList(); return }

        Thread {
            val pm = packageManager
            val isolated: Set<String> = runCatching {
                getSystemService(LauncherApps::class.java)
                    .getActivityList(null, profile).map { it.componentName.packageName }.toSet()
            }.getOrDefault(emptySet())
            val rows = pm.getInstalledApplications(0)
                .asSequence()
                .filter { it.packageName != packageName }
                .filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 }
                .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
                .map { ai ->
                    val inIso = ai.packageName in isolated
                    AppRow(
                        pkg = ai.packageName,
                        label = pm.getApplicationLabel(ai).toString(),
                        icon = runCatching { pm.getApplicationIcon(ai) }.getOrNull(),
                        sub = if (inIso) "Déjà présente dans l'espace isolé" else "Profil personnel",
                        actionText = getString(if (inIso) R.string.remove_personal else R.string.isolate),
                    )
                }
                .sortedBy { it.label.lowercase() }
                .toList()
            runOnUiThread { if (!isFinishing) adapter.rows = rows }
        }.start()
    }

    private fun onAppAction(row: AppRow) {
        if (row.actionText == getString(R.string.isolate)) {
            startActivity(IsolateActivity.intent(this, row.pkg))
        } else {
            runCatching { startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:${row.pkg}"))) }
        }
    }

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

    private fun openManager() {
        val profile = Iso.isolatedProfile(this) ?: return
        runCatching {
            getSystemService(CrossProfileApps::class.java)
                .startMainActivity(ComponentName(this, MainActivity::class.java), profile)
        }.onFailure {
            Toast.makeText(
                this, "Impossible d'ouvrir l'espace isolé (est-il en pause ?) : ${it.message}", Toast.LENGTH_LONG
            ).show()
        }
    }
}
