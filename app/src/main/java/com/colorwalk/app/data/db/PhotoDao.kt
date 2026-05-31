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

    @Query("SELECT DISTINCT colorName, colorHex FROM photos")
    fun getDistinctColors(): Flow<List<ColorSummary>>

    @Query("SELECT COUNT(*) FROM photos WHERE colorName = :colorName")
    suspend fun countByColor(colorName: String): Int

    @Query("SELECT dateTaken FROM photos WHERE dateTaken >= :midnightMs LIMIT 1")
    suspend fun getPhotoForDay(midnightMs: Long): Long?

    @Query("SELECT dateTaken FROM photos ORDER BY dateTaken DESC LIMIT 60")
    suspend fun getRecentPhotoDates(): List<Long>

    @Query("DELETE FROM photos WHERE id = :id")
    suspend fun deleteById(id: Long)
}

data class ColorSummary(
    val colorName: String,
    val colorHex: String
)
