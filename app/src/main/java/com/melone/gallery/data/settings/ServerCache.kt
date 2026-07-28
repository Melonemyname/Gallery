package com.melone.gallery.data.settings

import android.content.Context
import com.melone.gallery.data.model.MediaItem
import com.melone.gallery.data.model.MediaSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Persistenter Cache der Server-Medienliste, damit die App beim Start nicht jedes Mal
 * das (langsame) SMB-Listing neu holen muss.
 */
class ServerCache(context: Context) {

    private val file = File(context.filesDir, "server_cache.json")

    suspend fun load(): List<MediaItem> = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext emptyList()
        runCatching {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                MediaItem(
                    id = o.getString("id"),
                    source = MediaSource.SERVER,
                    displayName = o.getString("name"),
                    fullPath = o.getString("path"),
                    sizeBytes = o.optLong("size"),
                    dateTaken = o.optLong("date"),
                    width = o.optInt("w"),
                    height = o.optInt("h"),
                    mimeType = o.optString("mime", "*/*"),
                    isVideo = o.optBoolean("video"),
                    smbShare = o.optString("share").ifEmpty { null },
                    smbPath = o.optString("rel").ifEmpty { null },
                    durationMs = if (o.has("dur")) o.getLong("dur") else null,
                )
            }
        }.getOrDefault(emptyList())
    }

    suspend fun save(items: List<MediaItem>) = withContext(Dispatchers.IO) {
        runCatching {
            val arr = JSONArray()
            for (m in items) {
                arr.put(
                    JSONObject().apply {
                        put("id", m.id)
                        put("name", m.displayName)
                        put("path", m.fullPath)
                        put("size", m.sizeBytes)
                        put("date", m.dateTaken)
                        put("w", m.width)
                        put("h", m.height)
                        put("mime", m.mimeType)
                        put("video", m.isVideo)
                        put("share", m.smbShare ?: "")
                        put("rel", m.smbPath ?: "")
                        m.durationMs?.let { put("dur", it) }
                    },
                )
            }
            file.writeText(arr.toString())
        }
        Unit
    }
}
