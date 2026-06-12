package com.colorwalk.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.colorwalk.app.data.db.PhotoEntity
import com.colorwalk.app.ui.theme.Spacing
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The one photo tile used by every grid (color album, date list, place album,
 * untagged list). Variants are expressed through flags instead of copy-pasted
 * composables so spacing, rounding, and metadata layout stay identical app-wide.
 */
@Composable
fun PhotoGridCard(
    photo: PhotoEntity,
    accentColor: Color,
    onOpen: () -> Unit,
    onDelete: (() -> Unit)? = null,
    showColorName: Boolean = false,
    showTime: Boolean = false,
    showDominant: Boolean = true,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val dateStr = remember(photo.dateTaken, showTime) {
        val pattern = if (showTime) "MMM d  •  h:mm a" else "MMM d, yyyy"
        SimpleDateFormat(pattern, Locale.getDefault()).format(Date(photo.dateTaken))
    }
    var showConfirm by remember { mutableStateOf(false) }

    if (showConfirm && onDelete != null) {
        ConfirmDeleteDialog(onConfirm = onDelete, onDismiss = { showConfirm = false })
    }

    Card(
        onClick = onOpen,
        shape = MaterialTheme.shapes.small,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = modifier.fillMaxWidth()
    ) {
        Column {
            Box {
                AsyncImage(
                    model = photoImageRequest(context, photo.filePath),
                    contentDescription = "${photo.colorName} photo from $dateStr",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .background(accentColor.copy(alpha = 0.15f))
                )
                if (onDelete != null) {
                    IconButton(
                        onClick = { showConfirm = true },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(2.dp)
                            .size(40.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(30.dp)
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.6f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "Delete photo",
                                tint = Color(0xFFEF9A9A),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
            Column(modifier = Modifier.padding(Spacing.m)) {
                if (showColorName) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(accentColor)
                        )
                        Spacer(Modifier.width(Spacing.xs))
                        Text(
                            photo.colorName,
                            style = MaterialTheme.typography.labelMedium,
                            color = accentColor
                        )
                    }
                    Spacer(Modifier.height(2.dp))
                }
                Text(
                    dateStr,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (photo.locationName != null) {
                    Spacer(Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.LocationOn,
                            contentDescription = null,
                            tint = accentColor,
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(Modifier.width(3.dp))
                        Text(
                            photo.locationName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                }
                if (showDominant) {
                    Spacer(Modifier.height(Spacing.xs))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(parseAccentHex(photo.dominantColorHex))
                        )
                        Spacer(Modifier.width(Spacing.xs))
                        Text(
                            photo.dominantColorHex.uppercase(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                }
            }
        }
    }
}
