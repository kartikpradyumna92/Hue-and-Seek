package com.colorwalk.app.ui.components

import android.content.Context
import android.net.Uri
import androidx.compose.ui.graphics.Color
import coil.request.ImageRequest
import java.io.File

/**
 * Builds the Coil request for a photo's stored filePath, handling the three
 * shapes the DB can contain (bare absolute path, file://, content://).
 * Replaces the same expression previously copy-pasted across six screens.
 */
fun photoImageRequest(
    context: Context,
    filePath: String,
    cacheKey: String? = null
): ImageRequest = ImageRequest.Builder(context)
    .data(if (filePath.startsWith("/")) File(filePath) else Uri.parse(filePath))
    .apply {
        if (cacheKey != null) {
            memoryCacheKey(cacheKey)
            diskCacheKey(cacheKey)
        }
    }
    .crossfade(true)
    .build()

/** Parses "#RRGGBB" defensively; falls back to gray for malformed values. */
fun parseAccentHex(hex: String): Color = try {
    Color(android.graphics.Color.parseColor(if (hex.startsWith("#")) hex else "#$hex"))
} catch (_: Exception) {
    Color.Gray
}
