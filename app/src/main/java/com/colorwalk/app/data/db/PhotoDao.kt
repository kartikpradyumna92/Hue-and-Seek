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

    // Import dedup (M-4): EXIF dates are second-precision while MediaStore dates
    // carry millis, so duplicates are hunted at second granularity and then
    // disambiguated by original source size in the repository.
    @Query("SELECT * FROM photos WHERE dateTaken / 1000 = :epochSecond")
    suspend fun getByDateTakenSecond(epochSecond: Long): List<PhotoEntity>

    @Query("SELECT COUNT(*) FROM photos WHERE colorName = :colorName")
    suspend fun countByColor(colorName: String): Int

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

    @Query("UPDATE photos SET description = :description WHERE id = :id")
    suspend fun updateDescription(id: Long, description: String?)

    @Query("UPDATE photos SET latitude = :lat, longitude = :lon, locationName = :name WHERE id = :id")
    suspend fun updateLocation(id: Long, lat: Double?, lon: Double?, name: String?)

    @Query("SELECT * FROM photos")
    suspend fun getAllPhotosSnapshot(): List<PhotoEntity>

    @Query("SELECT * FROM photos WHERE id = :id")
    suspend fun getById(id: Long): PhotoEntity?

    // Donor row for coordinate inheritance when naming a photo's location (M-7):
    // any OTHER photo already carrying this name with a real GPS fix (present and
    // not the (0,0) "null island" bad fix).
    @Query(
        """SELECT * FROM photos
           WHERE locationName = :locationName AND id != :excludeId
             AND latitude IS NOT NULL AND longitude IS NOT NULL
             AND (ABS(latitude) >= 0.001 OR ABS(longitude) >= 0.001)
           LIMIT 1"""
    )
    suspend fun getLocationDonor(locationName: String, excludeId: Long): PhotoEntity?

    @Query("DELETE FROM photos WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM photos WHERE id NOT IN (SELECT MIN(id) FROM photos GROUP BY filePath)")
    suspend fun deleteFilepathDuplicates()

}

data class ColorSummary(
    val colorName: String,
    val colorHex: String
)
