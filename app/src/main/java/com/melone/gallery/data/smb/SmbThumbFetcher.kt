package com.melone.gallery.data.smb

import coil.ImageLoader
import coil.decode.DataSource
import coil.decode.ImageSource
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.fetch.SourceResult
import coil.request.Options
import com.melone.gallery.data.model.SmbImageModel
import com.melone.gallery.data.model.SmbThumbModel
import com.melone.gallery.data.model.SmbVideoModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.buffer
import okio.source

/**
 * Coil-Fetcher für [SmbThumbModel]: lädt bevorzugt ein server-seitig vorgeneriertes
 * Mini-JPEG unter `.thumbs/<pfad>.jpg` (ein schneller SMB-Read) und cached es in
 * Coils Disk-Cache. Existiert kein Thumbnail (neue Datei / noch nicht generiert),
 * fällt es transparent auf das bisherige Verhalten zurück: Original-Bytes
 * ([SmbCoilFetcher]) bzw. Frame-Extraktion ([SmbVideoFetcher]).
 */
class SmbThumbFetcher(
    private val model: SmbThumbModel,
    private val options: Options,
    private val imageLoader: ImageLoader,
    private val smb: SmbManager,
) : Fetcher {

    override suspend fun fetch(): FetchResult = withContext<FetchResult>(Dispatchers.IO) {
        val diskCache = imageLoader.diskCache
        val cacheKey = "smbthumb|${model.share}|${model.path}"
        val thumbPath = ".thumbs/${model.path}.jpg"

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

        // 2) Server-Thumbnail versuchen (ein SMB-Read). Fehlt es, Fallback unten.
        val bytes: ByteArray? = try {
            val file = smb.openFile(model.share, thumbPath)
            try {
                file.inputStream.source().buffer().use { it.readByteArray() }
            } finally {
                runCatching { file.close() }
            }
        } catch (t: Throwable) {
            null
        }

        if (bytes != null && bytes.isNotEmpty()) {
            // In den Disk-Cache schreiben (best effort) und ausliefern.
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
            val buffer = okio.Buffer().apply { write(bytes) }
            return@withContext SourceResult(
                source = ImageSource(source = buffer, context = options.context),
                mimeType = "image/jpeg",
                dataSource = DataSource.NETWORK,
            )
        }

        // 3) Kein Server-Thumbnail → bisheriges Verhalten (eigene Caches der Delegates).
        val delegate: Fetcher = if (model.isVideo) {
            SmbVideoFetcher(SmbVideoModel(model.share, model.path), options, imageLoader, smb)
        } else {
            SmbCoilFetcher(SmbImageModel(model.share, model.path), options, imageLoader, smb)
        }
        delegate.fetch() ?: throw java.io.IOException("Kein Vorschaubild: ${model.path}")
    }

    class Factory(private val smb: SmbManager) : Fetcher.Factory<SmbThumbModel> {
        override fun create(data: SmbThumbModel, options: Options, imageLoader: ImageLoader): Fetcher =
            SmbThumbFetcher(data, options, imageLoader, smb)
    }
}
