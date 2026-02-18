package com.foss.appcloner.util

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import com.foss.appcloner.model.AppInfo

object PackageUtils {

    /**
     * Returns installed apps.
     * CHANGED: 'includeSystem' now defaults to TRUE so apps like Calculator/Chrome appear.
     */
    fun getInstalledApps(context: Context, includeSystem: Boolean = true): List<AppInfo> {
        val pm = context.packageManager
        
        // Use GET_META_DATA to ensure we get basic info
        val flags = PackageManager.GET_META_DATA

        val packages = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(flags.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getInstalledApplications(flags)
        }

        return packages
            .filter { info ->
                // 1. Always exclude our own app (the cloner itself)
                if (info.packageName == context.packageName) return@filter false

                // 2. Calculate if it is a system app
                val isSystem = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                val isUpdatedSystem = (info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0

                // 3. Filtering logic:
                // - If includeSystem is true, show everything.
                // - If includeSystem is false, show ONLY user apps OR updated system apps (like Gmail updates).
                if (includeSystem) true else (!isSystem || isUpdatedSystem)
            }
            .mapNotNull { info ->
                runCatching {
                    val pkgInfo = pm.getPackageInfo(info.packageName, 0)
                    
                    // Retrieve icon safely
                    val icon = try { pm.getApplicationIcon(info) } catch (e: Exception) { null }
                    
                    // Retrieve label safely
                    val label = try { pm.getApplicationLabel(info).toString() } catch (e: Exception) { info.packageName }

                    AppInfo(
                        packageName  = info.packageName,
                        appName      = label,
                        versionName  = pkgInfo.versionName ?: "1.0",
                        versionCode  = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                            pkgInfo.longVersionCode else pkgInfo.versionCode.toLong(),
                        apkPath      = info.sourceDir,
                        icon         = icon,
                        isSystemApp  = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                    )
                }.getOrNull()
            }
            .sortedBy { it.appName.lowercase() }
    }

    fun isInstalled(context: Context, packageName: String): Boolean =
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                context.packageManager.getPackageInfo(packageName, 0)
            }
            true
        }.getOrDefault(false)

    fun buildClonePackageName(context: Context, sourcePackage: String, cloneNumber: Int): String {
        // Simple strategy: com.example.app -> com.example.app.clone1
        var candidate = "$sourcePackage.clone$cloneNumber"
        var n = cloneNumber
        while (isInstalled(context, candidate)) {
            n++
            candidate = "$sourcePackage.clone$n"
        }
        return candidate
    }

    fun getApkPath(context: Context, packageName: String): String? =
        runCatching {
            val appInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
            } else {
                context.packageManager.getApplicationInfo(packageName, 0)
            }
            appInfo.sourceDir
        }.getOrNull()
}
