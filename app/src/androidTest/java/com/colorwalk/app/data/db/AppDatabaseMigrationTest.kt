package com.colorwalk.app.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.colorwalk.app.domain.StreakCalculator
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/**
 * BUG-028: every upgrade path to the current schema, run against the exported
 * schemas (app/schemas, wired as androidTest assets). The app deliberately has no
 * destructive fallback, so a broken migration would crash every upgrading user on
 * launch — these must pass before any schema change ships.
 */
@RunWith(AndroidJUnit4::class)
class AppDatabaseMigrationTest {

    private val dbName = "migration-test"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java
    )

    private val allMigrations = arrayOf(
        AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4
    )

    // 2026-09-22 22:20 local — late evening, the case BUG-024 is about.
    private val lateEvening = LocalDate.of(2026, 9, 22)
        .atTime(22, 20).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
    private val sept22 = LocalDate.of(2026, 9, 22).toEpochDay().toInt()

    private fun SupportSQLiteDatabase.dayIndexOf(filePath: String): Int =
        query("SELECT dayIndex FROM photos WHERE filePath = ?", arrayOf(filePath)).use {
            it.moveToFirst(); it.getInt(0)
        }

    @Test
    fun migrate3To4_backfillsDayIndexFromTheFilenameDate() {
        helper.createDatabase(dbName, 3).apply {
            insertV3("/data/files/photos/ColorWalk_20260922_222000_123.jpg", lateEvening)
            // Same instant, but the name says the 21st: the filename (written in the
            // zone the photo was taken in) must win over re-deriving from dateTaken.
            insertV3("/data/files/photos/ColorWalk_20260921_222000_0.jpg", lateEvening)
            close()
        }
        val db = helper.runMigrationsAndValidate(dbName, 4, true, AppDatabase.MIGRATION_3_4)

        assertEquals(sept22, db.dayIndexOf("/data/files/photos/ColorWalk_20260922_222000_123.jpg"))
        assertEquals(sept22 - 1, db.dayIndexOf("/data/files/photos/ColorWalk_20260921_222000_0.jpg"))
    }

    @Test
    fun migrate3To4_rowsWithoutADatedFilename_fallBackToTheDeviceZone() {
        val legacy = listOf(
            "/data/files/photos/photo_17.jpg",
            "content://media/external/images/media/42",
            "/data/files/photos/ColorWalk_20261340_120000_0.jpg"   // invalid date
        )
        helper.createDatabase(dbName, 3).apply {
            legacy.forEach { insertV3(it, lateEvening) }
            close()
        }
        val db = helper.runMigrationsAndValidate(dbName, 4, true, AppDatabase.MIGRATION_3_4)

        // SQLite 'localtime' must agree with java.time's zone rules on this device.
        val expected = StreakCalculator.epochMillisToDayIndex(lateEvening)
        legacy.forEach { assertEquals(it, expected, db.dayIndexOf(it)) }
    }

    @Test
    fun migrate1To4_keepsEveryRowAndItsData() {
        helper.createDatabase(dbName, 1).apply {
            execSQL(
                """INSERT INTO photos (filePath, colorName, colorHex, dateTaken, latitude,
                   longitude, locationName, dominantColorHex)
                   VALUES ('/p/ColorWalk_20260922_222000_0.jpg', 'Red', '#E53935', $lateEvening,
                   38.72, -9.14, 'Lisbon', '#D32F2F')"""
            )
            close()
        }
        val db = helper.runMigrationsAndValidate(dbName, 4, true, *allMigrations)

        db.query(
            "SELECT colorName, locationName, latitude, description, originalSizeBytes, dayIndex FROM photos"
        ).use { c ->
            assertEquals(1, c.count)
            c.moveToFirst()
            assertEquals("Red", c.getString(0))
            assertEquals("Lisbon", c.getString(1))
            assertEquals(38.72, c.getDouble(2), 1e-9)
            assertEquals(true, c.isNull(3))   // description added in v2
            assertEquals(true, c.isNull(4))   // originalSizeBytes added in v3
            assertEquals(sept22, c.getInt(5))
        }
    }

    @Test
    fun migrate2To4_preservesNotes() {
        helper.createDatabase(dbName, 2).apply {
            execSQL(
                """INSERT INTO photos (filePath, colorName, colorHex, dateTaken, latitude,
                   longitude, locationName, dominantColorHex, description)
                   VALUES ('/p/ColorWalk_20260922_222000_0.jpg', 'Blue', '#1E88E5', $lateEvening,
                   NULL, NULL, NULL, '#1565C0', 'harbor at dusk')"""
            )
            close()
        }
        val db = helper.runMigrationsAndValidate(
            dbName, 4, true, AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4
        )
        db.query("SELECT description, dayIndex FROM photos").use { c ->
            c.moveToFirst()
            assertEquals("harbor at dusk", c.getString(0))
            assertEquals(sept22, c.getInt(1))
        }
    }

    private fun SupportSQLiteDatabase.insertV3(filePath: String, dateTaken: Long) {
        execSQL(
            """INSERT INTO photos (filePath, colorName, colorHex, dateTaken, latitude, longitude,
               locationName, dominantColorHex, description, originalSizeBytes)
               VALUES (?, 'Red', '#E53935', ?, NULL, NULL, NULL, '#D32F2F', NULL, NULL)""",
            arrayOf<Any>(filePath, dateTaken)
        )
    }
}
