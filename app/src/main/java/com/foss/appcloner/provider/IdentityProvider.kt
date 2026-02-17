package com.foss.appcloner.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import com.foss.appcloner.db.AppDatabase
import kotlinx.coroutines.runBlocking

/**
 * Content provider that serves identity JSON to running cloned apps.
 *
 * URI format:  content://com.foss.appcloner.identity/get/<clonePackageName>
 *
 * The hook code in each clone queries this URI on startup (and after
 * receiving a NEW_IDENTITY broadcast) to obtain fresh identity values.
 *
 * Returns a single-row cursor with columns: [package, identity_json]
 */
class IdentityProvider : ContentProvider() {

    private val uriMatcher = UriMatcher(UriMatcher.NO_MATCH).also {
        it.addURI(AUTHORITY, "get/*", URI_GET_IDENTITY)
    }

    override fun onCreate() = true

    override fun query(
        uri: Uri, projection: Array<String>?, selection: String?,
        selectionArgs: Array<String>?, sortOrder: String?
    ): Cursor? {
        if (uriMatcher.match(uri) != URI_GET_IDENTITY) return null
        val clonePackage = uri.lastPathSegment ?: return null

        val dao    = AppDatabase.getInstance(context!!).cloneDao()
        val entity = runBlocking { dao.getByPackage(clonePackage) } ?: return null

        val cursor = MatrixCursor(arrayOf(COL_PACKAGE, COL_IDENTITY_JSON))
        cursor.addRow(arrayOf(clonePackage, entity.identityJson))
        return cursor
    }

    override fun getType(uri: Uri): String = "vnd.android.cursor.item/identity"
    override fun insert(uri: Uri, values: ContentValues?) = null
    override fun delete(uri: Uri, sel: String?, args: Array<String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, sel: String?, args: Array<String>?) = 0

    companion object {
        const val AUTHORITY          = "com.foss.appcloner.identity"
        const val COL_PACKAGE        = "package"
        const val COL_IDENTITY_JSON  = "identity_json"
        private  const val URI_GET_IDENTITY = 1
    }
}
