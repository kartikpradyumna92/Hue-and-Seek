package com.colorwalk.app.ui.components

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.colorwalk.app.R

// Colour names double as DB keys ("Red", "Blue", …) and must never be translated at
// rest; only their on-screen label goes through resources.
@StringRes
private fun colorNameRes(key: String): Int? = when (key) {
    "Red"    -> R.string.color_red
    "Orange" -> R.string.color_orange
    "Yellow" -> R.string.color_yellow
    "Green"  -> R.string.color_green
    "Blue"   -> R.string.color_blue
    "Purple" -> R.string.color_purple
    "Pink"   -> R.string.color_pink
    "Brown"  -> R.string.color_brown
    else     -> null
}

fun Context.colorDisplayName(key: String): String =
    colorNameRes(key)?.let { getString(it) } ?: key

@Composable
fun colorDisplayName(key: String): String =
    colorNameRes(key)?.let { stringResource(it) } ?: key
