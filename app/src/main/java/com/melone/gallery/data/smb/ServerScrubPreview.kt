package com.melone.gallery.data.smb

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Liefert schnelle Vorschau-Frames für ein Server-Video beim Scrubben (Zeitleiste ziehen).
 * Hält EINEN [MediaMetadataRetriever] über [SmbMediaDataSource] offen (eine SMB-Verbindung
 * für die ganze Scrub-Sitzung, nicht pro Frame neu) und holt nur den nächsten **Keyframe**
 * (`OPTION_CLOSEST_SYNC`) klein skaliert — das ist über Tailscale schnell genug für eine
 * Live-Vorschau. Zugriffe werden serialisiert ([mutex]), weil der Retriever nicht
 * threadsicher ist.
 */
class ServerScrubPreview(
    private val smb: SmbManager,
    private val share: String,
    private val path: String,
) {
    private val mutex = Mutex()
    private var retriever: MediaMetadataRetriever? = null
    private var source: SmbMediaDataSource? = null
    private var initFailed = false

    /** Kleiner, schneller Keyframe für die Scrub-Vorschau (Signatur einparametrig lassen,
     *  damit sie als `(suspend (Long) -> Bitmap?)`-Referenz nutzbar bleibt). */
    suspend fun frameAt(positionMs: Long): Bitmap? = capture(positionMs, MAX_W, MAX_H)

    /** Größerer Frame fürs Zoom-Standbild bei pausiertem Video. */
    suspend fun bigFrameAt(positionMs: Long): Bitmap? = capture(positionMs, 1920, 1080)

    private suspend fun capture(positionMs: Long, maxW: Int, maxH: Int): Bitmap? = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (initFailed) return@withLock null
            val r = retriever ?: run {
                try {
                    val src = SmbMediaDataSource(smb, share, path)
                    val rr = MediaMetadataRetriever()
                    rr.setDataSource(src)
                    source = src
                    retriever = rr
                    rr
                } catch (t: Throwable) {
                    initFailed = true
                    return@withLock null
                }
            }
            val us = positionMs.coerceAtLeast(0L) * 1000L
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                    r.getScaledFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, maxW, maxH)
                } else {
                    r.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                }
            }.getOrNull()
        }
    }

    fun release() {
        runCatching { retriever?.release() }
        runCatching { source?.close() }
        retriever = null
        source = null
    }

    private companion object {
        const val MAX_W = 320
        const val MAX_H = 180
    }
}
