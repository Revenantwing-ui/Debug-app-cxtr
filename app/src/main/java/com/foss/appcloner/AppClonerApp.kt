package com.foss.appcloner

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.foss.appcloner.db.AppDatabase

class AppClonerApp : Application() {
    val database: AppDatabase by lazy { AppDatabase.getInstance(this) }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL_CLONING, getString(R.string.channel_cloning), NotificationManager.IMPORTANCE_LOW))
            nm.createNotificationChannel(NotificationChannel(CHANNEL_IDENTITY, getString(R.string.channel_identity), NotificationManager.IMPORTANCE_DEFAULT))
        }
    }

    companion object {
        const val CHANNEL_CLONING  = "cloning_progress"
        const val CHANNEL_IDENTITY = "identity_update"
        const val IDENTITY_UPDATE_ACTION = "com.foss.appcloner.action.NEW_IDENTITY"
        const val IDENTITY_PERMISSION     = "com.foss.appcloner.permission.IDENTITY_UPDATE"
    }
}
