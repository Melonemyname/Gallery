package com.melone.gallery.data.smb

import coil.ImageLoader
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.request.Options
import com.melone.gallery.data.model.SmbImageModel
import kotlinx.coroutines.withContext

/**
 * Coil-Fetcher für [SmbImageModel]. Streamt die Datei über SMB in Coils Platten-Cache und
 * liefert sie von dort an den Decoder. Die Bytes laufen dabei **nie** komplett durch den
 * Arbeitsspeicher, siehe [smbSourceResult].
 */
class SmbCoilFetcher(
    private val model: SmbImageModel,
    private val options: Options,
    private val imageLoader: ImageLoader,
    private val smb: SmbManager,
) : Fetcher {

    override suspend fun fetch(): FetchResult = withContext(smb.fetchDispatcher) {
        val diskCache = imageLoader.diskCache
        val cacheKey = cacheKey(model.share, model.path)
        val mimeType = guessMimeType(model.path)

        cachedSourceResult(diskCache, cacheKey, mimeType)
            ?: smbSourceResult(
                smb = smb,
                share = model.share,
                path = model.path,
                diskCache = diskCache,
                cacheKey = cacheKey,
                mimeType = mimeType,
                context = options.context,
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
