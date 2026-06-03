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
                .build().also { INSTANCE = it }
            }
    }
}
