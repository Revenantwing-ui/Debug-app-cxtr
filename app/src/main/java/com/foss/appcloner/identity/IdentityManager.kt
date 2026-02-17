package com.foss.appcloner.identity

import android.content.Context
import android.content.Intent
import com.foss.appcloner.AppClonerApp
import com.foss.appcloner.db.AppDatabase
import com.foss.appcloner.db.CloneEntity
import com.foss.appcloner.model.Identity
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Central manager for clone identities.
 * Generates, persists and broadcasts updated identities.
 */
class IdentityManager(private val context: Context) {

    private val dao  = AppDatabase.getInstance(context).cloneDao()
    private val gson = Gson()

    /** Generate and persist a fresh identity for [clonePackageName]. */
    suspend fun generateNewIdentity(
        clonePackageName: String,
        clearCache: Boolean = false,
        deleteAppData: Boolean = false
    ): Identity = withContext(Dispatchers.IO) {
        val identity = IdentityGenerator.generate()
        val entity = dao.getByPackage(clonePackageName)
            ?: error("Clone $clonePackageName not found in database")

        dao.updateIdentity(clonePackageName, gson.toJson(identity))

        // Deliver new identity to the running clone via broadcast
        broadcastIdentityUpdate(
            clonePackageName = clonePackageName,
            identity         = identity,
            clearCache       = clearCache,
            deleteAppData    = deleteAppData
        )

        identity
    }

    /** Retrieve the current persisted identity for [clonePackageName]. */
    suspend fun getIdentity(clonePackageName: String): Identity? =
        withContext(Dispatchers.IO) {
            val entity = dao.getByPackage(clonePackageName) ?: return@withContext null
            if (entity.identityJson.isBlank()) null
            else runCatching { gson.fromJson(entity.identityJson, Identity::class.java) }.getOrNull()
        }

    /** Serialise an [Identity] to JSON (used by the content provider). */
    fun toJson(identity: Identity): String = gson.toJson(identity)

    /** Deserialise an [Identity] from JSON. */
    fun fromJson(json: String): Identity? =
        runCatching { gson.fromJson(json, Identity::class.java) }.getOrNull()

    // ─── Private ──────────────────────────────────────────────────────────────

    private fun broadcastIdentityUpdate(
        clonePackageName: String,
        identity: Identity,
        clearCache: Boolean,
        deleteAppData: Boolean
    ) {
        val intent = Intent(AppClonerApp.IDENTITY_UPDATE_ACTION).apply {
            setPackage(clonePackageName)
            putExtra(EXTRA_IDENTITY_JSON,  gson.toJson(identity))
            putExtra(EXTRA_CLEAR_CACHE,    clearCache)
            putExtra(EXTRA_DELETE_DATA,    deleteAppData)
        }
        context.sendBroadcast(intent, AppClonerApp.IDENTITY_PERMISSION)
    }

    companion object {
        const val EXTRA_IDENTITY_JSON = "identity_json"
        const val EXTRA_CLEAR_CACHE   = "clear_cache"
        const val EXTRA_DELETE_DATA   = "delete_app_data"
    }
}
