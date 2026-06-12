package com.colorwalk.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

// SCHEMA CHANGE RULE: increment `version` + add a Migration object below.
// Never use fallbackToDestructiveMigration — it silently wipes all user data (streak + photos).
@Database(entities = [PhotoEntity::class], version = 1, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun photoDao(): PhotoDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "colorwalk.db"
                )
                // Add Migration(oldVer, newVer) { ... } objects here on every schema change.
                // TRUNCATE keeps everything in the single .db file: Auto Backup snapshots
                // the files independently, so a .db/.db-wal pair from different moments
                // restores an inconsistent database (B10). Write volume here is a few
                // rows per day — WAL buys nothing.
                .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
                .build().also { INSTANCE = it }
            }
    }
}
