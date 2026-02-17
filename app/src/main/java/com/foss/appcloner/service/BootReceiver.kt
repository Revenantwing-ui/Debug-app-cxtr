package com.foss.appcloner.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.foss.appcloner.util.PackageUtils
import com.foss.appcloner.db.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Runs after device boot – reconciles clone DB with installed packages. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        CoroutineScope(Dispatchers.IO).launch {
            val dao = AppDatabase.getInstance(context).cloneDao()
            val clones = dao.getAll()
            for (clone in clones) {
                val installed = PackageUtils.isInstalled(context, clone.clonePackageName)
                dao.setInstalled(clone.clonePackageName, installed)
            }
        }
    }
}
