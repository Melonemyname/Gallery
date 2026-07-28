package com.melone.gallery.ui.components

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.melone.gallery.GalleryApplication
import com.melone.gallery.data.model.MediaItem
import com.melone.gallery.data.model.MediaSource
import com.melone.gallery.data.settings.ServerFolder
import com.melone.gallery.data.transfer.TransferTarget
import com.melone.gallery.ui.gallery.GalleryViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Kachel im Raster mit Auswahl-Overlay (Timeline + Alben). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SelectableThumb(
    item: MediaItem,
    isSelected: Boolean,
    showVideoBadges: Boolean = true,
    onClick: (MediaItem) -> Unit,
    onLong: (MediaItem) -> Unit,
) {
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .combinedClickable(onClick = { onClick(item) }, onLongClick = { onLong(item) }),
    ) {
        MediaThumbnail(
            item = item,
            modifier = Modifier.fillMaxSize(),
            cornerRadius = 4,
            showVideoBadges = showVideoBadges,
        )
        if (isSelected) {
            Box(Modifier.fillMaxSize().clip(RoundedCornerShape(4.dp)).background(Color.Black.copy(alpha = 0.35f)))
            Icon(
                Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(22.dp),
            )
        }
    }
}

/** Obere Leiste im Auswahlmodus (ersetzt die normale Top-Bar). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelectionTopBar(count: Int, onClose: () -> Unit) {
    TopAppBar(
        title = { Text("$count ausgewählt") },
        navigationIcon = {
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, contentDescription = "Auswahl beenden")
            }
        },
    )
}

/**
 * Gemeinsame Auswahl-Aktionsleiste (Info, Teilen, Löschen, 3-Punkte:
 * Verschieben/Kopieren) samt Ziel-Dialog und den nötigen System-Launchern.
 * Muss innerhalb eines [BoxScope] stehen; positioniert sich unten auf Höhe des
 * Papierkorb-FAB.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BoxScope.SelectionActionsBar(
    selectedItems: List<MediaItem>,
    serverFolders: List<ServerFolder>,
    viewModel: GalleryViewModel,
    onClear: () -> Unit,
    onOpenInfo: (List<MediaItem>) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val app = context.applicationContext as GalleryApplication
    val transfer = app.container.mediaTransfer

    var showDest by remember { mutableStateOf(false) }
    var pendingMove by remember { mutableStateOf(false) }
    var pendingLocalIds by remember { mutableStateOf<List<String>>(emptyList()) }
    var menu by remember { mutableStateOf(false) }

    fun toast(m: String) = Toast.makeText(context, m, Toast.LENGTH_SHORT).show()

    val trashLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        if (r.resultCode == android.app.Activity.RESULT_OK) {
            viewModel.removeLocalItems(pendingLocalIds.toSet()); toast("In den Papierkorb")
        }
        pendingLocalIds = emptyList(); onClear()
    }
    // Beim Verschieben wird das lokale Original nach dem Kopieren endgültig gelöscht
    // (NICHT in den Papierkorb) – es liegt ja schon auf dem Server.
    val moveDeleteLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        if (r.resultCode == android.app.Activity.RESULT_OK) viewModel.removeLocalItems(pendingLocalIds.toSet())
        pendingLocalIds = emptyList(); onClear()
    }

    fun runBatch(target: TransferTarget, move: Boolean) {
        val items = selectedItems
        scope.launch {
            var ok = 0
            val localMoveIds = mutableListOf<String>()
            items.forEach { item ->
                if (transfer.copy(item, target).isSuccess) {
                    ok++
                    if (move) when (item.source) {
                        MediaSource.SERVER -> {
                            transfer.trashServerSource(item, System.currentTimeMillis())
                            viewModel.removeServerItems(setOf(item.id))
                        }
                        MediaSource.LOCAL -> localMoveIds += item.id
                    }
                }
            }
            toast(if (move) "$ok verschoben" else "$ok kopiert")
            if (move && localMoveIds.isNotEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                pendingLocalIds = localMoveIds
                // Endgültig vom Gerät löschen (nicht in den Papierkorb).
                val pi = MediaStore.createDeleteRequest(context.contentResolver, localMoveIds.map { Uri.parse(it) })
                moveDeleteLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
            } else onClear()
        }
    }

    val treeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            runBatch(TransferTarget.LocalTree(uri), pendingMove)
        }
    }

    fun doTrash() {
        val items = selectedItems
        val servers = items.filter { it.source == MediaSource.SERVER }
        val locals = items.filter { it.source == MediaSource.LOCAL }
        scope.launch {
            servers.forEach { runCatching { transfer.trashServerSource(it, System.currentTimeMillis()) } }
            if (servers.isNotEmpty()) viewModel.removeServerItems(servers.map { it.id }.toSet())
            if (locals.isNotEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                pendingLocalIds = locals.map { it.id }
                val pi = MediaStore.createTrashRequest(context.contentResolver, locals.map { Uri.parse(it.id) }, true)
                trashLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
            } else { toast("In den Papierkorb"); onClear() }
        }
    }

    fun doShare() {
        val items = selectedItems
        scope.launch {
            val uris = ArrayList<Uri>()
            items.forEach { sharedContentUri(context, it)?.let { u -> uris.add(u) } }
            if (uris.isNotEmpty()) {
                val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                    type = "*/*"
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                withContext(Dispatchers.Main) {
                    context.startActivity(Intent.createChooser(intent, "Teilen"))
                }
            }
            onClear()
        }
    }

    Surface(
        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 3.dp,
    ) {
        Row(modifier = Modifier.padding(horizontal = 6.dp)) {
            IconButton(onClick = { if (selectedItems.isNotEmpty()) onOpenInfo(selectedItems) }) {
                Icon(Icons.Filled.Info, contentDescription = "Info")
            }
            IconButton(onClick = { doShare() }) { Icon(Icons.Filled.Share, contentDescription = "Teilen") }
            IconButton(onClick = { doTrash() }) { Icon(Icons.Filled.Delete, contentDescription = "Löschen") }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Mehr") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("Verschieben") },
                        leadingIcon = { Icon(Icons.Filled.DriveFileMove, contentDescription = null) },
                        onClick = { menu = false; pendingMove = true; showDest = true },
                    )
                    DropdownMenuItem(
                        text = { Text("Kopieren") },
                        leadingIcon = { Icon(Icons.Filled.ContentCopy, contentDescription = null) },
                        onClick = { menu = false; pendingMove = false; showDest = true },
                    )
                }
            }
        }
    }

    if (showDest) {
        SelectionDestinationDialog(
            move = pendingMove,
            serverFolders = serverFolders,
            onDismiss = { showDest = false },
            onPickLocal = { showDest = false; treeLauncher.launch(null) },
            onPickServer = { folder -> showDest = false; runBatch(TransferTarget.Server(folder.share, folder.rootPath), pendingMove) },
        )
    }
}

