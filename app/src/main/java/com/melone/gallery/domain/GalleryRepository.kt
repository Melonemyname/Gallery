package com.melone.gallery.domain

import com.melone.gallery.data.local.MediaStoreSource
import com.melone.gallery.data.model.MediaItem
import com.melone.gallery.data.settings.ServerFolder
import com.melone.gallery.data.smb.SmbMediaSource

/**
 * Führt lokale (MediaStore) und Server-Medien (SMB) zusammen.
 * Sortierung/Gruppierung liegt in [GalleryGrouping].
 */
class GalleryRepository(
    private val local: MediaStoreSource,
    private val smb: SmbMediaSource,
) {
    suspend fun loadLocal(): List<MediaItem> = local.queryAll()

    suspend fun loadServer(folders: List<ServerFolder>): List<MediaItem> {
        if (folders.isEmpty()) return emptyList()
        return smb.query(folders)
    }
}
