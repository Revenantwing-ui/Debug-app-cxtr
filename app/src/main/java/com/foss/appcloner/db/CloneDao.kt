package com.foss.appcloner.db

import androidx.lifecycle.LiveData
import androidx.room.*

@Dao
interface CloneDao {
    @Query("SELECT * FROM clones ORDER BY createdAt DESC")
    fun observeAll(): LiveData<List<CloneEntity>>

    @Query("SELECT * FROM clones ORDER BY createdAt DESC")
    suspend fun getAll(): List<CloneEntity>

    @Query("SELECT * FROM clones WHERE clonePackageName = :pkg LIMIT 1")
    suspend fun getByPackage(pkg: String): CloneEntity?

    @Query("SELECT * FROM clones WHERE sourcePackageName = :src ORDER BY cloneNumber ASC")
    suspend fun getBySource(src: String): List<CloneEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: CloneEntity)

    @Update
    suspend fun update(entity: CloneEntity)

    @Delete
    suspend fun delete(entity: CloneEntity)

    @Query("DELETE FROM clones WHERE clonePackageName = :pkg")
    suspend fun deleteByPackage(pkg: String)

    @Query("UPDATE clones SET identityJson = :json, lastIdentityRefresh = :ts WHERE clonePackageName = :pkg")
    suspend fun updateIdentity(pkg: String, json: String, ts: Long = System.currentTimeMillis())

    @Query("UPDATE clones SET isInstalled = :installed WHERE clonePackageName = :pkg")
    suspend fun setInstalled(pkg: String, installed: Boolean)
}
