package com.cj.tapblok.database

import app.untethered.BuildConfig
import app.untethered.R

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [BlockedApp::class, BlockedWebsite::class], version = 3, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {

    abstract fun blockedAppDao(): BlockedAppDao
    abstract fun blockedWebsiteDao(): BlockedWebsiteDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `blocked_websites` (
                        `domain` TEXT NOT NULL,
                        PRIMARY KEY(`domain`)
                    )
                    """.trimIndent()
                )
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `blocked_apps` ADD COLUMN `blockMode` TEXT NOT NULL DEFAULT 'HOME_AWARE'")
                db.execSQL("ALTER TABLE `blocked_websites` ADD COLUMN `blockMode` TEXT NOT NULL DEFAULT 'HOME_AWARE'")
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "app_database"
                )
                    .addMigrations(MIGRATION_1_2)
                    .addMigrations(MIGRATION_2_3)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
