package com.melone.gallery.data.smb

import coil.ImageLoader
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.request.Options
import com.melone.gallery.data.model.SmbImageModel
import com.melone.gallery.data.model.SmbThumbModel
import com.melone.gallery.data.model.SmbVideoModel
import kotlinx.coroutines.withContext

/**
 * Coil-Fetcher für [SmbThumbModel]: lädt bevorzugt ein server-seitig vorgeneriertes
 * Mini-JPEG unter `.thumbs/<pfad>.jpg` (ein schneller SMB-Read) und cached es in
 * Coils Platten-Cache. Existiert kein Thumbnail (neue Datei / noch nicht generiert),
 * fällt es transparent auf das bisherige Verhalten zurück: Original-Bytes
 * ([SmbCoilFetcher]) bzw. Frame-Extraktion ([SmbVideoFetcher]).
 */
class SmbThumbFetcher(
    private val model: SmbThumbModel,
    private val options: Options,
    private val imageLoader: ImageLoader,
    private val smb: SmbManager,
) : Fetcher {

    override suspend fun fetch(): FetchResult = withContext<FetchResult>(smb.fetchDispatcher) {
        val diskCache = imageLoader.diskCache
        val cacheKey = cacheKey(model.share, model.path)

        // 1) Aus dem Platten-Cache bedienen, falls vorhanden.
        cachedSourceResult(diskCache, cacheKey, "image/jpeg")?.let { return@withContext it }

        // 2) Server-Thumbnail versuchen. Fehlt es, wirft openFile und wir gehen zu 3.
        runCatching {
            smbSourceResult(
                smb = smb,
                share = model.share,
                path = serverThumbPath(model.path),
                diskCache = diskCache,
                cacheKey = cacheKey,
                mimeType = "image/jpeg",
                context = options.context,
            )
        }.getOrNull()?.let { return@withContext it }

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

    companion object {
        /** Schlüssel im Coil-Disk-Cache (zum gezielten Verwerfen nach Änderungen). */
        fun cacheKey(share: String, path: String): String = "smbthumb|$share|$path"

        /** Pfad des server-seitig vorgenerierten Vorschaubilds. */
        fun serverThumbPath(path: String): String = ".thumbs/$path.jpg"
    }
}
