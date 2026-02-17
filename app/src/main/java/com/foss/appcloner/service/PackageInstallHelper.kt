package com.foss.appcloner.service

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import java.io.File

/**
 * Installs an APK using the PackageInstaller session API.
 * Works on Android 14 without root.
 */
object PackageInstallHelper {

    fun installApk(context: Context, apkFile: File) {
        val pi = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            .apply { setInstallReason(android.content.pm.PackageManager.INSTALL_REASON_USER) }

        val sessionId = pi.createSession(params)
        pi.openSession(sessionId).use { session ->
            apkFile.inputStream().buffered().use { input ->
                session.openWrite("base.apk", 0, apkFile.length()).buffered().use { output ->
                    input.copyTo(output)
                    session.fsync(output)
                }
            }

            val pi2 = android.app.PendingIntent.getBroadcast(
                context, sessionId,
                Intent("com.foss.appcloner.INSTALL_STATUS"),
                android.app.PendingIntent.FLAG_MUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
            )
            session.commit(pi2.intentSender)
        }
    }
}
