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

class CloningService : Service() {
    private val binder = LocalBinder()
    private val scope   = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val nm get() = getSystemService(NotificationManager::class.java)
    private val gson = Gson()

    inner class LocalBinder : Binder() { fun getService() = this@CloningService }

    override fun onBind(intent: Intent?): IBinder = binder
    override fun onCreate() { super.onCreate(); startForeground(NOTIF_ID, buildNotification("Starting...", 0)) }

    fun cloneApp(sourceApkPath: String, config: CloneConfig, onComplete: (Boolean, String) -> Unit) {
        scope.launch {
            try {
                val repackager = ApkRepackager(applicationContext)
                val result = repackager.repackage(sourceApkPath, config) { step, pct ->
                    nm.notify(NOTIF_ID, buildNotification(step, pct))
                }
                
                // Save to DB
                val dao = AppDatabase.getInstance(applicationContext).cloneDao()
                dao.insert(CloneEntity(
                    clonePackageName  = config.clonePackageName,
                    sourcePackageName = config.sourcePackageName,
                    cloneName         = config.cloneName.ifBlank { config.clonePackageName },
                    cloneNumber       = config.cloneNumber,
                    identityJson      = gson.toJson(config.identity),
                    configJson        = gson.toJson(config)
                ))

                // Trigger Install
                installApk(result.apkFile.absolutePath)
                
                withContext(Dispatchers.Main) { onComplete(true, result.apkFile.absolutePath) }
                stopForeground(STOP_FOREGROUND_REMOVE)
            } catch (e: Exception) {
                e.printStackTrace()
                // Show error in notification so user sees it even if app is closed
                nm.notify(NOTIF_ID, buildErrorNotification(e.message ?: "Unknown error"))
                withContext(Dispatchers.Main) { onComplete(false, e.message ?: "Unknown error") }
                stopForeground(STOP_FOREGROUND_DETACH) 
            } finally {
                stopSelf()
            }
        }
    }

    private fun installApk(apkPath: String) {
        PackageInstallHelper.installApk(applicationContext, java.io.File(apkPath))
    }

    private fun buildNotification(step: String, pct: Int): Notification {
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, AppClonerApp.CHANNEL_CLONING)
            .setSmallIcon(R.drawable.ic_cloning)
            .setContentTitle(getString(R.string.cloning_in_progress))
            .setContentText(step)
            .setProgress(100, pct, pct == 0)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun buildErrorNotification(error: String): Notification {
        return NotificationCompat.Builder(this, AppClonerApp.CHANNEL_CLONING)
            .setSmallIcon(R.drawable.ic_cloning)
            .setContentTitle("Cloning Failed")
            .setContentText(error)
            .setStyle(NotificationCompat.BigTextStyle().bigText(error))
            .setAutoCancel(true)
            .build()
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }
    companion object { private const val NOTIF_ID = 1001 }
}
