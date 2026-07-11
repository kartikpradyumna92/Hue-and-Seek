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
    val description: String? = null,  // user-written note; also written to EXIF ImageDescription
    // Byte size of the ORIGINAL source file at import time (null for in-app captures
    // and legacy rows). The private copy is re-encoded, so its on-disk size can't
    // identify the source — this column is what lets import dedup distinguish a
    // re-import of the same photo from a different burst shot in the same second (M-4).
    val originalSizeBytes: Long? = null
)
