package com.cj.tapblok.database

import app.olauncher.BuildConfig
import app.olauncher.R

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface BlockedWebsiteDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(blockedWebsite: BlockedWebsite)

    @Delete
    suspend fun delete(blockedWebsite: BlockedWebsite)

    @Query("UPDATE blocked_websites SET blockMode = :blockMode WHERE domain = :domain")
    suspend fun updateBlockMode(domain: String, blockMode: String)

    @Query("SELECT * FROM blocked_websites ORDER BY domain COLLATE NOCASE ASC")
    fun getAllBlockedWebsites(): Flow<List<BlockedWebsite>>

    @Query("SELECT * FROM blocked_websites ORDER BY domain COLLATE NOCASE ASC")
    suspend fun getAllBlockedWebsitesList(): List<BlockedWebsite>
}
