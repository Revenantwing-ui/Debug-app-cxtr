package com.foss.appcloner.model

import android.graphics.drawable.Drawable

data class AppInfo(
    val packageName: String,
    val appName: String,
    val versionName: String,
    val versionCode: Long,
    val apkPath: String,
    val icon: Drawable?,
    val isSystemApp: Boolean = false
)
