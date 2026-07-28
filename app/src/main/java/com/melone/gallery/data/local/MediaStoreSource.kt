package com.melone.gallery.data.local

import android.content.ContentUris
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import com.melone.gallery.data.model.MediaItem
import com.melone.gallery.data.model.MediaSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Liest lokale Fotos und Videos über MediaStore (Images + Video).
 */
class MediaStoreSource(private val context: Context) {

    suspend fun queryAll(): List<MediaItem> = withContext(Dispatchers.IO) {
        val result = ArrayList<MediaItem>(512)
        result += queryImages()
        result += queryVideos()
        result
    }

    /** Elemente im System-Papierkorb (nur Android 11+). */
    suspend fun queryTrashed(): List<MediaItem> = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return@withContext emptyList()
        val out = ArrayList<MediaItem>()
        out += queryTrashedFrom(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, isVideo = false)
        out += queryTrashedFrom(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, isVideo = true)
        out
    }

    private fun queryTrashedFrom(collection: android.net.Uri, isVideo: Boolean): List<MediaItem> {
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.DATA,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.MIME_TYPE,
        )
        val items = ArrayList<MediaItem>()
        val args = Bundle().apply {
            putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_ONLY)
        }
        runCatching {
            context.contentResolver.query(collection, projection, args, null)?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val dataCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)
                val sizeCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val dateCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
                val mimeCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
                while (c.moveToNext()) {
                    val id = c.getLong(idCol)
                    val uri = ContentUris.withAppendedId(collection, id)
                    items += MediaItem(
                        id = uri.toString(),
                        source = MediaSource.LOCAL,
                        displayName = c.getString(nameCol) ?: "unbenannt",
                        fullPath = c.getString(dataCol) ?: uri.toString(),
                        sizeBytes = c.getLong(sizeCol),
                        dateTaken = c.getLong(dateCol) * 1000L,
                        width = 0,
                        height = 0,
                        mimeType = c.getString(mimeCol) ?: if (isVideo) "video/*" else "image/*",
                        isVideo = isVideo,
                    )
                }
            }
        }
        return items
    }

    private fun queryImages(): List<MediaItem> {
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATA,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_MODIFIED,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT,
            MediaStore.Images.Media.MIME_TYPE,
        )
        val items = ArrayList<MediaItem>()
        context.contentResolver.query(collection, projection, null, null, null)?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val dataCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
            val sizeCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
            val dateTakenCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
            val dateModCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
            val widthCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)
            val heightCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)
            val mimeCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)

            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                val uri = ContentUris.withAppendedId(collection, id)
                val dateTaken = c.getLong(dateTakenCol).takeIf { it > 0 }
                    ?: (c.getLong(dateModCol) * 1000L)
                items += MediaItem(
                    id = uri.toString(),
                    source = MediaSource.LOCAL,
                    displayName = c.getString(nameCol) ?: "unbenannt",
                    fullPath = c.getString(dataCol) ?: uri.toString(),
                    sizeBytes = c.getLong(sizeCol),
                    dateTaken = dateTaken,
                    width = c.getInt(widthCol),
                    height = c.getInt(heightCol),
                    mimeType = c.getString(mimeCol) ?: "image/*",
                    isVideo = false,
                )
            }
        }
        return items
    }

    private fun queryVideos(): List<MediaItem> {
        val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DATA,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DATE_TAKEN,
            MediaStore.Video.Media.DATE_MODIFIED,
            MediaStore.Video.Media.WIDTH,
            MediaStore.Video.Media.HEIGHT,
            MediaStore.Video.Media.MIME_TYPE,
            MediaStore.Video.Media.DURATION,
        )
        val items = ArrayList<MediaItem>()
        context.contentResolver.query(collection, projection, null, null, null)?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val dataCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)
            val sizeCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
            val dateTakenCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_TAKEN)
            val dateModCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_MODIFIED)
            val widthCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.WIDTH)
            val heightCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.HEIGHT)
            val mimeCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.MIME_TYPE)
            val durCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)

            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                val uri = ContentUris.withAppendedId(collection, id)
                val dateTaken = c.getLong(dateTakenCol).takeIf { it > 0 }
                    ?: (c.getLong(dateModCol) * 1000L)
                items += MediaItem(
                    id = uri.toString(),
                    source = MediaSource.LOCAL,
                    displayName = c.getString(nameCol) ?: "unbenannt",
                    fullPath = c.getString(dataCol) ?: uri.toString(),
                    sizeBytes = c.getLong(sizeCol),
                    dateTaken = dateTaken,
                    width = c.getInt(widthCol),
                    height = c.getInt(heightCol),
                    mimeType = c.getString(mimeCol) ?: "video/*",
                    isVideo = true,
                    durationMs = c.getLong(durCol),
                )
            }
        }
        return items
    }
}
