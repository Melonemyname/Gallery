package com.melone.gallery.data.smb

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build
import coil.ImageLoader
import coil.decode.DataSource
import coil.decode.ImageSource
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.fetch.SourceResult
import coil.request.Options
import com.melone.gallery.data.model.SmbVideoModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * Coil-Fetcher für [SmbVideoModel]: extrahiert per [MediaMetadataRetriever] (über
 * [SmbMediaDataSource], also ohne Voll-Download) einen Frame, verkleinert ihn und
 * cached das Ergebnis als JPEG in Coils Disk-Cache. Beim nächsten Mal kommt das
 * Thumbnail direkt aus dem Cache.
 */
class SmbVideoFetcher(
    private val model: SmbVideoModel,
    private val options: Options,
    private val imageLoader: ImageLoader,
    private val smb: SmbManager,
) : Fetcher {

    override suspend fun fetch(): FetchResult = withContext(Dispatchers.IO) {
        val diskCache = imageLoader.diskCache
        val cacheKey = "smbvideo|${model.share}|${model.path}"

        // 1) Aus Disk-Cache bedienen, falls vorhanden.
        diskCache?.openSnapshot(cacheKey)?.let { snapshot ->
            return@withContext SourceResult(
                source = ImageSource(
                    file = snapshot.data,
                    fileSystem = diskCache.fileSystem,
                    diskCacheKey = cacheKey,
                    closeable = snapshot,
                ),
                mimeType = "image/jpeg",
                dataSource = DataSource.DISK,
            )
        }

        // 2) Frame über SMB extrahieren.
        val bytes = extractFrameJpeg()
            ?: throw IOException("Konnte kein Video-Vorschaubild erzeugen: ${model.path}")

        // 3) In den Disk-Cache schreiben (best effort).
        if (diskCache != null) {
            val editor = diskCache.openEditor(cacheKey)
            if (editor != null) {
                try {
                    diskCache.fileSystem.write(editor.data) { write(bytes) }
                    editor.commit()
                    diskCache.openSnapshot(cacheKey)?.let { snapshot ->
                        return@withContext SourceResult(
                            source = ImageSource(
                                file = snapshot.data,
                                fileSystem = diskCache.fileSystem,
                                diskCacheKey = cacheKey,
                                closeable = snapshot,
                            ),
                            mimeType = "image/jpeg",
                            dataSource = DataSource.NETWORK,
                        )
                    }
                } catch (t: Throwable) {
                    runCatching { editor.abort() }
                }
            }
        }

        // 4) Fallback: direkt aus den Bytes.
        val buffer = okio.Buffer().apply { write(bytes) }
        SourceResult(
            source = ImageSource(source = buffer, context = options.context),
            mimeType = "image/jpeg",
            dataSource = DataSource.NETWORK,
        )
    }

    private fun extractFrameJpeg(): ByteArray? {
        val retriever = MediaMetadataRetriever()
        val dataSource = SmbMediaDataSource(smb, model.share, model.path)
        val frame: Bitmap? = try {
            retriever.setDataSource(dataSource)
            // Ab API 27 direkt skaliert dekodieren (spart Zeit/Speicher bei großen Videos).
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                retriever.getScaledFrameAtTime(
                    FRAME_US, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, MAX_EDGE_PX, MAX_EDGE_PX,
                ) ?: retriever.getScaledFrameAtTime(
                    0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, MAX_EDGE_PX, MAX_EDGE_PX,
                )
            } else {
                retriever.getFrameAtTime(FRAME_US, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?: retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            }
        } catch (t: Throwable) {
            null
        } finally {
            runCatching { retriever.release() }
            runCatching { dataSource.close() }
        }

        val bitmap = frame ?: return null
        val scaled = downscale(bitmap, MAX_EDGE_PX)
        return try {
            ByteArrayOutputStream().use { bos ->
                scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, bos)
                bos.toByteArray()
            }
        } finally {
            if (scaled !== bitmap) scaled.recycle()
            bitmap.recycle()
        }
    }

    private fun downscale(src: Bitmap, maxEdge: Int): Bitmap {
        val longest = maxOf(src.width, src.height)
        if (longest <= maxEdge || longest == 0) return src
        val factor = maxEdge.toFloat() / longest
        val w = (src.width * factor).toInt().coerceAtLeast(1)
        val h = (src.height * factor).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, w, h, true)
    }

    class Factory(private val smb: SmbManager) : Fetcher.Factory<SmbVideoModel> {
        override fun create(data: SmbVideoModel, options: Options, imageLoader: ImageLoader): Fetcher =
            SmbVideoFetcher(data, options, imageLoader, smb)
    }

    private companion object {
        const val FRAME_US = 1_000_000L // 1 Sekunde
        const val MAX_EDGE_PX = 1080
        const val JPEG_QUALITY = 85
    }
}
