package com.isolapp.manager

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.os.Bundle

/** Étapes de provisionnement demandées par Android 10+ lors de la création du profil. */
class ProvisioningActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        when (intent.action) {
            "android.app.action.GET_PROVISIONING_MODE" -> setResult(
                RESULT_OK,
                Intent().putExtra(
                    DevicePolicyManager.EXTRA_PROVISIONING_MODE,
                    DevicePolicyManager.PROVISIONING_MODE_MANAGED_PROFILE
                )
            )
            "android.app.action.ADMIN_POLICY_COMPLIANCE" -> {
                Iso.ensureIsolatedSetup(this)
                setResult(RESULT_OK)
            }
            else -> setResult(RESULT_CANCELED)
        }
        finish()
    }
}

/** Lancée dans le nouveau profil une fois le provisionnement terminé. */
class ProvisionedActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Iso.ensureIsolatedSetup(this)
        startActivity(Intent(this, IsolatedActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }
}
