package com.foss.appcloner.util

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import com.foss.appcloner.model.AppInfo

object PackageUtils {

    /**
     * Returns all user-installed (and optionally system) apps on the device.
     */
    fun getInstalledApps(context: Context, includeSystem: Boolean = false): List<AppInfo> {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            PackageManager.GET_META_DATA.toLong()
        } else {
            PackageManager.GET_META_DATA.toLong()
        }

        val packages = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(flags))
        } else {
            @Suppress("DEPRECATION")
            pm.getInstalledApplications(PackageManager.GET_META_DATA)
        }

        return packages
            .filter { info ->
                val isSystem = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                // Always exclude ourselves
                info.packageName != context.packageName &&
                (includeSystem || !isSystem)
            }
            .mapNotNull { info ->
                runCatching {
                    val pkgInfo = pm.getPackageInfo(info.packageName, 0)
                    AppInfo(
                        packageName  = info.packageName,
                        appName      = pm.getApplicationLabel(info).toString(),
                        versionName  = pkgInfo.versionName ?: "?",
                        versionCode  = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                            pkgInfo.longVersionCode else pkgInfo.versionCode.toLong(),
                        apkPath      = info.sourceDir,
                        icon         = runCatching { pm.getApplicationIcon(info) }.getOrNull(),
                        isSystemApp  = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                    )
                }.getOrNull()
            }
            .sortedBy { it.appName.lowercase() }
    }

    /** Check whether a package is currently installed on the device. */
    fun isInstalled(context: Context, packageName: String): Boolean =
        runCatching {
            context.packageManager.getApplicationInfo(packageName, 0)
            true
        }.getOrDefault(false)

    /**
     * Build a unique clone package name by appending a numeric suffix
     * and ensuring there are no collisions with existing packages.
     */
    fun buildClonePackageName(
        context: Context,
        sourcePackage: String,
        cloneNumber: Int
    ): String {
        var candidate = "${sourcePackage}.clone${cloneNumber}"
        var n = cloneNumber
        while (isInstalled(context, candidate)) {
            n++
            candidate = "${sourcePackage}.clone$n"
        }
        return candidate
    }

    /** Return the APK path for an installed package, or null. */
    fun getApkPath(context: Context, packageName: String): String? =
        runCatching {
            context.packageManager.getApplicationInfo(packageName, 0).sourceDir
        }.getOrNull()
}
