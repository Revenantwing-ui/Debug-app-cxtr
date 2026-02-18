package com.foss.appcloner.service

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.util.Log
import java.io.File

/**
 * Installs a locally-built APK using the PackageInstaller Session API.
 * Compatible with Android 8+ (no root required).
 *
 * CRITICAL FIX: session.fsync() must be called on the RAW OutputStream returned
 * by session.openWrite(), not on a BufferedOutputStream wrapper.  Calling it on
 * the wrapper causes a ClassCastException on some ROMs and always produces an
 * incorrect file-descriptor-based sync.
 */
object PackageInstallHelper {

    private const val TAG = "PackageInstallHelper"

    fun installApk(context: Context, apkFile: File) {
        require(apkFile.exists()) { "APK not found at ${apkFile.absolutePath}" }
        require(apkFile.length() > 0) { "APK file is empty: ${apkFile.absolutePath}" }

        val pi = context.packageManager.packageInstaller

        val params = PackageInstaller.SessionParams(
            PackageInstaller.SessionParams.MODE_FULL_INSTALL
        ).apply {
            setInstallReason(PackageManager.INSTALL_REASON_USER)
            // Tell the installer the exact size so it can pre-allocate
            setSize(apkFile.length())
        }

        val sessionId = pi.createSession(params)
        Log.d(TAG, "Created install session $sessionId for ${apkFile.name}")

        pi.openSession(sessionId).use { session ->
            // ── Write APK into session ───────────────────────────────────────
            // openWrite returns a raw FileOutputStream backed by the session fd.
            // We buffer the INPUT side only; fsync must use the raw output ref.
            val rawOutput = session.openWrite("base.apk", 0, apkFile.length())
            try {
                apkFile.inputStream().buffered(65_536).use { input ->
                    input.copyTo(rawOutput, bufferSize = 65_536)
                }
                // fsync MUST be called on the raw stream (not a BufferedOutputStream).
                // This flushes the kernel buffer to the session file before commit.
                session.fsync(rawOutput)
            } finally {
                rawOutput.close()
            }

            // ── Commit the session ────────────────────────────────────────────
            val broadcastIntent = Intent("com.foss.appcloner.INSTALL_STATUS").apply {
                setPackage(context.packageName)
                putExtra(PackageInstaller.EXTRA_SESSION_ID, sessionId)
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                sessionId,
                broadcastIntent,
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            session.commit(pendingIntent.intentSender)
            Log.d(TAG, "Session $sessionId committed, awaiting installer callback")
        }
    }
}
