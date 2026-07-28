package com.melone.gallery.ui.trash

import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.melone.gallery.ui.AppViewModelFactories
import com.melone.gallery.ui.components.MediaThumbnail

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrashScreen(onBack: () -> Unit) {
    val vm: TrashViewModel = viewModel(factory = AppViewModelFactories.trash)
    val items by vm.items.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { vm.load() }

    var selected by remember { mutableStateOf<TrashItem?>(null) }
    var pendingLocal by remember { mutableStateOf<TrashItem?>(null) }
    var confirmEmpty by remember { mutableStateOf(false) }

    val restoreLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) pendingLocal?.let { vm.removeLocal(it) }
        pendingLocal = null
    }
    val deleteLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) pendingLocal?.let { vm.removeLocal(it) }
        pendingLocal = null
    }

    // Papierkorb leeren: alle lokalen Einträge in einem System-Dialog endgültig löschen.
    val emptyLocalLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) vm.removeAllLocal()
    }

    val context = androidx.compose.ui.platform.LocalContext.current
    fun emptyTrash() {
        // Server-Einträge direkt löschen; lokale über den System-Löschdialog.
        vm.emptyServer()
        val localUris = items.filterIsInstance<TrashItem.Local>().map { Uri.parse(it.media.id) }
        if (localUris.isNotEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val pi = MediaStore.createDeleteRequest(context.contentResolver, localUris)
            emptyLocalLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
        }
    }
    fun restoreLocal(item: TrashItem.Local) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            pendingLocal = item
            val uri = Uri.parse(item.media.id)
            val pi = MediaStore.createTrashRequest(context.contentResolver, listOf(uri), false)
            restoreLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
        }
    }
    fun deleteLocal(item: TrashItem.Local) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            pendingLocal = item
            val uri = Uri.parse(item.media.id)
            val pi = MediaStore.createDeleteRequest(context.contentResolver, listOf(uri))
            deleteLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Papierkorb") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
                    }
                },
                actions = {
                    if (loading) {
                        CircularProgressIndicator(Modifier.padding(end = 12.dp).size(20.dp), strokeWidth = 2.dp)
                    }
                    if (items.isNotEmpty()) {
                        IconButton(onClick = { confirmEmpty = true }) {
                            Icon(Icons.Filled.DeleteSweep, contentDescription = "Papierkorb leeren")
                        }
                    }
                },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (items.isEmpty() && !loading) {
                Text(
                    "Papierkorb ist leer.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center),
                )
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    contentPadding = PaddingValues(2.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(items, key = { it.media.id }) { item ->
                        MediaThumbnail(
                            item = item.media,
                            modifier = Modifier.aspectRatio(1f).clickable { selected = item },
                            cornerRadius = 4,
                        )
                    }
                }
            }
        }
    }

    val sel = selected
    if (sel != null) {
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text(sel.media.displayName, maxLines = 1) },
            text = { Text("Wiederherstellen oder endgültig löschen?") },
            confirmButton = {
                TextButton(onClick = {
                    when (sel) {
                        is TrashItem.Local -> restoreLocal(sel)
                        is TrashItem.Server -> vm.restoreServer(sel)
                    }
                    selected = null
                }) { Text("Wiederherstellen") }
            },
            dismissButton = {
                TextButton(onClick = {
                    when (sel) {
                        is TrashItem.Local -> deleteLocal(sel)
                        is TrashItem.Server -> vm.deleteServerPermanent(sel)
                    }
                    selected = null
                }) { Text("Endgültig löschen") }
            },
        )
    }

    if (confirmEmpty) {
        AlertDialog(
            onDismissRequest = { confirmEmpty = false },
            title = { Text("Papierkorb leeren?") },
            text = { Text("Alle Elemente im Papierkorb werden endgültig gelöscht.") },
            confirmButton = {
                TextButton(onClick = { confirmEmpty = false; emptyTrash() }) { Text("Leeren") }
            },
            dismissButton = { TextButton(onClick = { confirmEmpty = false }) { Text("Abbrechen") } },
        )
    }
}
