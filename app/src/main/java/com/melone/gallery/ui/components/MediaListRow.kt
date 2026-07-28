package com.melone.gallery.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.SdCard
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.melone.gallery.data.model.MediaItem
import com.melone.gallery.data.model.MediaSource
import com.melone.gallery.domain.DateFormatters
import com.melone.gallery.domain.Formatters

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MediaListRow(
    item: MediaItem,
    showDetails: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    selected: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (selected) Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f))
                else Modifier,
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MediaThumbnail(
            item = item,
            modifier = Modifier.size(if (showDetails) 64.dp else 48.dp),
            cornerRadius = 6,
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.displayName,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (showDetails) {
                val parts = buildList {
                    add(Formatters.fileSize(item.sizeBytes))
                    Formatters.resolution(item.width, item.height)?.let { add(it) }
                    add(DateFormatters.fullDateTime(item.dateTaken))
                }
                Text(
                    text = parts.joinToString("  ·  "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                Text(
                    text = DateFormatters.fullDateTime(item.dateTaken),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Icon(
            imageVector = when {
                item.source == MediaSource.SERVER -> Icons.Filled.Cloud
                isOnSdCard(item.fullPath) -> Icons.Filled.SdCard
                else -> Icons.Filled.Smartphone
            },
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
    }
}

/** Lokale Datei auf SD-Karte? (nicht unter /storage/emulated bzw. /storage/self). */
private fun isOnSdCard(path: String): Boolean =
    path.startsWith("/storage/") &&
        !path.startsWith("/storage/emulated/") &&
        !path.startsWith("/storage/self/")
