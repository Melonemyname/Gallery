package com.melone.gallery.ui.gallery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.melone.gallery.data.model.MediaItem
import com.melone.gallery.data.model.MediaSource
import com.melone.gallery.data.model.SortOption
import com.melone.gallery.data.model.SourceFilter
import com.melone.gallery.data.model.ViewMode
import com.melone.gallery.data.model.Grouping
import com.melone.gallery.data.settings.ServerFolder
import com.melone.gallery.data.settings.SettingsRepository
import com.melone.gallery.data.settings.UiPrefs
import com.melone.gallery.domain.GalleryGrouping
import com.melone.gallery.domain.GalleryRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class GalleryUiState(
    val hasPermission: Boolean = false,
    val loadingLocal: Boolean = false,
    val loadingServer: Boolean = false,
    val localItems: List<MediaItem> = emptyList(),
    val serverItems: List<MediaItem> = emptyList(),
    val prefs: UiPrefs = UiPrefs(),
    val serverConfigured: Boolean = false,
    val serverFolders: List<ServerFolder> = emptyList(),
    val serverError: String? = null,
    val prefsLoaded: Boolean = false,
) {
    val isLoading: Boolean get() = loadingLocal || loadingServer
}

class GalleryViewModel(
    private val repo: GalleryRepository,
    private val settings: SettingsRepository,
    private val serverCache: com.melone.gallery.data.settings.ServerCache,
) : ViewModel() {

    private val _state = MutableStateFlow(GalleryUiState())
    val state: StateFlow<GalleryUiState> = _state.asStateFlow()

    private var lastServerFolders: List<ServerFolder>? = null
    private var initialServerHandled = false
    /** Zuletzt gesehene Versions-Token je Freigabe (für den Reload-bei-Änderung-Check). */
    private var lastTokens: Map<String, String> = emptyMap()
    /** Erst nach der ersten Server-Ladung/Cache-Primung darf der Resume-Check laufen. */
    private var serverPrimed = false

    /** Liste, über die der Viewer blättert (vom aufrufenden Screen gesetzt). */
    private var viewerList: List<MediaItem> = emptyList()
    fun setViewerItems(items: List<MediaItem>) { viewerList = items }
    fun viewerItems(): List<MediaItem> = viewerList

    init {
        viewModelScope.launch {
            settings.uiPrefs.collect { p -> _state.update { it.copy(prefs = p, prefsLoaded = true) } }
        }
        viewModelScope.launch {
            settings.serverConfig.collect { config ->
                _state.update {
                    it.copy(serverConfigured = config.isConfigured, serverFolders = config.folders)
                }
                if (config.folders != lastServerFolders) {
                    lastServerFolders = config.folders
                    if (!initialServerHandled) {
                        initialServerHandled = true
                        // Beim ersten Mal: aus Cache anzeigen, außer "beim Start neu laden".
                        val reloadOnStart = settings.uiPrefs.first().reloadServerOnStart
                        val cached = if (config.folders.isEmpty()) emptyList() else serverCache.load()
                        if (cached.isNotEmpty() && !reloadOnStart) {
                            _state.update { it.copy(serverItems = cached) }
                            // Token primen, damit der sofortige ON_RESUME nicht unnötig neu lädt
                            // (respektiert "nicht bei jedem Start neu laden").
                            lastTokens = runCatching { repo.serverTokens(config.folders.map { it.share }) }
                                .getOrDefault(emptyMap())
                            serverPrimed = true
                        } else {
                            loadServer(config.folders)
                        }
                    } else {
                        // Nutzer hat Ordner geändert → neu laden.
                        loadServer(config.folders)
                    }
                }
            }
        }
    }

    fun onPermissionResult(granted: Boolean) {
        _state.update { it.copy(hasPermission = granted) }
        if (granted && _state.value.localItems.isEmpty() && !_state.value.loadingLocal) {
            loadLocal()
        }
    }

    fun refresh() {
        if (_state.value.hasPermission) loadLocal()
        loadServer(_state.value.serverFolders)
    }

    private fun loadLocal() {
        viewModelScope.launch {
            _state.update { it.copy(loadingLocal = true) }
            val items = runCatching { repo.loadLocal() }.getOrDefault(emptyList())
            _state.update { it.copy(localItems = items, loadingLocal = false) }
        }
    }

    private var lastServerLoadAt = 0L

    private fun loadServer(folders: List<ServerFolder>) {
        if (folders.isEmpty()) {
            _state.update { it.copy(serverItems = emptyList(), serverError = null) }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(loadingServer = true, serverError = null) }
            val result = runCatching { repo.loadServer(folders) }
            lastServerLoadAt = android.os.SystemClock.elapsedRealtime()
            result.getOrNull()?.let { items ->
                runCatching { serverCache.save(items) }
                // Aktuellen Stand der Versions-Token merken (für den Änderungs-Check).
                lastTokens = runCatching { repo.serverTokens(folders.map { it.share }) }
                    .getOrDefault(lastTokens)
            }
            serverPrimed = true
            _state.update {
                it.copy(
                    loadingServer = false,
                    serverItems = result.getOrDefault(it.serverItems),
                    serverError = result.exceptionOrNull()?.let { e -> e.message ?: e.javaClass.simpleName },
                )
            }
        }
    }

    /** Setzt/entfernt Items aus dem Server-State (nach Löschen/Verschieben). */
    fun removeServerItems(ids: Set<String>) {
        if (ids.isEmpty()) return
        _state.update { st -> st.copy(serverItems = st.serverItems.filterNot { it.id in ids }) }
        viewModelScope.launch { runCatching { serverCache.save(_state.value.serverItems) } }
    }

    fun removeLocalItems(ids: Set<String>) {
        if (ids.isEmpty()) return
        _state.update { st -> st.copy(localItems = st.localItems.filterNot { it.id in ids }) }
    }

    /**
     * Aktualisiert den Server, wenn die App wieder in den Vordergrund kommt.
     * Bevorzugt den billigen Änderungs-Check über die Versions-Datei (`.galerie-version`):
     * es wird nur dann komplett neu gelistet, wenn sich auf dem Server wirklich etwas
     * geändert hat. Existiert kein Token (kein Server-Watcher eingerichtet), fällt es auf
     * das bisherige Verhalten zurück (zeitgedrosselt einmal pro Minute komplett neu laden).
     */
    fun refreshServerOnResume() {
        val folders = _state.value.serverFolders
        if (folders.isEmpty() || _state.value.loadingServer || !serverPrimed) return
        viewModelScope.launch {
            val shares = folders.map { it.share }.distinct()
            val tokens = runCatching { repo.serverTokens(shares) }.getOrDefault(emptyMap())
            if (tokens.isNotEmpty()) {
                // Token vorhanden → nur laden, wenn sich eines geändert hat.
                val changed = tokens.any { (share, tok) -> lastTokens[share] != tok }
                if (changed) loadServer(folders)
            } else if (android.os.SystemClock.elapsedRealtime() - lastServerLoadAt > 60_000) {
                // Kein Token/Watcher → altes zeitgedrosseltes Verhalten.
                loadServer(folders)
            }
        }
    }

    // --- Einstellungen der Ansicht ---
    fun setViewMode(mode: ViewMode) = viewModelScope.launch { settings.setViewMode(mode) }
    fun setGrouping(g: Grouping) = viewModelScope.launch { settings.setGrouping(g) }
    fun setSort(s: SortOption) = viewModelScope.launch { settings.setSort(s) }
    fun setSourceFilter(f: SourceFilter) = viewModelScope.launch { settings.setSourceFilter(f) }
    fun setGridColumns(c: Int) = viewModelScope.launch { settings.setGridColumns(c) }
    fun setTimelineMixed(mixed: Boolean) = viewModelScope.launch { settings.setTimelineMixed(mixed) }

    /** Nach Quelle gefilterte + sortierte Liste (Grundlage für Timeline & Viewer). */
    fun visibleItems(): List<MediaItem> = computeVisible(_state.value)

    companion object {
        fun computeVisible(state: GalleryUiState): List<MediaItem> {
            val combined = when (state.prefs.sourceFilter) {
                SourceFilter.ALL -> state.localItems + state.serverItems
                SourceFilter.LOCAL -> state.localItems
                SourceFilter.SERVER -> state.serverItems
            }
            return GalleryGrouping.sort(combined, state.prefs.sort)
        }

        fun itemsForSource(state: GalleryUiState, source: MediaSource): List<MediaItem> {
            val list = if (source == MediaSource.LOCAL) state.localItems else state.serverItems
            return GalleryGrouping.sort(list, state.prefs.sort)
        }
    }
}
