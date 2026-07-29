package com.melone.gallery.data.smb

import coil.ImageLoader
import coil.decode.DataSource
import coil.decode.ImageSource
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.fetch.SourceResult
import coil.request.Options
import com.melone.gallery.data.model.SmbImageModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.buffer
import okio.source

/**
 * Coil-Fetcher für [SmbImageModel]. Lädt die Datei über SMB, cached die
 * Original-Bytes in Coils Disk-Cache und liefert sie an den Decoder.
 */
class SmbCoilFetcher(
    private val model: SmbImageModel,
    private val options: Options,
    private val imageLoader: ImageLoader,
    private val smb: SmbManager,
) : Fetcher {

    override suspend fun fetch(): FetchResult = withContext(Dispatchers.IO) {
        val diskCache = imageLoader.diskCache
        val cacheKey = cacheKey(model.share, model.path)

        // 1) Aus Disk-Cache bedienen, falls vorhanden.
        diskCache?.openSnapshot(cacheKey)?.let { snapshot ->
            return@withContext SourceResult(
                source = ImageSource(
                    file = snapshot.data,
                    fileSystem = diskCache.fileSystem,
                    diskCacheKey = cacheKey,
                    closeable = snapshot,
                ),
                mimeType = guessMimeType(model.path),
                dataSource = DataSource.DISK,
            )
        }

        // 2) Über SMB laden.
        val file = smb.openFile(model.share, model.path)
        val bytes = try {
            file.inputStream.source().buffer().use { it.readByteArray() }
        } finally {
            runCatching { file.close() }
        }

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
                            mimeType = guessMimeType(model.path),
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
            mimeType = guessMimeType(model.path),
            dataSource = DataSource.NETWORK,
        )
    }

    private fun guessMimeType(path: String): String? = when (path.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "heic", "heif" -> "image/heif"
        "bmp" -> "image/bmp"
        else -> null
    }

    class Factory(private val smb: SmbManager) : Fetcher.Factory<SmbImageModel> {
        override fun create(data: SmbImageModel, options: Options, imageLoader: ImageLoader): Fetcher =
            SmbCoilFetcher(data, options, imageLoader, smb)
    }

    companion object {
        /**
         * Schlüssel im Coil-Disk-Cache. Wird auch als `diskCacheKey` an den
         * ImageRequest gehängt, damit die Zoom-Bibliothek (telephoto) die Datei im
         * Cache findet — sonst wirft sie "image that is missing from its disk cache".
         */
        fun cacheKey(share: String, path: String): String = "smb|$share|$path"
    }
}
