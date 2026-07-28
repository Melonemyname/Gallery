package com.melone.gallery.ui.trash

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.melone.gallery.data.local.MediaStoreSource
import com.melone.gallery.data.model.MediaItem
import com.melone.gallery.data.model.MediaSource
import com.melone.gallery.data.settings.SettingsRepository
import com.melone.gallery.data.smb.SmbManager
import com.melone.gallery.data.smb.SmbTrashEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Ein Element im Papierkorb (lokal = System-Papierkorb, Server = .trash). */
sealed interface TrashItem {
    val media: MediaItem

    data class Local(override val media: MediaItem) : TrashItem
    data class Server(override val media: MediaItem, val entry: SmbTrashEntry) : TrashItem
}

class TrashViewModel(
    private val local: MediaStoreSource,
    private val smb: SmbManager,
    private val settings: SettingsRepository,
) : ViewModel() {

    private val _items = MutableStateFlow<List<TrashItem>>(emptyList())
    val items: StateFlow<List<TrashItem>> = _items.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val videoExts = setOf("mp4", "mkv", "mov", "avi", "webm", "3gp", "m4v", "ts")

    fun load() {
        viewModelScope.launch {
            _loading.value = true
            val result = ArrayList<TrashItem>()
            runCatching { local.queryTrashed() }.getOrDefault(emptyList())
                .forEach { result += TrashItem.Local(it) }
            val shares = settings.serverConfig.first().folders.map { it.share }.distinct()
            withContext(Dispatchers.IO) {
                shares.forEach { share ->
                    runCatching { smb.listTrash(share) }.getOrDefault(emptyList()).forEach { e ->
                        result += TrashItem.Server(buildMedia(e), e)
                    }
                }
            }
            _items.value = result.sortedByDescending { it.media.dateTaken }
            _loading.value = false
        }
    }

    fun restoreServer(item: TrashItem.Server) = viewModelScope.launch {
        withContext(Dispatchers.IO) {
            runCatching { smb.restoreFromTrash(item.entry.share, item.entry.trashName, item.entry.origPath) }
        }
        _items.value = _items.value - item
    }

    fun deleteServerPermanent(item: TrashItem.Server) = viewModelScope.launch {
        withContext(Dispatchers.IO) {
            runCatching { smb.deleteTrashEntry(item.entry.share, item.entry.trashName) }
        }
        _items.value = _items.value - item
    }

    /** Nach lokaler Wiederherstellung/Löschung (per System-Dialog) aus der Liste entfernen. */
    fun removeLocal(item: TrashItem) {
        _items.value = _items.value - item
    }

    /** Alle Server-Einträge im Papierkorb endgültig löschen (lokale via System-Dialog). */
    fun emptyServer() = viewModelScope.launch {
        val servers = _items.value.filterIsInstance<TrashItem.Server>()
        if (servers.isEmpty()) return@launch
        withContext(Dispatchers.IO) {
            servers.forEach { runCatching { smb.deleteTrashEntry(it.entry.share, it.entry.trashName) } }
        }
        _items.value = _items.value.filterNot { it is TrashItem.Server }
    }

    /** Nach dem System-Löschdialog alle lokalen Einträge aus der Liste entfernen. */
    fun removeAllLocal() {
        _items.value = _items.value.filterNot { it is TrashItem.Local }
    }

    private fun buildMedia(e: SmbTrashEntry): MediaItem {
        val name = e.origPath.substringAfterLast('/')
        val ext = name.substringAfterLast('.', "").lowercase()
        val isVideo = ext in videoExts
        return MediaItem(
            id = "smb://${e.share}/.trash/${e.trashName}",
            source = MediaSource.SERVER,
            displayName = name,
            fullPath = "${e.share}/.trash/${e.trashName}",
            sizeBytes = e.sizeBytes,
            dateTaken = e.trashMillis,
            width = 0,
            height = 0,
            mimeType = if (isVideo) "video/*" else "image/*",
            isVideo = isVideo,
            smbShare = e.share,
            smbPath = ".trash/${e.trashName}",
        )
    }
}
