package com.melone.gallery.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.melone.gallery.data.model.StartTab
import com.melone.gallery.data.settings.ServerFolder
import com.melone.gallery.ui.AppViewModelFactories

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val vm: SettingsViewModel = viewModel(factory = AppViewModelFactories.settings)
    val state by vm.state.collectAsStateWithLifecycle()
    val browse by vm.browse.collectAsStateWithLifecycle()
    val startTab by vm.startTab.collectAsStateWithLifecycle()
    val reloadOnStart by vm.reloadServerOnStart.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Einstellungen") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Start-Ansicht", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = startTab == StartTab.GALLERY,
                    onClick = { vm.setStartTab(StartTab.GALLERY) },
                    label = { Text("Bilder") },
                )
                FilterChip(
                    selected = startTab == StartTab.ALBUMS,
                    onClick = { vm.setStartTab(StartTab.ALBUMS) },
                    label = { Text("Alben") },
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Server beim Start neu laden", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Aus: beim Start aus Cache, nur manuell/aktualisieren neu laden.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = reloadOnStart, onCheckedChange = { vm.setReloadServerOnStart(it) })
            }
            HorizontalDivider()

            Text("Server (SMB / Tailscale)", style = MaterialTheme.typography.titleMedium)

            OutlinedTextField(
                value = state.host,
                onValueChange = vm::onHostChange,
                label = { Text("Host") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.username,
                onValueChange = vm::onUserChange,
                label = { Text("Benutzer") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.password,
                onValueChange = vm::onPasswordChange,
                label = { Text("Passwort") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedButton(
                onClick = { vm.testConnection() },
                enabled = !state.testing,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (state.testing) {
                    CircularProgressIndicator(Modifier.height(18.dp), strokeWidth = 2.dp)
                } else {
                    Text("Verbindung testen")
                }
            }
            state.testMessage?.let { msg ->
                Text(
                    text = msg,
                    color = if (state.testSuccess == true) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            androidx.compose.material3.HorizontalDivider()

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Server-Ordner", style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = { vm.openBrowser() }) {
                    Icon(Icons.Filled.CreateNewFolder, contentDescription = null)
                    Spacer(Modifier.height(0.dp))
                    Text("  Hinzufügen")
                }
            }

            if (state.folders.isEmpty()) {
                Text(
                    "Noch keine Ordner gewählt.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            state.folders.forEach { folder ->
                FolderCard(vm = vm, folder = folder)
            }

            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { vm.save() },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.Check, contentDescription = null)
                Text(if (state.saved) "  Gespeichert" else "  Speichern")
            }
            Text(
                "Server-Zugriff nur bei aktivem Tailscale am Handy.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (browse.active) {
        SmbBrowserDialog(vm = vm, browse = browse)
    }
}

@Composable
private fun FolderCard(vm: SettingsViewModel, folder: ServerFolder) {
    var expanded by remember { mutableStateOf(false) }
    var subfolders by remember(folder) { mutableStateOf<List<String>?>(null) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Folder, contentDescription = null)
                Spacer(Modifier.height(0.dp))
                Text(
                    text = "  ${folder.label}",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { vm.removeFolder(folder) }) {
                    Icon(Icons.Filled.Delete, contentDescription = "Entfernen")
                }
            }
            if (folder.excludes.isNotEmpty()) {
                Text(
                    "Ausgeschlossen: ${folder.excludes.joinToString(", ") { it.substringAfterLast('/') }}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = {
                expanded = !expanded
            }) {
                Text(if (expanded) "Unterordner ausblenden" else "Unterordner ausschließen…")
            }
            if (expanded) {
                androidx.compose.runtime.LaunchedEffect(folder) {
                    if (subfolders == null) subfolders = vm.listSubfolders(folder)
                }
                val subs = subfolders
                if (subs == null) {
                    CircularProgressIndicator(Modifier.height(20.dp), strokeWidth = 2.dp)
                } else if (subs.isEmpty()) {
                    Text("Keine Unterordner.", style = MaterialTheme.typography.bodySmall)
                } else {
                    subs.forEach { rel ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = rel in folder.excludes,
                                onCheckedChange = { vm.toggleExclude(folder, rel) },
                            )
                            Text(rel.substringAfterLast('/'), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SmbBrowserDialog(vm: SettingsViewModel, browse: BrowseState) {
    androidx.compose.ui.window.Dialog(onDismissRequest = { vm.closeBrowser() }) {
        Card {
            Column(modifier = Modifier.padding(16.dp).fillMaxWidth()) {
                Text(
                    if (browse.atRoot) "Freigabe (Platte) wählen" else "Ordner wählen",
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(4.dp))

                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { vm.navigateUp() }, enabled = !browse.atRoot) {
                        Icon(Icons.Filled.ArrowUpward, contentDescription = "Nach oben")
                    }
                    Text(
                        text = if (browse.atRoot) "Server" else "/${browse.share}/${browse.path}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }

                Column(
                    modifier = Modifier
                        .height(280.dp)
                        .verticalScroll(rememberScrollState())
                        .fillMaxWidth(),
                ) {
                    if (browse.atRoot) {
                        ShareLevel(vm, browse)
                    } else {
                        FolderLevel(vm, browse)
                    }
                }

                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = { vm.closeBrowser() }) { Text("Abbrechen") }
                    Spacer(Modifier.height(0.dp))
                    Button(
                        onClick = {
                            vm.addFolder(ServerFolder(share = browse.share.trim(), rootPath = browse.path))
                            vm.closeBrowser()
                        },
                        enabled = !browse.atRoot,
                    ) {
                        Text(if (browse.path.isEmpty()) "Ganze Platte wählen" else "Diesen Ordner wählen")
                    }
                }
            }
        }
    }
}

/** Oberste Ebene: verfügbare Freigaben (Platten). */
@Composable
private fun ShareLevel(vm: SettingsViewModel, browse: BrowseState) {
    when {
        browse.loadingShares -> Box(Modifier.fillMaxWidth().padding(24.dp), Alignment.Center) {
            CircularProgressIndicator()
        }
        browse.shares.isNotEmpty() -> browse.shares.forEach { share ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { vm.enterShare(share) }
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Folder, contentDescription = null)
                Text("  $share", style = MaterialTheme.typography.bodyLarge)
            }
        }
        // Fallback: Auflisten nicht möglich → Freigabe manuell eingeben.
        else -> {
            browse.error?.let {
                Text(
                    "Freigaben konnten nicht aufgelistet werden ($it). Bitte manuell eingeben:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
            }
            var manual by remember { mutableStateOf("") }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = manual,
                    onValueChange = { manual = it },
                    label = { Text("Freigabe (z. B. bilder)") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.height(0.dp))
                TextButton(onClick = { vm.setBrowseShare(manual.trim()) }, enabled = manual.isNotBlank()) {
                    Text("Öffnen")
                }
            }
        }
    }
}

/** Ordner-Ebene innerhalb einer Freigabe. */
@Composable
private fun FolderLevel(vm: SettingsViewModel, browse: BrowseState) {
    when {
        browse.loading -> Box(Modifier.fillMaxWidth().padding(24.dp), Alignment.Center) {
            CircularProgressIndicator()
        }
        browse.error != null -> Text(
            "Fehler: ${browse.error}",
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
        )
        browse.entries.isEmpty() -> Text(
            "Keine Unterordner.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        else -> browse.entries.forEach { entry ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { vm.enter(entry.name) }
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Folder, contentDescription = null)
                Text("  ${entry.name}", style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}
