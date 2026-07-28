package com.melone.gallery.ui.gallery

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items as listItems
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.CalendarViewDay
import androidx.compose.material.icons.filled.CalendarViewMonth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.melone.gallery.data.settings.ServerFolder
import com.melone.gallery.data.transfer.TransferTarget
import kotlinx.coroutines.launch
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.MultiplePermissionsState
import com.melone.gallery.data.model.Grouping
import com.melone.gallery.data.model.MediaItem
import com.melone.gallery.data.model.SortDirection
import com.melone.gallery.data.model.SortField
import com.melone.gallery.data.model.SortOption
import com.melone.gallery.data.model.SourceFilter
import com.melone.gallery.data.model.ViewMode
import com.melone.gallery.ui.components.MediaListRow
import com.melone.gallery.ui.components.MediaThumbnail
import com.melone.gallery.ui.components.PermissionRequest
import com.melone.gallery.ui.components.SelectableThumb
import com.melone.gallery.ui.components.SelectionActionsBar
import com.melone.gallery.ui.components.SelectionTopBar
import kotlin.math.roundToInt

data class SourceGroup(val sourceTitle: String?, val sections: List<com.melone.gallery.domain.TimelineSection>)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalPermissionsApi::class)
@Composable
fun GalleryScreen(
    viewModel: GalleryViewModel,
    permissionState: MultiplePermissionsState,
    onOpenViewer: (List<MediaItem>, Int) -> Unit,
    onOpenSettings: () -> Unit,
    contentPadding: PaddingValues,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val app = context.applicationContext as com.melone.gallery.GalleryApplication
    val transfer = app.container.mediaTransfer

    val structure = remember(state.localItems, state.serverItems, state.prefs) { buildStructure(state) }
    val flatItems = remember(structure) { structure.flatMap { g -> g.sections.flatMap { it.items } } }
    val idToDate = remember(flatItems) {
        flatItems.associate { it.id to com.melone.gallery.domain.DateFormatters.monthHeader(it.dateTaken) }
    }

    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    val selectionMode = selected.isNotEmpty()
    val selectedItems = flatItems.filter { it.id in selected }
    fun toggle(id: String) { selected = if (id in selected) selected - id else selected + id }
    fun clearSel() { selected = emptySet() }

    // Im Auswahlmodus: Zurück beendet erst die Auswahl (statt die App/den Tab zu verlassen).
    BackHandler(enabled = selectionMode) { clearSel() }

    Column(modifier = Modifier.fillMaxSize().padding(contentPadding)) {
        // Auswahlmodus ersetzt nur die Top-Bar; die Filterzeile bleibt erhalten,
        // damit sich der Inhalt beim langen Halten nicht nach oben schiebt.
        if (selectionMode) {
            SelectionTopBar(count = selected.size, onClose = { clearSel() })
        } else {
            GalleryTopBar(
                viewMode = state.prefs.viewMode,
                grouping = state.prefs.grouping,
                sort = state.prefs.sort,
                timelineMixed = state.prefs.timelineMixed,
                loading = state.isLoading,
                onViewMode = viewModel::setViewMode,
                onGrouping = viewModel::setGrouping,
                onSort = viewModel::setSort,
                onToggleMixed = viewModel::setTimelineMixed,
                onRefresh = viewModel::refresh,
                onOpenSettings = onOpenSettings,
            )
        }
        SourceFilterRow(
            selected = state.prefs.sourceFilter,
            serverConfigured = state.serverConfigured,
            onSelect = viewModel::setSourceFilter,
        )

        if (!state.hasPermission) {
            PermissionRequest(
                shouldShowRationale = permissionState.shouldShowRationale,
                onRequest = { permissionState.launchMultiplePermissionRequest() },
            )
            return@Column
        }
        if (flatItems.isEmpty()) {
            EmptyOrLoading(state = state)
            return@Column
        }

        Box(modifier = Modifier.fillMaxSize()) {
            val onItemClick: (MediaItem) -> Unit = { item ->
                if (selectionMode) toggle(item.id) else onOpenViewer(flatItems, flatItems.indexOf(item))
            }
            val onItemLong: (MediaItem) -> Unit = { item -> toggle(item.id) }
            when (state.prefs.viewMode) {
                ViewMode.GRID -> TimelineGrid(
                    structure = structure,
                    columns = state.prefs.gridColumns,
                    selected = selected,
                    idToDate = idToDate,
                    onColumnsChange = viewModel::setGridColumns,
                    onItemClick = onItemClick,
                    onItemLong = onItemLong,
                )
                ViewMode.LIST, ViewMode.DETAILS -> TimelineList(
                    structure = structure,
                    details = state.prefs.viewMode == ViewMode.DETAILS,
                    selected = selected,
                    onItemClick = onItemClick,
                    onItemLong = onItemLong,
                )
            }
            if (selectionMode) {
                SelectionActionsBar(
                    selectedItems = selectedItems,
                    serverFolders = state.serverFolders,
                    viewModel = viewModel,
                    onClear = { clearSel() },
                    onOpenInfo = { items -> onOpenViewer(items, 0); clearSel() },
                )
            }
        }
    }
}

