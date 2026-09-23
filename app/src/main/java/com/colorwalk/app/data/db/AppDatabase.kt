package com.colorwalk.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

// SCHEMA CHANGE RULE: increment `version` + add a Migration object below.
// Never use fallbackToDestructiveMigration — it silently wipes all user data (streak + photos).
@Database(entities = [PhotoEntity::class], version = 4, exportSchema = true)
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

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // T-1: the day a photo counts toward the streak is now frozen at
                // capture/import time, in the zone the user was in THEN, instead of
                // being re-derived from dateTaken on every read using the device's
                // CURRENT zone — the latter let travel (device zone auto-updates
                // mid-trip) retroactively reclassify an already-captured photo onto a
                // different calendar day and break an otherwise-unbroken streak.
                // Backfill (BUG-024): "ColorWalk_yyyyMMdd_…" filenames were formatted
                // in the zone the photo was taken in, so their date IS the photo's
                // local day — use it. Only rows without a parseable name (legacy
                // photo_<id>.jpg / content:// rows) fall back to the zone the phone
                // is in during the upgrade, which could be a travel zone. Must agree
                // with GallerySynchronizer.dayIndexFromFilename. julianday() yields
                // NULL for an invalid date, so COALESCE also covers "20261340".
                db.execSQL("ALTER TABLE photos ADD COLUMN dayIndex INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    """UPDATE photos SET dayIndex = COALESCE(
                           CASE WHEN instr(filePath, 'ColorWalk_') > 0
                                 AND substr(filePath, instr(filePath, 'ColorWalk_') + 10, 8)
                                     GLOB '[0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9]'
                                THEN CAST(julianday(
                                         substr(filePath, instr(filePath, 'ColorWalk_') + 10, 4) || '-' ||
                                         substr(filePath, instr(filePath, 'ColorWalk_') + 14, 2) || '-' ||
                                         substr(filePath, instr(filePath, 'ColorWalk_') + 16, 2)
                                     ) - 2440587.5 AS INTEGER)
                           END,
                           CAST(strftime('%s', dateTaken / 1000, 'unixepoch', 'localtime') AS INTEGER) / 86400
                       )"""
                )
            }
        }

        fun getInstance(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "colorwalk.db"
                )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                // TRUNCATE keeps everything in the single .db file: Auto Backup snapshots
                // the files independently, so a .db/.db-wal pair from different moments
                // restores an inconsistent database (B10). Write volume here is a few
                // rows per day — WAL buys nothing.
                .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
                .build().also { INSTANCE = it }
            }
    }
}
