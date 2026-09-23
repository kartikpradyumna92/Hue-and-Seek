package com.colorwalk.app.ui.components

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.net.Uri
import androidx.compose.runtime.mutableStateMapOf
import androidx.core.graphics.PathParser
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
        // BUG-030: rotation rewrites a photo in place. Every cache key — explicit ones
        // like Newsfeed's "feed_<id>" included — carries the photo's revision, and the
        // read below re-runs the calling composable when it's bumped, so every screen
        // drops the stale orientation instead of serving it for the rest of the process.
        val revision = PhotoRevisions.of(filePath)
        if (cacheKey != null) {
            memoryCacheKey("$cacheKey@$revision")
            diskCacheKey("$cacheKey@$revision")
        } else {
            setParameter("revision", revision, memoryCacheKey = revision.toString())
        }
    }
    // BUG-053: a row whose file is gone (restore/reinstall with nothing to recover
    // from) rendered as an indistinguishable blank tile.
    .error(MissingPhotoDrawable(context.resources.displayMetrics.density))
    .crossfade(true)
    .build()

/**
 * Neutral tile with a centered "broken image" glyph. Deliberately has NO intrinsic
 * size, so ContentScale.Crop fills the tile instead of blowing the glyph up.
 */
private class MissingPhotoDrawable(private val density: Float) : Drawable() {
    private val bg = Paint().apply { color = 0x33808080 }
    private val fg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x99808080.toInt() }
    // Material "broken_image", 24×24 viewport.
    private val glyph = PathParser.createPathFromPathData(
        "M21,5v6.59l-3,-3.01 -4,4.01 -4,-4 -4,4 -3,-3.01L3,5c0,-1.1 0.9,-2 2,-2h14c1.1,0 2,0.9 2,2z" +
            "M18,11.42l3,3.01L21,19c0,1.1 -0.9,2 -2,2L5,21c-1.1,0 -2,-0.9 -2,-2v-6.58l3,2.99 4,-4 4,4 4,-3.99z"
    )

    override fun draw(canvas: Canvas) {
        canvas.drawRect(bounds, bg)
        val size = minOf(32f * density, bounds.width() * 0.4f, bounds.height() * 0.4f)
        val save = canvas.save()
        canvas.translate(bounds.exactCenterX() - size / 2, bounds.exactCenterY() - size / 2)
        canvas.scale(size / 24f, size / 24f)
        canvas.drawPath(glyph, fg)
        canvas.restoreToCount(save)
    }

    override fun setAlpha(alpha: Int) { bg.alpha = alpha * 0x33 / 255; fg.alpha = alpha * 0x99 / 255 }
    override fun setColorFilter(colorFilter: ColorFilter?) { fg.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/**
 * In-memory revision per photo file, bumped when its pixels/orientation change in
 * place (rotation). Snapshot state: composables that built a request for the file
 * recompose on a bump. Resets with the process, as does Coil's memory cache.
 */
object PhotoRevisions {
    private val revisions = mutableStateMapOf<String, Int>()
    fun of(filePath: String): Int = revisions[filePath] ?: 0
    fun bump(filePath: String) { revisions[filePath] = of(filePath) + 1 }
}

/** Parses "#RRGGBB" defensively; falls back to gray for malformed values. */
fun parseAccentHex(hex: String): Color = try {
    Color(android.graphics.Color.parseColor(if (hex.startsWith("#")) hex else "#$hex"))
} catch (_: Exception) {
    Color.Gray
}