@Composable
private fun EmptyOrLoading(state: GalleryUiState) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (state.isLoading) {
            CircularProgressIndicator()
        } else {
            Text(
                text = if (state.serverError != null && state.prefs.sourceFilter != SourceFilter.LOCAL)
                    "Keine Medien. Server: ${state.serverError}"
                else "Keine Medien gefunden.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TimelineGrid(
    structure: List<SourceGroup>,
    columns: Int,
    selected: Set<String>,
    idToDate: Map<String, String>,
    onColumnsChange: (Int) -> Unit,
    onItemClick: (MediaItem) -> Unit,
    onItemLong: (MediaItem) -> Unit,
) {
    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    val showScrollTop by remember { derivedStateOf { gridState.firstVisibleItemIndex > 8 } }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Fixed(columns),
            modifier = Modifier
                .fillMaxSize()
                .pinchColumns(columns, onColumnsChange),
            contentPadding = PaddingValues(2.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            structure.forEach { group ->
                group.sourceTitle?.let { title ->
                    item(span = { GridItemSpan(maxLineSpan) }) { SourceHeader(title) }
                }
                group.sections.forEach { section ->
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        DateHeader(section.title, section.items.size)
                    }
                    gridItems(section.items, key = { it.id }) { item ->
                        SelectableThumb(
                            item = item,
                            isSelected = item.id in selected,
                            onClick = onItemClick,
                            onLong = onItemLong,
                        )
                    }
                }
            }
        }
        FastScrollbar(state = gridState, idToDate = idToDate)
        ScrollToTopButton(visible = showScrollTop) {
            scope.launch { gridState.animateScrollToItem(0) }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BoxScope.FastScrollbar(state: LazyGridState, idToDate: Map<String, String>) {
    val total = state.layoutInfo.totalItemsCount
    if (total < 40) return
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    var dragging by remember { mutableStateOf(false) }
    var dragFrac by remember { mutableStateOf(0f) }
    val progress = if (total <= 1) 0f else (state.firstVisibleItemIndex.toFloat() / (total - 1)).coerceIn(0f, 1f)
    val shownFrac = if (dragging) dragFrac else progress
    val currentKey = state.layoutInfo.visibleItemsInfo.firstOrNull { it.key is String && idToDate.containsKey(it.key) }?.key
    val dateLabel = idToDate[currentKey]

    BoxWithConstraints(modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight()) {
        val trackHpx = constraints.maxHeight.toFloat()
        val thumbH = 48.dp
        val thumbHpx = with(density) { thumbH.toPx() }
        val thumbYpx = (shownFrac * (trackHpx - thumbHpx)).coerceIn(0f, (trackHpx - thumbHpx).coerceAtLeast(0f))

        if (dragging && dateLabel != null) {
            Surface(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset { IntOffset(0, thumbYpx.roundToInt()) }
                    .padding(end = 34.dp),
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.primary,
            ) {
                Text(
                    dateLabel,
                    color = MaterialTheme.colorScheme.onPrimary,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }

        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset { IntOffset(0, thumbYpx.roundToInt()) }
                .padding(end = 3.dp)
                .width(6.dp)
                .height(thumbH)
                .clip(RoundedCornerShape(3.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = if (dragging) 1f else 0.55f)),
        )

        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(28.dp)
                .pointerInput(total) {
                    detectVerticalDragGestures(
                        onDragStart = { off ->
                            dragging = true
                            dragFrac = (off.y / size.height).coerceIn(0f, 1f)
                            scope.launch { state.scrollToItem((dragFrac * total).toInt().coerceIn(0, (total - 1).coerceAtLeast(0))) }
                        },
                        onDragEnd = { dragging = false },
                        onDragCancel = { dragging = false },
                    ) { change, _ ->
                        dragFrac = (change.position.y / size.height).coerceIn(0f, 1f)
                        scope.launch { state.scrollToItem((dragFrac * total).toInt().coerceIn(0, (total - 1).coerceAtLeast(0))) }
                    }
                },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TimelineList(
    structure: List<SourceGroup>,
    details: Boolean,
    selected: Set<String>,
    onItemClick: (MediaItem) -> Unit,
    onItemLong: (MediaItem) -> Unit,
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val showScrollTop by remember { derivedStateOf { listState.firstVisibleItemIndex > 12 } }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 4.dp),
        ) {
            structure.forEachIndexed { groupIndex, group ->
                group.sourceTitle?.let { title ->
                    item(key = "src-$groupIndex") { SourceHeader(title) }
                }
                group.sections.forEach { section ->
                    stickyHeader(key = "date-$groupIndex-${section.key}") {
                        DateHeader(section.title, section.items.size, sticky = true)
                    }
                    listItems(section.items, key = { it.id }) { item ->
                        MediaListRow(
                            item = item,
                            showDetails = details,
                            onClick = { onItemClick(item) },
                            onLongClick = { onItemLong(item) },
                            selected = item.id in selected,
                        )
                    }
                }
            }
        }
        ScrollToTopButton(visible = showScrollTop) {
            scope.launch { listState.animateScrollToItem(0) }
        }
    }
}

@Composable
private fun BoxScope.ScrollToTopButton(visible: Boolean, onClick: () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + scaleIn(),
        exit = fadeOut() + scaleOut(),
        modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
    ) {
        SmallFloatingActionButton(onClick = onClick) {
            Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Nach oben")
        }
    }
}

@Composable
private fun SourceHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun DateHeader(title: String, count: Int, sticky: Boolean = false) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (sticky) Modifier.background(MaterialTheme.colorScheme.background) else Modifier)
            .padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SourceFilterRow(
    selected: SourceFilter,
    serverConfigured: Boolean,
    onSelect: (SourceFilter) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(selected = selected == SourceFilter.ALL, onClick = { onSelect(SourceFilter.ALL) }, label = { Text("Alle") })
        FilterChip(selected = selected == SourceFilter.LOCAL, onClick = { onSelect(SourceFilter.LOCAL) }, label = { Text("Gerät") })
        FilterChip(
            selected = selected == SourceFilter.SERVER,
            onClick = { onSelect(SourceFilter.SERVER) },
            enabled = serverConfigured,
            label = { Text("Server") },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GalleryTopBar(
    viewMode: ViewMode,
    grouping: Grouping,
    sort: SortOption,
    timelineMixed: Boolean,
    loading: Boolean,
    onViewMode: (ViewMode) -> Unit,
    onGrouping: (Grouping) -> Unit,
    onSort: (SortOption) -> Unit,
    onToggleMixed: (Boolean) -> Unit,
    onRefresh: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    var sortMenu by remember { mutableStateOf(false) }
    var viewMenu by remember { mutableStateOf(false) }

    TopAppBar(
        title = { Text("Galerie") },
        actions = {
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp).padding(end = 4.dp),
                    strokeWidth = 2.dp,
                )
            }
            // Tag/Monat
            IconButton(onClick = {
                onGrouping(if (grouping == Grouping.DAY) Grouping.MONTH else Grouping.DAY)
            }) {
                Icon(
                    if (grouping == Grouping.DAY) Icons.Filled.CalendarViewDay else Icons.Filled.CalendarViewMonth,
                    contentDescription = "Gruppierung",
                )
            }
            // Ansicht
            Box {
                IconButton(onClick = { viewMenu = true }) {
                    Icon(
                        when (viewMode) {
                            ViewMode.GRID -> Icons.Filled.GridView
                            ViewMode.LIST -> Icons.AutoMirrored.Filled.ViewList
                            ViewMode.DETAILS -> Icons.Filled.ViewAgenda
                        },
                        contentDescription = "Ansicht",
                    )
                }
                DropdownMenu(expanded = viewMenu, onDismissRequest = { viewMenu = false }) {
                    DropdownMenuItem(text = { Text("Miniaturansicht") }, onClick = { onViewMode(ViewMode.GRID); viewMenu = false })
                    DropdownMenuItem(text = { Text("Liste") }, onClick = { onViewMode(ViewMode.LIST); viewMenu = false })
                    DropdownMenuItem(text = { Text("Details") }, onClick = { onViewMode(ViewMode.DETAILS); viewMenu = false })
                    androidx.compose.material3.HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text(if (timelineMixed) "Nach Quelle trennen" else "Gemischt anzeigen") },
                        onClick = { onToggleMixed(!timelineMixed); viewMenu = false },
                    )
                }
            }
            // Sortierung
            Box {
                IconButton(onClick = { sortMenu = true }) {
                    Icon(Icons.Filled.Sort, contentDescription = "Sortieren")
                }
                DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                    SortMenuItem("Erstellt am", SortField.DATE_TAKEN, sort, onSort) { sortMenu = false }
                    SortMenuItem("Geändert am", SortField.DATE_MODIFIED, sort, onSort) { sortMenu = false }
                    SortMenuItem("Name", SortField.NAME, sort, onSort) { sortMenu = false }
                    androidx.compose.material3.HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text(if (sort.direction == SortDirection.DESC) "Aufsteigend" else "Absteigend") },
                        onClick = {
                            onSort(sort.copy(direction = if (sort.direction == SortDirection.DESC) SortDirection.ASC else SortDirection.DESC))
                            sortMenu = false
                        },
                    )
                }
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

