package com.foss.appcloner.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import com.foss.appcloner.R
import com.foss.appcloner.databinding.ActivityCloneOptionsBinding
import com.foss.appcloner.model.CloneConfig
import com.foss.appcloner.service.CloningService
import com.foss.appcloner.ui.viewmodel.CloneOptionsViewModel
import com.foss.appcloner.util.PackageUtils

class CloneOptionsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCloneOptionsBinding
    private val vm: CloneOptionsViewModel by viewModels()

    // ── Service binding ───────────────────────────────────────────────────────
    private var cloningService: CloningService? = null
    private var pendingClone: (() -> Unit)? = null   // queued call if not yet bound

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            Log.d(TAG, "Service connected")
            cloningService = (binder as CloningService.LocalBinder).getService()
            // KEY FIX: If a clone was requested before the connection completed, run it now.
            pendingClone?.invoke()
            pendingClone = null
        }
        override fun onServiceDisconnected(name: ComponentName) {
            Log.w(TAG, "Service disconnected")
            cloningService = null
        }
    }
    private var serviceBound = false

    // ─────────────────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCloneOptionsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val sourcePackage = intent.getStringExtra(EXTRA_PACKAGE)  ?: run { finish(); return }
        val appName       = intent.getStringExtra(EXTRA_APP_NAME) ?: sourcePackage
        val apkPath       = intent.getStringExtra(EXTRA_APK_PATH) ?: run { finish(); return }

        title = getString(R.string.clone_options_title, appName)

        val cloneNumber  = 1
        val clonePackage = PackageUtils.buildClonePackageName(this, sourcePackage, cloneNumber)

        vm.initForPackage(sourcePackage, clonePackage, "$appName Clone")
        binding.etCloneName.setText("$appName Clone")
        binding.etClonePackage.setText(clonePackage)

        // ── Identity section ─────────────────────────────────────────────────
        binding.switchNewIdentity.setOnCheckedChangeListener { _, checked ->
            binding.identityGroup.visibility = if (checked) View.VISIBLE else View.GONE
            vm.updateConfig { it.copy(enableNewIdentity = checked) }
            if (checked) vm.regenerateIdentity()
        }
        binding.btnRefreshIdentity.setOnClickListener { vm.regenerateIdentity() }

        vm.identity.observe(this) { id ->
            binding.tvIdentitySummary.text =
                "IMEI: ${id.imei.take(15)}\n" +
                "Android ID: ${id.androidId}\n" +
                "Device: ${id.manufacturer} ${id.model}\n" +
                "MAC: ${id.wifiMacAddress}\n" +
                "Battery: ${id.batteryLevel}%"
        }

        // ── Options ──────────────────────────────────────────────────────────
        binding.switchShowNotification.setOnCheckedChangeListener { _, checked ->
            vm.updateConfig { it.copy(showIdentityNotification = checked) }
            binding.switchAutoRestart.isEnabled = checked
        }
        binding.switchAutoRestart.setOnCheckedChangeListener { _, checked ->
            vm.updateConfig { it.copy(autoRestartOnNewIdentity = checked) }
        }
        binding.switchClearCache.setOnCheckedChangeListener { _, checked ->
            vm.updateConfig { it.copy(clearCacheOnNewIdentity = checked) }
        }
        binding.switchDeleteData.setOnCheckedChangeListener { _, checked ->
            vm.updateConfig { it.copy(deleteDataOnNewIdentity = checked) }
        }

        // ── Progress ─────────────────────────────────────────────────────────
        vm.progress.observe(this) { (step, pct) ->
            binding.progressBar.progress = pct
            binding.tvProgress.text      = step
            binding.progressGroup.visibility = if (pct in 1..99) View.VISIBLE else View.GONE
        }

        // ── Clone button ─────────────────────────────────────────────────────
        binding.btnClone.setOnClickListener {
            val finalConfig = vm.buildFinalConfig().copy(
                cloneName        = binding.etCloneName.text.toString().ifBlank { appName },
                clonePackageName = binding.etClonePackage.text.toString().ifBlank { clonePackage }
            )
            // [FIX] Separated the start command and the dialog display logic
            startCloning(apkPath, finalConfig)
            CloningConsoleDialog().show(supportFragmentManager, CloningConsoleDialog.TAG)
        }
    }

    /**
     * Start the CloningService and clone the app.
     *
     * KEY FIX: The old implementation used postDelayed(500ms) hoping that
     * onServiceConnected would fire first — a race condition that silently
     * fails if the service is slow to bind (common on low-end devices or
     * when the system is busy).
     *
     * New approach: queue the work in [pendingClone].  If the service is
     * already connected, run immediately.  Otherwise onServiceConnected()
     * will drain the queue as soon as binding completes.
     */
    private fun startCloning(apkPath: String, config: CloneConfig) {
        binding.btnClone.isEnabled = false
        binding.progressGroup.visibility = View.VISIBLE
        binding.tvProgress.text = getString(R.string.starting_service)

        val serviceIntent = Intent(this, CloningService::class.java)

        val doClone = {
            val svc = cloningService
            if (svc == null) {
                // Should never happen after the fix, but surface it clearly
                runOnUiThread {
                    binding.btnClone.isEnabled = true
                    Toast.makeText(this,
                        "Service not ready — please try again", Toast.LENGTH_LONG).show()
                }
            } else {
                svc.cloneApp(apkPath, config) { success, msg ->
                    runOnUiThread {
                        binding.btnClone.isEnabled = true
                        binding.progressGroup.visibility = View.GONE
                        Toast.makeText(this,
                            if (success) getString(R.string.clone_success)
                            else         getString(R.string.clone_error, msg),
                            Toast.LENGTH_LONG
                        ).show()
                        if (success) finish()
                    }
                }
            }
        }

        if (cloningService != null) {
            // Already bound from a previous click
            doClone()
        } else {
            // Queue and start service
            pendingClone = doClone
            startForegroundService(serviceIntent)
            bindService(serviceIntent, connection, Context.BIND_AUTO_CREATE)
            serviceBound = true
        }
    }

    override fun onDestroy() {
        if (serviceBound) {
            unbindService(connection)
            serviceBound = false
        }
        super.onDestroy()
    }

    override fun onSupportNavigateUp() = true.also { finish() }

    companion object {
        private const val TAG           = "CloneOptionsActivity"
        const val EXTRA_PACKAGE         = "extra_package"
        const val EXTRA_APP_NAME        = "extra_app_name"
        const val EXTRA_APK_PATH        = "extra_apk_path"
    }
}
