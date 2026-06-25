package com.colorwalk.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "photos")
data class PhotoEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val filePath: String,
    val colorName: String,       // e.g. "Red"
    val colorHex: String,        // e.g. "#E53935"
    val dateTaken: Long,         // epoch millis
    val latitude: Double?,
    val longitude: Double?,
    val locationName: String?,   // reverse-geocoded label
    val dominantColorHex: String, // actual dominant color of the captured photo
    val description: String? = null  // user-written note; also written to EXIF ImageDescription
)