@Composable
private fun SelectionDestinationDialog(
    move: Boolean,
    serverFolders: List<ServerFolder>,
    onDismiss: () -> Unit,
    onPickLocal: () -> Unit,
    onPickServer: (ServerFolder) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (move) "Verschieben nach…" else "Kopieren nach…") },
        text = {
            Column {
                Text(
                    "Auf dem Gerät (Ordner wählen)…",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.fillMaxWidth().clickable(onClick = onPickLocal).padding(vertical = 12.dp),
                )
                serverFolders.forEach { folder ->
                    Text(
                        "Server: ${folder.label}",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.fillMaxWidth().clickable { onPickServer(folder) }.padding(vertical = 12.dp),
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}

/** content-URI zum Teilen (lokal direkt, Server über Cache-Kopie). */
internal suspend fun sharedContentUri(context: android.content.Context, item: MediaItem): Uri? =
    when (item.source) {
        MediaSource.LOCAL -> Uri.parse(item.id)
        MediaSource.SERVER -> withContext(Dispatchers.IO) {
            val app = context.applicationContext as GalleryApplication
            runCatching {
                val dir = java.io.File(context.cacheDir, "shared").apply { mkdirs() }
                val out = java.io.File(dir, item.displayName)
                val f = app.container.smbManager.openFile(item.smbShare!!, item.smbPath!!)
                try {
                    f.inputStream.use { i -> out.outputStream().use { o -> i.copyTo(o) } }
                } finally {
                    runCatching { f.close() }
                }
                androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", out)
            }.getOrNull()
        }
    }
