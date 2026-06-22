package com.colorwalk.app.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface PhotoDao {
    @Insert
    suspend fun insert(photo: PhotoEntity): Long

    @Query("SELECT * FROM photos ORDER BY dateTaken DESC")
    fun getAllPhotos(): Flow<List<PhotoEntity>>

    @Query("SELECT * FROM photos WHERE colorName = :colorName ORDER BY dateTaken DESC")
    fun getPhotosByColor(colorName: String): Flow<List<PhotoEntity>>

    @Query("SELECT colorName, colorHex FROM photos GROUP BY colorName ORDER BY COUNT(*) DESC")
    fun getDistinctColors(): Flow<List<ColorSummary>>

    @Query("SELECT COUNT(*) FROM photos WHERE dateTaken = :dateTaken")
    suspend fun countByDateTaken(dateTaken: Long): Int

    @Query("SELECT dateTaken FROM photos WHERE dateTaken >= :midnightMs AND dateTaken < :tomorrowMidnightMs LIMIT 1")
    suspend fun getPhotoForDay(midnightMs: Long, tomorrowMidnightMs: Long): Long?

    // No LIMIT: streaks are computed from every photo date. A LIMIT on photo
    // *rows* silently caps the streak (rows != days when a day has several photos).
    @Query("SELECT dateTaken FROM photos")
    suspend fun getAllPhotoDates(): List<Long>

    @Query("SELECT id FROM photos WHERE dateTaken >= :midnightMs AND dateTaken < :tomorrowMidnightMs AND filePath LIKE 'content://%' LIMIT 1")
    suspend fun getContentUriPhotoIdForDay(midnightMs: Long, tomorrowMidnightMs: Long): Long?

    @Query("UPDATE photos SET filePath = :newPath WHERE id = :id")
    suspend fun updateFilePath(id: Long, newPath: String)

    @Query("UPDATE photos SET latitude = :lat, longitude = :lon, locationName = :name WHERE id = :id")
    suspend fun updateLocation(id: Long, lat: Double?, lon: Double?, name: String?)

    @Query("SELECT * FROM photos")
    suspend fun getAllPhotosSnapshot(): List<PhotoEntity>

    @Query("DELETE FROM photos WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM photos WHERE id NOT IN (SELECT MIN(id) FROM photos GROUP BY filePath)")
    suspend fun deleteFilepathDuplicates()

}

data class ColorSummary(
    val colorName: String,
    val colorHex: String
)
