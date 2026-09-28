package com.melone.gallery.ui.albums

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items as listItems
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SdCard
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.melone.gallery.data.model.MediaItem
import com.melone.gallery.data.model.MediaSource
import com.melone.gallery.data.model.ViewMode
import com.melone.gallery.domain.Album
import com.melone.gallery.domain.GalleryGrouping
import com.melone.gallery.domain.StorageKind
import com.melone.gallery.ui.components.MediaListRow
import com.melone.gallery.ui.components.MediaThumbnail
import com.melone.gallery.ui.components.SelectableThumb
import com.melone.gallery.ui.components.dragSelect
import com.melone.gallery.ui.components.SelectionActionsBar
import com.melone.gallery.ui.components.SelectionTopBar
import com.melone.gallery.ui.gallery.GalleryViewModel
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Surface
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumsScreen(
    viewModel: GalleryViewModel,
    nav: String,
    onNavChange: (String) -> Unit,
    onOpenViewer: (List<MediaItem>, Int) -> Unit,
    onOpenSettings: () -> Unit,
    contentPadding: PaddingValues,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Navigationszustand (in GalleryApp gehalten, damit die Bottom-Nav im Album
    // ausgeblendet werden kann):
    //  "" = Wurzel, "L:<albumId>" = lokales Album, "S:<pfad>" = Server-Ordner.

    // Alben nutzen eine eigene Sortierung/Ansicht (getrennt von der Bilder-Timeline).
    val albumSort = state.prefs.albumSort
    val albumViewMode = state.prefs.albumViewMode
    // Fertig aufbereitet aus dem ViewModel (läuft dort im Hintergrund, siehe AlbumsData).
    val albumsData by viewModel.albums.collectAsStateWithLifecycle()
    val localAlbums = albumsData.localAlbums
    val serverItems = albumsData.serverItems

    // Datei-Auswahlmodus (langes Halten) innerhalb eines Albums/Ordners.
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    // Album-Auswahlmodus (langes Halten auf eine Album-/Ordner-Kachel) für ganze Alben.
    var selectedAlbums by remember { mutableStateOf<Set<String>>(emptySet()) }
    // Beim Navigieren (anderes Album/Ebene) beide Auswahlen zurücksetzen.
    LaunchedEffect(nav) { selected = emptySet(); selectedAlbums = emptySet() }
    // Datei- und Album-Auswahl schließen sich gegenseitig aus.
    val onToggle: (String) -> Unit = { id ->
        selectedAlbums = emptySet()
        selected = if (id in selected) selected - id else selected + id
    }
    val clearSel: () -> Unit = { selected = emptySet() }
    val onToggleAlbum: (String) -> Unit = { key ->
        selected = emptySet()
        selectedAlbums = if (key in selectedAlbums) selectedAlbums - key else selectedAlbums + key
    }
    val clearAlbumSel: () -> Unit = { selectedAlbums = emptySet() }

    // Ganze Alben löschen (Auswahl → untere „Löschen"-Leiste): alle Elemente der gewählten
    // Alben in den Papierkorb (Server `.trash` / lokal System-Papierkorb, 30 Tage) – dieselben
    // Primitive wie die Datei-Auswahl-Löschen-Logik.
    val context = LocalContext.current
    val transfer = (context.applicationContext as com.melone.gallery.GalleryApplication)
        .container.mediaTransfer
    val deleteScope = rememberCoroutineScope()
    var pendingLocalDeleteIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    val localTrashLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            viewModel.removeLocalItems(pendingLocalDeleteIds)
        }
        pendingLocalDeleteIds = emptySet()
    }
    fun performAlbumDelete(items: List<MediaItem>) {
        val servers = items.filter { it.source == MediaSource.SERVER }
        val locals = items.filter { it.source == MediaSource.LOCAL }
        if (servers.isNotEmpty()) {
            deleteScope.launch {
                val now = System.currentTimeMillis()
                servers.forEach { runCatching { transfer.trashServerSource(it, now) } }
                viewModel.removeServerItems(servers.map { it.id }.toSet())
            }
        }
        if (locals.isNotEmpty() && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            pendingLocalDeleteIds = locals.map { it.id }.toSet()
            runCatching {
                val pi = android.provider.MediaStore.createTrashRequest(
                    context.contentResolver, locals.map { android.net.Uri.parse(it.id) }, true,
                )
                localTrashLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
            }
        }
    }
    // Schlüssel eines gewählten Albums ("L:<id>" / "S:<pfad>") → seine Elemente.
    fun albumItemsOf(key: String): List<MediaItem> = when {
        key.startsWith("L:") -> localAlbums.find { it.id == key.removePrefix("L:") }?.items ?: emptyList()
        key.startsWith("S:") -> GalleryGrouping.itemsUnder(serverItems, key.removePrefix("S:"))
        else -> emptyList()
    }
    fun deleteSelectedAlbums() {
        val items = selectedAlbums.flatMap { albumItemsOf(it) }.distinctBy { it.id }
        selectedAlbums = emptySet()
        performAlbumDelete(items)
    }

    // Zurück im Album/Ordner: eine Ebene hoch. Hier (im Alben-Ziel) verankert, damit
    // es nicht durch verzögerten Routen-Status fälschlich zu „Bilder" springt.
    // (Der Auswahl-BackHandler in den Ebenen liegt tiefer und hat Vorrang.)
    BackHandler(enabled = nav.isNotEmpty()) {
        onNavChange(
            when {
                nav.startsWith("S:") -> {
                    val p = nav.removePrefix("S:")
                    if (p.contains('/')) "S:" + p.substringBeforeLast('/') else ""
                }
                else -> ""
            },
        )
    }
    // Im Album-Auswahlmodus beendet Zurück erst die Auswahl (Vorrang vor „Ebene hoch").
    BackHandler(enabled = selectedAlbums.isNotEmpty()) { clearAlbumSel() }

    // Kopfzeile blendet sich beim Scrollen aus und wieder ein (wie bei Bildern),
    // aber nur im Querformat; im Hochformat bleibt sie stehen.
    val scrollBehavior = androidx.compose.material3.TopAppBarDefaults.enterAlwaysScrollBehavior()
    val landscape = com.melone.gallery.ui.components.isLandscape()
    LaunchedEffect(landscape) { if (!landscape) scrollBehavior.state.heightOffset = 0f }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (selected.isEmpty() && selectedAlbums.isEmpty() && landscape)
                        Modifier.nestedScroll(scrollBehavior.nestedScrollConnection)
                    else Modifier,
                ),
        ) {
            when {
                nav.startsWith("L:") -> {
                    val album = localAlbums.find { it.id == nav.removePrefix("L:") }
                    if (album == null) {
                        // Album verschwunden (z. B. alle Elemente gelöscht) → zurück zur Wurzel.
                        LaunchedEffect(nav) { onNavChange("") }
                    } else {
                        LocalAlbumLevel(
                            album = album,
                            scrollBehavior = scrollBehavior,
                            viewMode = albumViewMode,
                            columns = state.prefs.gridColumns,
                            onSetViewMode = viewModel::setAlbumViewMode,
                            sort = albumSort,
                            onSetSort = viewModel::setAlbumSort,
                            onBack = { onNavChange("") },
                            onOpenViewer = onOpenViewer,
                            selected = selected,
                            onToggle = onToggle,
                            onSelectedChange = { selected = it },
                            onClearSel = clearSel,
                            serverFolders = state.serverFolders,
                            viewModel = viewModel,
                        )
                    }
                }
                nav.startsWith("S:") -> {
                    val path = nav.removePrefix("S:")
                    ServerFolderLevel(
                        path = path,
                        serverItems = serverItems,
                        scrollBehavior = scrollBehavior,
                        viewMode = albumViewMode,
                        columns = state.prefs.gridColumns,
                        onSetViewMode = viewModel::setAlbumViewMode,
                        sort = albumSort,
                        onSetSort = viewModel::setAlbumSort,
                        onEnter = { child -> onNavChange("S:$child") },
                        onBack = {
                            onNavChange(if (path.contains('/')) "S:" + path.substringBeforeLast('/') else "")
                        },
                        onOpenViewer = onOpenViewer,
                        selected = selected,
                        onToggle = onToggle,
                        onSelectedChange = { selected = it },
                        onClearSel = clearSel,
                        selectedAlbums = selectedAlbums,
                        onToggleAlbum = onToggleAlbum,
                        onClearAlbumSel = clearAlbumSel,
                        serverFolders = state.serverFolders,
                        viewModel = viewModel,
                    )
                }
                else -> RootLevel(
                    localAlbums = localAlbums,
                    serverFolders = GalleryGrouping.serverFolder(serverItems, "").folders,
                    loading = state.isLoading,
                    scrollBehavior = scrollBehavior,
                    onOpenLocal = { onNavChange("L:${it.id}") },
                    onOpenServer = { onNavChange("S:${it.path}") },
                    onRefresh = viewModel::refresh,
                    onOpenSettings = onOpenSettings,
                    selectedAlbums = selectedAlbums,
                    onToggleAlbum = onToggleAlbum,
                    onClearAlbumSel = clearAlbumSel,
                )
            }
        }

        // Untere Leiste im Album-Auswahlmodus: bewusst NUR „Löschen" (wie bei Bildern,
        // aber auf die eine sinnvolle Album-Aktion reduziert).
        if (selectedAlbums.isNotEmpty()) {
            Surface(
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp),
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                tonalElevation = 3.dp,
            ) {
                Row(modifier = Modifier.padding(horizontal = 6.dp)) {
                    IconButton(onClick = { deleteSelectedAlbums() }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Löschen")
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RootLevel(
    localAlbums: List<Album>,
    serverFolders: List<com.melone.gallery.domain.FolderEntry>,
    loading: Boolean,
    scrollBehavior: androidx.compose.material3.TopAppBarScrollBehavior,
    onOpenLocal: (Album) -> Unit,
    onOpenServer: (com.melone.gallery.domain.FolderEntry) -> Unit,
    onRefresh: () -> Unit,
    onOpenSettings: () -> Unit,
    selectedAlbums: Set<String>,
    onToggleAlbum: (String) -> Unit,
    onClearAlbumSel: () -> Unit,
) {
    if (selectedAlbums.isNotEmpty()) {
        SelectionTopBar(count = selectedAlbums.size, onClose = onClearAlbumSel)
    } else {
        TopAppBar(
            scrollBehavior = scrollBehavior,
            // Statusleisten-Abstand kommt schon über das Scaffold-contentPadding.
            windowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
            title = { Text("Alben") },
            actions = {
                if (loading) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp).padding(end = 4.dp), strokeWidth = 2.dp)
                }
                IconButton(onClick = onRefresh) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Aktualisieren")
                }
                IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Filled.Settings, contentDescription = "Einstellungen")
                }
            },
        )
    }
    if (localAlbums.isEmpty() && serverFolders.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Keine Alben.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        contentPadding = PaddingValues(10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        if (localAlbums.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) { SectionTitle("Auf dem Gerät") }
            items(localAlbums, key = { "L:${it.id}" }) { album ->
                val key = "L:${album.id}"
                FolderCard(
                    name = album.name,
                    cover = album.cover,
                    count = album.count,
                    storageKinds = album.storageKinds,
                    isSelected = key in selectedAlbums,
                    onClick = { if (selectedAlbums.isNotEmpty()) onToggleAlbum(key) else onOpenLocal(album) },
                    onLongClick = { onToggleAlbum(key) },
                )
            }
        }
        if (serverFolders.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) { SectionTitle("Server") }
            items(serverFolders, key = { "S:${it.path}" }) { entry ->
                val key = "S:${entry.path}"
                FolderCard(
                    name = entry.name,
                    cover = entry.cover,
                    count = entry.count,
                    storageKinds = emptySet(),
                    isSelected = key in selectedAlbums,
                    onClick = { if (selectedAlbums.isNotEmpty()) onToggleAlbum(key) else onOpenServer(entry) },
                    onLongClick = { onToggleAlbum(key) },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ServerFolderLevel(
    path: String,
    serverItems: List<MediaItem>,
    scrollBehavior: androidx.compose.material3.TopAppBarScrollBehavior,
    viewMode: ViewMode,
    columns: Int,
    onSetViewMode: (ViewMode) -> Unit,
    sort: com.melone.gallery.data.model.SortOption,
    onSetSort: (com.melone.gallery.data.model.SortOption) -> Unit,
    onEnter: (String) -> Unit,
    onBack: () -> Unit,
    onOpenViewer: (List<MediaItem>, Int) -> Unit,
    selected: Set<String>,
    onToggle: (String) -> Unit,
    onSelectedChange: (Set<String>) -> Unit,
    onClearSel: () -> Unit,
    selectedAlbums: Set<String>,
    onToggleAlbum: (String) -> Unit,
    onClearAlbumSel: () -> Unit,
    serverFolders: List<com.melone.gallery.data.settings.ServerFolder>,
    viewModel: GalleryViewModel,
) {
    val view = remember(serverItems, path) { GalleryGrouping.serverFolder(serverItems, path) }
    val selectionMode = selected.isNotEmpty()
    val albumSelActive = selectedAlbums.isNotEmpty()
    val selectedItems = view.items.filter { it.id in selected }
    // Im Datei-Auswahlmodus: Zurück beendet erst die Auswahl. (Die Album-Auswahl behandelt
    // der BackHandler auf AlbumsScreen-Ebene.)
    BackHandler(enabled = selectionMode) { onClearSel() }

    when {
        albumSelActive -> SelectionTopBar(count = selectedAlbums.size, onClose = onClearAlbumSel)
        selectionMode -> SelectionTopBar(count = selected.size, onClose = onClearSel)
        else -> TopAppBar(
            scrollBehavior = scrollBehavior,
            windowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
            title = { Text(path.substringAfterLast('/'), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
                }
            },
            actions = {
                SortButton(sort, onSetSort)
                ViewModeButton(viewMode, onSetViewMode)
            },
        )
    }

    val onItemClick: (MediaItem) -> Unit = { item ->
        if (selectionMode) onToggle(item.id) else onOpenViewer(view.items, view.items.indexOf(item))
    }
    val gridState = rememberLazyGridState()
    val listState = rememberLazyListState()
    val orderedIds = remember(view.items) { view.items.map { it.id } }

    Box(modifier = Modifier.fillMaxSize()) {
        if (viewMode == ViewMode.GRID) {
            LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Fixed(3),
                contentPadding = PaddingValues(6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .dragSelect(gridState, orderedIds, selected, onSelectedChange),
            ) {
                if (view.folders.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) { SectionTitle("Ordner") }
                    items(view.folders, key = { "S:${it.path}" }) { entry ->
                        val key = "S:${entry.path}"
                        FolderCard(
                            entry.name, entry.cover, entry.count, emptySet(),
                            isSelected = key in selectedAlbums,
                            onClick = { if (selectedAlbums.isNotEmpty()) onToggleAlbum(key) else onEnter(entry.path) },
                            onLongClick = { onToggleAlbum(key) },
                        )
                    }
                }
                if (view.items.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) { SectionTitle("Dateien") }
                    items(view.items, key = { it.id }) { item ->
                        SelectableThumb(
                            item = item,
                            isSelected = item.id in selected,
                            onClick = onItemClick,
                        )
                    }
                }
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .dragSelect(listState, orderedIds, selected, onSelectedChange),
                contentPadding = PaddingValues(vertical = 4.dp),
            ) {
                if (view.folders.isNotEmpty()) {
                    item { SectionTitle("Ordner") }
                    listItems(view.folders, key = { "S:${it.path}" }) { entry ->
                        val key = "S:${entry.path}"
                        FolderRow(
                            entry.name, entry.count,
                            isSelected = key in selectedAlbums,
                            onClick = { if (selectedAlbums.isNotEmpty()) onToggleAlbum(key) else onEnter(entry.path) },
                            onLongClick = { onToggleAlbum(key) },
                        )
                    }
                }
                if (view.items.isNotEmpty()) {
                    item { SectionTitle("Dateien") }
                    listItems(view.items, key = { it.id }) { item ->
                        MediaListRow(
                            item = item,
                            showDetails = viewMode == ViewMode.DETAILS,
                            onClick = { onItemClick(item) },
                            selected = item.id in selected,
                        )
                    }
                }
            }
        }
        if (selectionMode) {
            SelectionActionsBar(
                selectedItems = selectedItems,
                serverFolders = serverFolders,
                viewModel = viewModel,
                onClear = onClearSel,
                onOpenInfo = { items -> onOpenViewer(items, 0); onClearSel() },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LocalAlbumLevel(
    album: Album,
    scrollBehavior: androidx.compose.material3.TopAppBarScrollBehavior,
    viewMode: ViewMode,
    columns: Int,
    onSetViewMode: (ViewMode) -> Unit,
    sort: com.melone.gallery.data.model.SortOption,
    onSetSort: (com.melone.gallery.data.model.SortOption) -> Unit,
    onBack: () -> Unit,
    onOpenViewer: (List<MediaItem>, Int) -> Unit,
    selected: Set<String>,
    onToggle: (String) -> Unit,
    onSelectedChange: (Set<String>) -> Unit,
    onClearSel: () -> Unit,
    serverFolders: List<com.melone.gallery.data.settings.ServerFolder>,
    viewModel: GalleryViewModel,
) {
    val selectionMode = selected.isNotEmpty()
    val selectedItems = album.items.filter { it.id in selected }
    BackHandler(enabled = selectionMode) { onClearSel() }

    if (selectionMode) {
        SelectionTopBar(count = selected.size, onClose = onClearSel)
    } else {
        TopAppBar(
            scrollBehavior = scrollBehavior,
            windowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
            title = { Text(album.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
                }
            },
            actions = {
                SortButton(sort, onSetSort)
                ViewModeButton(viewMode, onSetViewMode)
            },
        )
    }

    val onItemClick: (MediaItem) -> Unit = { item ->
        if (selectionMode) onToggle(item.id) else onOpenViewer(album.items, album.items.indexOf(item))
    }
    val gridState = rememberLazyGridState()
    val listState = rememberLazyListState()
    val orderedIds = remember(album.items) { album.items.map { it.id } }

    Box(modifier = Modifier.fillMaxSize()) {
        if (viewMode == ViewMode.GRID) {
            LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Fixed(columns),
                contentPadding = PaddingValues(2.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .dragSelect(gridState, orderedIds, selected, onSelectedChange),
            ) {
                items(album.items, key = { it.id }) { item ->
                    SelectableThumb(
                        item = item,
                        isSelected = item.id in selected,
                        onClick = onItemClick,
                    )
                }
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .dragSelect(listState, orderedIds, selected, onSelectedChange),
                contentPadding = PaddingValues(vertical = 4.dp),
            ) {
                listItems(album.items, key = { it.id }) { item ->
                    MediaListRow(
                        item = item,
                        showDetails = viewMode == ViewMode.DETAILS,
                        onClick = { onItemClick(item) },
                        selected = item.id in selected,
                    )
                }
            }
        }
        if (selectionMode) {
            SelectionActionsBar(
                selectedItems = selectedItems,
                serverFolders = serverFolders,
                viewModel = viewModel,
                onClear = onClearSel,
                onOpenInfo = { items -> onOpenViewer(items, 0); onClearSel() },
            )
        }
    }
}

@Composable
private fun SortButton(
    sort: com.melone.gallery.data.model.SortOption,
    onSet: (com.melone.gallery.data.model.SortOption) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "Sortieren")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            SortMenuItem("Erstellt am", com.melone.gallery.data.model.SortField.DATE_TAKEN, sort, onSet) { open = false }
            SortMenuItem("Geändert am", com.melone.gallery.data.model.SortField.DATE_MODIFIED, sort, onSet) { open = false }
            SortMenuItem("Name", com.melone.gallery.data.model.SortField.NAME, sort, onSet) { open = false }
            androidx.compose.material3.HorizontalDivider()
            DropdownMenuItem(
                text = {
                    Text(
                        if (sort.direction == com.melone.gallery.data.model.SortDirection.DESC) "Aufsteigend" else "Absteigend",
                    )
                },
                onClick = {
                    val dir = if (sort.direction == com.melone.gallery.data.model.SortDirection.DESC)
                        com.melone.gallery.data.model.SortDirection.ASC
                    else com.melone.gallery.data.model.SortDirection.DESC
                    onSet(sort.copy(direction = dir)); open = false
                },
            )
        }
    }
}

@Composable
private fun SortMenuItem(
    label: String,
    field: com.melone.gallery.data.model.SortField,
    current: com.melone.gallery.data.model.SortOption,
    onSet: (com.melone.gallery.data.model.SortOption) -> Unit,
    dismiss: () -> Unit,
) {
    DropdownMenuItem(
        text = { Text(label + if (current.field == field) "  ✓" else "") },
        onClick = { onSet(current.copy(field = field)); dismiss() },
    )
}

@Composable
private fun ViewModeButton(current: ViewMode, onSet: (ViewMode) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(
                when (current) {
                    ViewMode.GRID -> Icons.Filled.GridView
                    ViewMode.LIST -> Icons.AutoMirrored.Filled.ViewList
                    ViewMode.DETAILS -> Icons.Filled.ViewAgenda
                },
                contentDescription = "Ansicht",
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Raster") }, onClick = { onSet(ViewMode.GRID); open = false })
            DropdownMenuItem(text = { Text("Liste") }, onClick = { onSet(ViewMode.LIST); open = false })
            DropdownMenuItem(text = { Text("Details") }, onClick = { onSet(ViewMode.DETAILS); open = false })
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun FolderRow(
    name: String,
    count: Int,
    isSelected: Boolean = false,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .background(if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else Color.Transparent)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (isSelected) Icons.Filled.CheckCircle else Icons.Filled.Folder,
            contentDescription = null,
            tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(12.dp))
        Text(name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("$count", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderCard(
    name: String,
    cover: MediaItem?,
    count: Int,
    storageKinds: Set<StorageKind>,
    isSelected: Boolean = false,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    Column(modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)) {
        Box(modifier = Modifier.fillMaxWidth().aspectRatio(1f)) {
            cover?.let {
                MediaThumbnail(
                    item = it,
                    modifier = Modifier.fillMaxSize(),
                    cornerRadius = 10,
                    showVideoBadges = false,
                )
            }
            if (isSelected) {
                Box(Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)).background(Color.Black.copy(alpha = 0.35f)))
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(24.dp),
                )
            }
        }
        Text(
            text = name,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp, start = 2.dp),
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 2.dp),
        ) {
            Text(
                text = "$count",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (storageKinds.isNotEmpty()) {
                Spacer(Modifier.width(6.dp))
                if (StorageKind.INTERNAL in storageKinds) {
                    Icon(
                        imageVector = Icons.Filled.Smartphone,
                        contentDescription = "Interner Speicher",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp),
                    )
                }
                if (StorageKind.SD in storageKinds) {
                    if (StorageKind.INTERNAL in storageKinds) Spacer(Modifier.width(3.dp))
                    Icon(
                        imageVector = Icons.Filled.SdCard,
                        contentDescription = "SD-Karte",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        }
    }
}
