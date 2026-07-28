package com.melone.gallery.data.smb

import com.melone.gallery.data.model.MediaItem
import com.melone.gallery.data.model.MediaSource
import com.melone.gallery.data.settings.ServerFolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Listet Medien aus den konfigurierten Server-Ordnern (rekursiv, mit Exclude).
 * Maße/EXIF werden hier bewusst NICHT gelesen (zu teuer pro Datei) — das macht
 * bei Bedarf der Viewer/das Info-Overlay lazy.
 */
class SmbMediaSource(private val smb: SmbManager) {

    private val imageExts = setOf("jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "bmp")
    private val videoExts = setOf("mp4", "mkv", "mov", "avi", "webm", "3gp", "m4v", "ts")

    suspend fun query(folders: List<ServerFolder>): List<MediaItem> = withContext(Dispatchers.IO) {
        // Papierkorb je Freigabe von >30 Tage alten Einträgen bereinigen.
        val cutoff = System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000
        folders.map { it.share }.distinct().forEach { runCatching { smb.purgeTrash(it, cutoff) } }

        val out = ArrayList<MediaItem>()
        for (folder in folders) {
            runCatching { walk(folder.share, folder.rootPath, folder.excludes, folder.recursive, out, 0) }
        }
        out
    }

    private fun walk(
        share: String,
        path: String,
        excludes: List<String>,
        recursive: Boolean,
        out: MutableList<MediaItem>,
        depth: Int,
    ) {
        if (depth > 30) return
        val entries = smb.list(share, path)
        for (e in entries) {
            val rel = if (path.isEmpty()) e.name else "$path/${e.name}"
            if (excludes.any { it.isNotEmpty() && (rel == it || rel.startsWith("$it/")) }) continue

            if (e.isDirectory) {
                if (e.name == ".trash") continue // Papierkorb nicht anzeigen
                if (recursive) walk(share, rel, excludes, recursive, out, depth + 1)
                continue
            }

            val ext = e.name.substringAfterLast('.', "").lowercase()
            val isVideo = ext in videoExts
            val isImage = ext in imageExts
            if (!isVideo && !isImage) continue

            out += MediaItem(
                id = "smb://$share/$rel",
                source = MediaSource.SERVER,
                displayName = e.name,
                fullPath = "$share/$rel",
                sizeBytes = e.sizeBytes,
                dateTaken = e.lastModified,
                width = 0,
                height = 0,
                mimeType = mimeFor(ext, isVideo),
                isVideo = isVideo,
                smbShare = share,
                smbPath = rel,
            )
        }
    }

    private fun mimeFor(ext: String, isVideo: Boolean): String = when (ext) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "heic", "heif" -> "image/heif"
        "bmp" -> "image/bmp"
        "mp4", "m4v" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "mov" -> "video/quicktime"
        "webm" -> "video/webm"
        "3gp" -> "video/3gpp"
        else -> if (isVideo) "video/*" else "image/*"
    }
}