@Composable
private fun SortMenuItem(
    label: String,
    field: SortField,
    current: SortOption,
    onSort: (SortOption) -> Unit,
    dismiss: () -> Unit,
) {
    DropdownMenuItem(
        text = { Text(label + if (current.field == field) "  ✓" else "") },
        onClick = { onSort(current.copy(field = field)); dismiss() },
    )
}

private fun Modifier.pinchColumns(current: Int, onColumnsChange: (Int) -> Unit): Modifier =
    this.pointerInput(Unit) {
        var accum = 1f
        detectTransformGestures { _, _, zoom, _ ->
            accum *= zoom
            if (accum > 1.15f) {
                onColumnsChange((current - 1).coerceAtLeast(2))
                accum = 1f
            } else if (accum < 0.87f) {
                onColumnsChange((current + 1).coerceAtMost(8))
                accum = 1f
            }
        }
    }

/** Baut die Render-Struktur (gemischt vs. nach Quelle getrennt). */
fun buildStructure(state: GalleryUiState): List<SourceGroup> {
    val prefs = state.prefs
    return if (prefs.sourceFilter == SourceFilter.ALL && !prefs.timelineMixed) {
        val local = GalleryViewModel.itemsForSource(state, com.melone.gallery.data.model.MediaSource.LOCAL)
        val server = GalleryViewModel.itemsForSource(state, com.melone.gallery.data.model.MediaSource.SERVER)
        buildList {
            if (local.isNotEmpty()) add(SourceGroup("Auf dem Gerät", com.melone.gallery.domain.GalleryGrouping.timeline(local, prefs.grouping)))
            if (server.isNotEmpty()) add(SourceGroup("Server", com.melone.gallery.domain.GalleryGrouping.timeline(server, prefs.grouping)))
        }
    } else {
        val visible = GalleryViewModel.computeVisible(state)
        listOf(SourceGroup(null, com.melone.gallery.domain.GalleryGrouping.timeline(visible, prefs.grouping)))
    }
}

