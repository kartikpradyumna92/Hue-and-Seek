package com.colorwalk.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

// SCHEMA CHANGE RULE: increment `version` + add a Migration object below.
// Never use fallbackToDestructiveMigration — it silently wipes all user data (streak + photos).
@Database(entities = [PhotoEntity::class], version = 3, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun photoDao(): PhotoDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE photos ADD COLUMN description TEXT")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Original source-file size recorded at import (M-4 dedup); NULL for
                // captures and all pre-existing rows.
                db.execSQL("ALTER TABLE photos ADD COLUMN originalSizeBytes INTEGER")
            }
        }

        fun getInstance(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "colorwalk.db"
                )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                // TRUNCATE keeps everything in the single .db file: Auto Backup snapshots
                // the files independently, so a .db/.db-wal pair from different moments
                // restores an inconsistent database (B10). Write volume here is a few
                // rows per day — WAL buys nothing.
                .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
                .build().also { INSTANCE = it }
            }
    }
}
