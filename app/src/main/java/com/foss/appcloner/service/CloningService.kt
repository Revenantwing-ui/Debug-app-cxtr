package com.foss.appcloner.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.foss.appcloner.AppClonerApp
import com.foss.appcloner.R
import com.foss.appcloner.cloner.ApkRepackager
import com.foss.appcloner.db.AppDatabase
import com.foss.appcloner.db.CloneEntity
import com.foss.appcloner.model.CloneConfig
import com.foss.appcloner.ui.MainActivity
import com.google.gson.Gson
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

class CloningService : Service() {

    private val binder = LocalBinder()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val nm get() = getSystemService(NotificationManager::class.java)
    private val gson = Gson()

    inner class LocalBinder : Binder() {
        fun getService() = this@CloningService
    }

    companion object {
        private const val NOTIF_ID = 1001
        
        // Static flow for the Dialog to observe logs
        private val _logFlow = MutableSharedFlow<String>(replay = 100)
        val logFlow: SharedFlow<String> = _logFlow

        fun log(msg: String) {
            _logFlow.tryEmit(msg)
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIF_ID, buildNotification("Starting…", 0))
    }

    fun cloneApp(sourceApkPath: String, config: CloneConfig, onComplete: (Boolean, String) -> Unit) {
        // Clear previous logs
        scope.launch { _logFlow.emit("--- New Cloning Session Started ---") }
        
        scope.launch {
            try {
                log("Initializing repackager...")
                val repackager = ApkRepackager(applicationContext)
                
                // Pass a logging lambda to Repackager
                val result = repackager.repackage(sourceApkPath, config) { step, pct ->
                    // Log to terminal
                    log("[$pct%] $step")
                    // Update notification
                    nm.notify(NOTIF_ID, buildNotification(step, pct))
                }

                log("Saving to database...")
                val dao = AppDatabase.getInstance(applicationContext).cloneDao()
                dao.insert(CloneEntity(
                    clonePackageName  = config.clonePackageName,
                    sourcePackageName = config.sourcePackageName,
                    cloneName         = config.cloneName.ifBlank { config.clonePackageName },
                    cloneNumber       = config.cloneNumber,
                    identityJson      = gson.toJson(config.identity),
                    configJson        = gson.toJson(config)
                ))

                log("Installing APK...")
                installApk(result.apkFile.absolutePath)

                log("SUCCESS: Cloned app created at ${result.apkFile.absolutePath}")
                
                withContext(Dispatchers.Main) { onComplete(true, result.apkFile.absolutePath) }
            } catch (e: Exception) {
                log("ERROR: ${e.message}")
                e.printStackTrace()
                withContext(Dispatchers.Main) { onComplete(false, e.message ?: "Unknown error") }
            } finally {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun installApk(apkPath: String) {
        PackageInstallHelper.installApk(applicationContext, java.io.File(apkPath))
    }

    private fun buildNotification(step: String, pct: Int): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, AppClonerApp.CHANNEL_CLONING)
            .setSmallIcon(R.drawable.ic_cloning)
            .setContentTitle(getString(R.string.cloning_in_progress))
            .setContentText(step)
            .setProgress(100, pct, pct == 0)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
