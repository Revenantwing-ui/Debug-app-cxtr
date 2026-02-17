package com.foss.appcloner.ui

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import com.foss.appcloner.R
import com.foss.appcloner.databinding.ActivityCloneOptionsBinding
import com.foss.appcloner.identity.IdentityGenerator
import com.foss.appcloner.service.CloningService
import com.foss.appcloner.ui.viewmodel.CloneOptionsViewModel
import com.foss.appcloner.util.PackageUtils

class CloneOptionsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCloneOptionsBinding
    private val vm: CloneOptionsViewModel by viewModels()

    private var cloningService: CloningService? = null
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            cloningService = (binder as CloningService.LocalBinder).getService()
        }
        override fun onServiceDisconnected(name: ComponentName) { cloningService = null }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCloneOptionsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val sourcePackage = intent.getStringExtra(EXTRA_PACKAGE) ?: run { finish(); return }
        val appName       = intent.getStringExtra(EXTRA_APP_NAME) ?: sourcePackage
        val apkPath       = intent.getStringExtra(EXTRA_APK_PATH) ?: run { finish(); return }

        title = "Clone $appName"

        // Determine a unique clone package
        val cloneNumber  = 1
        val clonePackage = PackageUtils.buildClonePackageName(this, sourcePackage, cloneNumber)

        vm.initForPackage(sourcePackage, clonePackage, "$appName Clone")
        binding.etCloneName.setText("$appName Clone")
        binding.etClonePackage.setText(clonePackage)

        // Identity section
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

        vm.progress.observe(this) { (step, pct) ->
            binding.progressBar.progress = pct
            binding.tvProgress.text      = step
            binding.progressGroup.visibility = if (pct in 1..99) View.VISIBLE else View.GONE
        }

        // Notification option
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

        // Clone button
        binding.btnClone.setOnClickListener {
            val finalConfig = vm.buildFinalConfig().copy(
                cloneName        = binding.etCloneName.text.toString().ifBlank { appName },
                clonePackageName = binding.etClonePackage.text.toString().ifBlank { clonePackage }
            )
            startCloning(apkPath, finalConfig)
        }
    }

    private fun startCloning(apkPath: String, config: com.foss.appcloner.model.CloneConfig) {
        binding.btnClone.isEnabled = false
        val intent = Intent(this, CloningService::class.java)
        startForegroundService(intent)
        bindService(intent, connection, BIND_AUTO_CREATE)

        // Give service a moment to start, then invoke
        binding.root.postDelayed({
            cloningService?.cloneApp(apkPath, config) { success, msg ->
                runOnUiThread {
                    binding.btnClone.isEnabled = true
                    Toast.makeText(this,
                        if (success) "Clone created! Installing…" else "Error: $msg",
                        Toast.LENGTH_LONG
                    ).show()
                    if (success) finish()
                }
            }
        }, 500)
    }

    override fun onSupportNavigateUp() = true.also { finish() }

    companion object {
        const val EXTRA_PACKAGE  = "extra_package"
        const val EXTRA_APP_NAME = "extra_app_name"
        const val EXTRA_APK_PATH = "extra_apk_path"
    }
}
