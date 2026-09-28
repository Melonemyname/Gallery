package com.melone.gallery.data.thumb

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import com.melone.gallery.data.smb.SmbManager
import com.melone.gallery.data.smb.SmbMediaDataSource
import com.melone.gallery.data.smb.SmbThumbFetcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * Setzt eigene Server-Vorschaubilder unter `.thumbs/<pfad>.jpg`. Dieser Ordner ist in
 * Syncthing ignoriert, die Cover werden also **nicht synchronisiert** und liegen nur auf
 * dem Server (nur die App liest sie). Quelle ist entweder ein Frame aus dem Video (an der
 * aktuellen Position) oder ein vom Nutzer gewähltes Bild.
 */
object CustomThumbnail {

    private const val MAX_EDGE_PX = 512
    private const val JPEG_QUALITY = 88

    /**
     * Extrahiert bei einem Server-Video einen Frame an [positionMs] (über SMB, ohne
     * Voll-Download) und gibt ihn als 512px-JPEG zurück. null bei Fehler.
     */
    suspend fun captureServerVideoFrame(
        smb: SmbManager,
        share: String,
        path: String,
        positionMs: Long,
    ): ByteArray? = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        val source = SmbMediaDataSource(smb, share, path)
        val frame: Bitmap? = try {
            retriever.setDataSource(source)
            val us = positionMs.coerceAtLeast(0L) * 1000L
            // OPTION_CLOSEST liefert GENAU das Bild an der Position (nicht den nächsten
            // Keyframe), damit das Cover dem entspricht, wo das Video gerade steht.
            // Fallback auf den nächsten Keyframe, falls das exakte Bild nicht geht.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                retriever.getScaledFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST, MAX_EDGE_PX, MAX_EDGE_PX)
                    ?: retriever.getScaledFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, MAX_EDGE_PX, MAX_EDGE_PX)
            } else {
                retriever.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST)
                    ?: retriever.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            }
        } catch (t: Throwable) {
            null
        } finally {
            runCatching { retriever.release() }
            runCatching { source.close() }
        }
        frame?.let { encodeJpeg(it) }
    }

    /** Lädt ein gewähltes Gerät-Bild herunterskaliert und gibt es als 512px-JPEG zurück. */
    suspend fun imageToThumbJpeg(context: Context, uri: Uri): ByteArray? =
        withContext(Dispatchers.IO) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            runCatching {
                context.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, bounds)
                }
            }
            val longest = maxOf(bounds.outWidth, bounds.outHeight)
            var sample = 1
            while (longest > 0 && longest / sample > MAX_EDGE_PX * 2) sample *= 2
            val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
            val bmp = runCatching {
                context.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, decodeOpts)
                }
            }.getOrNull() ?: return@withContext null
            encodeJpeg(bmp)
        }

    /**
     * Schreibt [jpeg] als Server-Vorschaubild (`.thumbs/<path>.jpg`) und verwirft den
     * Coil-Cache, damit die App sofort das neue Cover zeigt. true bei Erfolg.
     */
    suspend fun setServerThumbnail(
        context: Context,
        smb: SmbManager,
        share: String,
        path: String,
        jpeg: ByteArray,
    ): Boolean = withContext(Dispatchers.IO) {
        val ok = runCatching {
            smb.writeFileMkdirs(share, SmbThumbFetcher.serverThumbPath(path), jpeg)
        }.isSuccess
        if (ok) {
            runCatching {
                val loader = coil.Coil.imageLoader(context)
                loader.diskCache?.remove(SmbThumbFetcher.cacheKey(share, path))
                loader.memoryCache?.clear()
            }
        }
        ok
    }

    private fun encodeJpeg(src: Bitmap): ByteArray? {
        val scaled = downscale(src, MAX_EDGE_PX)
        return try {
            ByteArrayOutputStream().use { bos ->
                scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, bos)
                bos.toByteArray()
            }.takeIf { it.isNotEmpty() }
        } catch (t: Throwable) {
            null
        } finally {
            if (scaled !== src) scaled.recycle()
            src.recycle()
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
}
