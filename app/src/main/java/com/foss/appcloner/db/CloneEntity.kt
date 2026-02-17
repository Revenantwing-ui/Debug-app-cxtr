package com.foss.appcloner.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "clones")
data class CloneEntity(
    @PrimaryKey val clonePackageName: String,
    val sourcePackageName: String,
    val cloneName: String,
    val cloneNumber: Int,
    val identityJson: String,           // JSON-serialised Identity
    val configJson: String,             // JSON-serialised CloneConfig (minus Identity)
    val createdAt: Long = System.currentTimeMillis(),
    val lastIdentityRefresh: Long = 0L,
    val isInstalled: Boolean = false
)
