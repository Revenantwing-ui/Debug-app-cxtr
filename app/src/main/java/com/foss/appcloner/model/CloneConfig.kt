package com.foss.appcloner.model

data class CloneConfig(
    val sourcePackageName: String,
    val clonePackageName: String          = "${sourcePackageName}.clone1",
    val cloneName: String                 = "",
    val cloneNumber: Int                  = 1,
    val enableNewIdentity: Boolean        = false,
    val identity: Identity                = Identity(),
    val changeLocale: Boolean             = false,
    val locale: String                    = "",
    val showIdentityNotification: Boolean = false,
    val autoRestartOnNewIdentity: Boolean = false,
    val clearCacheOnNewIdentity: Boolean  = false,
    val deleteDataOnNewIdentity: Boolean  = false,
    val trustAllCertificates: Boolean     = false,
    val disableNetworking: Boolean        = false,
    val customLabel: String               = "",
    val randomizeBuildProps: Boolean      = false,
    val fakeBattery: Boolean              = false
)
