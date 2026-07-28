package com.melone.gallery.domain

import com.melone.gallery.data.model.Grouping
import com.melone.gallery.data.model.MediaItem
import com.melone.gallery.data.model.MediaSource
import com.melone.gallery.data.model.SortDirection
import com.melone.gallery.data.model.SortField
import com.melone.gallery.data.model.SortOption
import java.util.Calendar

/** Ein Datums-Abschnitt in der Timeline. */
data class TimelineSection(
    val key: String,
    val title: String,
    val items: List<MediaItem>,
)

/** Speicherort eines lokalen Mediums. */
enum class StorageKind { INTERNAL, SD }

/** Ein Album (lokaler Ordner oder Server-Ordner). */
data class Album(
    val id: String,
    val name: String,
    val source: MediaSource,
    val items: List<MediaItem>,
) {
    val cover: MediaItem? get() = items.firstOrNull()
    val count: Int get() = items.size

    /**
     * Speicherorte der (lokalen) Album-Inhalte. Bei gemergten Ordnern kann das
     * sowohl [StorageKind.INTERNAL] als auch [StorageKind.SD] enthalten.
     */
    val storageKinds: Set<StorageKind>
        get() = if (source != MediaSource.LOCAL) emptySet()
        else items.mapNotNullTo(LinkedHashSet()) { storageKindOf(it.fullPath) }

    private fun storageKindOf(path: String): StorageKind? = when {
        path.startsWith("/storage/emulated/") -> StorageKind.INTERNAL
        path.startsWith("/storage/self/") -> StorageKind.INTERNAL
        path.startsWith("/data/") -> StorageKind.INTERNAL
        path.startsWith("/storage/") -> StorageKind.SD
        else -> null
    }
}

/** Ein Unterordner im Server-Baum (mit Cover + Gesamtanzahl darunter). */
data class FolderEntry(
    val path: String,
    val name: String,
    val cover: MediaItem?,
    val count: Int,
)

/** Ansicht eines Server-Ordners: Unterordner + direkt enthaltene Dateien. */
data class FolderView(
    val folders: List<FolderEntry>,
    val items: List<MediaItem>,
)

object GalleryGrouping {

    /**
     * Blättert durch die Server-Ordnerstruktur wie auf der Platte. [path] = "" liefert die
     * Freigaben (oberste Ebene); sonst z. B. "bilder/Konzerte". Liefert die direkten
     * Unterordner (rekursive Anzahl + Cover) und die direkt in diesem Ordner liegenden Dateien.
     */
    fun serverFolder(items: List<MediaItem>, path: String): FolderView {
        val prefix = if (path.isEmpty()) "" else "$path/"
        val direct = ArrayList<MediaItem>()
        val childItems = LinkedHashMap<String, MutableList<MediaItem>>()
        for (item in items) {
            val folder = serverFolderPath(item)
            if (folder == path) {
                direct.add(item)
                continue
            }
            if (path.isEmpty() || folder.startsWith(prefix)) {
                val rest = if (path.isEmpty()) folder else folder.removePrefix(prefix)
                val next = rest.substringBefore('/')
                if (next.isNotEmpty()) {
                    val childPath = if (path.isEmpty()) next else "$path/$next"
                    childItems.getOrPut(childPath) { mutableListOf() }.add(item)
                }
            }
        }
        val folders = childItems.map { (cp, list) ->
            FolderEntry(path = cp, name = cp.substringAfterLast('/'), cover = list.firstOrNull(), count = list.size)
        }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
        return FolderView(folders, direct)
    }

    /** Ordnerpfad eines Server-Items ("share/dir…", ohne Dateiname). */
    private fun serverFolderPath(item: MediaItem): String {
        val share = item.smbShare ?: ""
        val rel = item.smbPath ?: ""
        val dir = rel.substringBeforeLast('/', "")
        return if (dir.isEmpty()) share else "$share/$dir"
    }


    fun sort(items: List<MediaItem>, sort: SortOption): List<MediaItem> {
        val comparator: Comparator<MediaItem> = when (sort.field) {
            SortField.DATE_TAKEN, SortField.DATE_MODIFIED -> compareBy { it.dateTaken }
            SortField.NAME -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.displayName }
        }
        val sorted = items.sortedWith(comparator)
        return if (sort.direction == SortDirection.DESC) sorted.reversed() else sorted
    }

    /**
     * Gruppiert nach Tag oder Monat. Erwartet bereits sortierte Items
     * (Reihenfolge der Abschnitte folgt der Sortierung).
     */
    fun timeline(items: List<MediaItem>, grouping: Grouping): List<TimelineSection> {
        if (items.isEmpty()) return emptyList()
        val sections = LinkedHashMap<String, MutableList<MediaItem>>()
        for (item in items) {
            val key = when (grouping) {
                Grouping.DAY -> dayKey(item.dateTaken)
                Grouping.MONTH -> monthKey(item.dateTaken)
            }
            sections.getOrPut(key) { mutableListOf() }.add(item)
        }
        return sections.map { (key, list) ->
            val title = when (grouping) {
                Grouping.DAY -> DateFormatters.dayHeader(list.first().dateTaken)
                Grouping.MONTH -> DateFormatters.monthHeader(list.first().dateTaken)
            }
            TimelineSection(key = key, title = title, items = list)
        }
    }

    /**
     * Alben aus den (bereits sortierten) Items ableiten: je Ordner ein Album.
     * Lokal wird nach **Ordnername** gruppiert, damit gleichnamige Ordner auf
     * verschiedenen Volumes (z. B. „Download" intern + auf SD-Karte) zu einem
     * Album zusammengeführt werden. Server-Ordner bleiben nach vollem Pfad getrennt.
     */
    fun albums(items: List<MediaItem>): List<Album> {
        val map = LinkedHashMap<String, MutableList<MediaItem>>()
        for (item in items) {
            map.getOrPut(albumKey(item)) { mutableListOf() }.add(item)
        }
        return map.map { (key, list) ->
            val src = list.first().source
            Album(
                id = "${src.name}:$key",
                name = key.substringAfterLast('/').ifEmpty { key },
                source = src,
                items = list,
            )
        }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    }

    private fun albumKey(item: MediaItem): String = when (item.source) {
        // Nur der Ordnername → gleichnamige Ordner über Volumes hinweg mergen.
        MediaSource.LOCAL -> {
            val dir = item.fullPath.substringBeforeLast('/', "")
            dir.substringAfterLast('/', "").ifEmpty { "Intern" }
        }
        // Voller Pfad ab Freigabe → Server-Ordner bleiben eindeutig getrennt.
        MediaSource.SERVER -> {
            val share = item.smbShare ?: "Server"
            val rel = item.smbPath ?: ""
            val dir = rel.substringBeforeLast('/', "")
            if (dir.isEmpty()) share else "$share/$dir"
        }
    }

    private fun dayKey(ts: Long): String {
        val c = Calendar.getInstance().apply { timeInMillis = ts }
        return "%04d-%02d-%02d".format(
            c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH),
        )
    }

    private fun monthKey(ts: Long): String {
        val c = Calendar.getInstance().apply { timeInMillis = ts }
        return "%04d-%02d".format(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1)
    }
}
