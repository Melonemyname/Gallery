package com.melone.gallery.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.melone.gallery.data.settings.ServerFolder
import com.melone.gallery.data.smb.SmbManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Ziel-Auswahl fürs Verschieben/Kopieren: man navigiert in der App durch die
 * Server-Freigaben und Unterordner (wie beim normalen Stöbern) und wählt mit
 * „Hierher" den aktuellen Ordner als Ziel. Zusätzlich Gerät-Ordner über SAF.
 */
@Composable
fun ServerFolderPickerDialog(
    smb: SmbManager,
    move: Boolean,
    serverFolders: List<ServerFolder>,
    onDismiss: () -> Unit,
    onPickLocal: () -> Unit,
    onPickServer: (share: String, path: String) -> Unit,
) {
    // null = Freigaben-Liste (Wurzel); sonst navigieren wir in dieser Freigabe.
    var share by remember { mutableStateOf<String?>(null) }
    var path by remember { mutableStateOf("") }
    var shares by remember { mutableStateOf<List<String>>(emptyList()) }
    var folders by remember { mutableStateOf<List<String>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    // Freigaben laden (Fallback: Shares der konfigurierten Ordner).
    LaunchedEffect(Unit) {
        loading = true; error = null
        val result = withContext(Dispatchers.IO) { runCatching { smb.listShares() } }
        shares = result.getOrNull()?.takeIf { it.isNotEmpty() }
            ?: serverFolders.map { it.share }.distinct().sorted()
        loading = false
    }

    // Unterordner der aktuellen Ebene laden.
    LaunchedEffect(share, path) {
        val s = share ?: return@LaunchedEffect
        loading = true; error = null
        val result = withContext(Dispatchers.IO) {
            runCatching {
                smb.list(s, path)
                    .filter { it.isDirectory && it.name != ".thumbs" && it.name != ".trash" }
                    .map { it.name }
                    .sortedBy { it.lowercase() }
            }
        }
        result.onSuccess { folders = it; error = null }
            .onFailure { error = it.message ?: it.javaClass.simpleName }
        loading = false
    }

    fun up() {
        when {
            share == null -> {}
            path.isEmpty() -> { share = null }
            else -> path = path.substringBeforeLast('/', "")
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(16.dp), tonalElevation = 6.dp) {
            Column(modifier = Modifier.padding(top = 12.dp, bottom = 8.dp)) {
                // Kopf: Zurück + aktueller Pfad.
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { up() }, enabled = share != null) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
                    }
                    Text(
                        text = if (share == null) {
                            if (move) "Verschieben nach…" else "Kopieren nach…"
                        } else {
                            "/$share" + if (path.isEmpty()) "" else "/$path"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }

                Box(modifier = Modifier.heightIn(min = 120.dp, max = 380.dp).fillMaxWidth()) {
                    if (loading) {
                        CircularProgressIndicator(Modifier.align(Alignment.Center).size(28.dp), strokeWidth = 2.dp)
                    } else if (error != null) {
                        Text(
                            "Fehler: $error",
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.align(Alignment.Center).padding(16.dp),
                        )
                    } else {
                        LazyColumn(contentPadding = PaddingValues(vertical = 4.dp)) {
                            if (share == null) {
                                item {
                                    RowItem(icon = { Icon(Icons.Filled.Smartphone, null) }, label = "Auf dem Gerät (Ordner wählen)…", onClick = onPickLocal)
                                }
                                items(shares) { s ->
                                    RowItem(icon = { Icon(Icons.Filled.Folder, null) }, label = s, onClick = { share = s; path = "" })
                                }
                            } else {
                                if (folders.isEmpty()) {
                                    item {
                                        Text(
                                            "Keine Unterordner hier.",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(16.dp),
                                        )
                                    }
                                }
                                items(folders) { name ->
                                    RowItem(
                                        icon = { Icon(Icons.Filled.Folder, null) },
                                        label = name,
                                        onClick = { path = if (path.isEmpty()) name else "$path/$name" },
                                    )
                                }
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onDismiss) { Text("Abbrechen") }
                    Spacer(Modifier.weight(1f))
                    TextButton(
                        onClick = { share?.let { onPickServer(it, path) } },
                        enabled = share != null && !loading,
                    ) {
                        Text(if (move) "Hierher verschieben" else "Hierher kopieren")
                    }
                }
            }
        }
    }
}

@Composable
private fun RowItem(icon: @Composable () -> Unit, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        icon()
        Spacer(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
