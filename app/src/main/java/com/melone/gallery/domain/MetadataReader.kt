package com.melone.gallery.domain

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.melone.gallery.data.model.MediaItem
import com.melone.gallery.data.model.MediaSource
import com.melone.gallery.data.smb.SmbManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Liest EXIF/Metadaten für das Info-Overlay.
 * Server: nur der Anfang der Datei wird über SMB gelesen (EXIF-Header), kein voller Download.
 */
class MetadataReader(
    private val context: Context,
    private val smb: SmbManager,
) {
    private val exifDateFormat = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US)

    suspend fun read(item: MediaItem): MediaDetails = withContext(Dispatchers.IO) {
        val exif = when (item.source) {
            MediaSource.LOCAL -> readLocal(item)
            MediaSource.SERVER -> if (item.isVideo) RawExif() else readServer(item)
        }
        buildDetails(item, exif)
    }

    private fun readLocal(item: MediaItem): RawExif {
        if (item.isVideo) return RawExif()
        return runCatching {
            context.contentResolver.openInputStream(Uri.parse(item.id))?.use { input ->
                parseExif(ExifInterface(input))
            } ?: RawExif()
        }.getOrDefault(RawExif())
    }

    private fun readServer(item: MediaItem): RawExif {
        val share = item.smbShare ?: return RawExif()
        val path = item.smbPath ?: return RawExif()
        return runCatching {
            val bytes = readHead(share, path, HEAD_BYTES)
            val exif = ExifInterface(ByteArrayInputStream(bytes))
            var raw = parseExif(exif)
            if (raw.width == 0 || raw.height == 0) {
                // Fallback: Maße aus dem Header dekodieren.
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                if (opts.outWidth > 0 && opts.outHeight > 0) {
                    raw = raw.copy(width = opts.outWidth, height = opts.outHeight)
                }
            }
            raw
        }.getOrDefault(RawExif())
    }

    private fun readHead(share: String, path: String, max: Int): ByteArray {
        val file = smb.openFile(share, path)
        return try {
            val buffer = ByteArray(max)
            var total = 0
            while (total < max) {
                val read = file.read(buffer, total.toLong(), total, max - total)
                if (read <= 0) break
                total += read
            }
            if (total == max) buffer else buffer.copyOf(total)
        } finally {
            runCatching { file.close() }
        }
    }

    private fun parseExif(exif: ExifInterface): RawExif {
        val dateMillis = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
            ?.let { runCatching { exifDateFormat.parse(it)?.time }.getOrNull() }

        val latLon = FloatArray(2).let { arr ->
            if (exif.getLatLong(arr)) arr[0].toDouble() to arr[1].toDouble() else null
        }

        val width = exif.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0)
            .takeIf { it > 0 } ?: exif.getAttributeInt(ExifInterface.TAG_PIXEL_X_DIMENSION, 0)
        val height = exif.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0)
            .takeIf { it > 0 } ?: exif.getAttributeInt(ExifInterface.TAG_PIXEL_Y_DIMENSION, 0)

        val exposure = exif.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, 0.0).takeIf { it > 0 }
        val iso = exif.getAttributeInt(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, 0)
            .takeIf { it > 0 } ?: exif.getAttributeInt(ExifInterface.TAG_ISO_SPEED_RATINGS, 0).takeIf { it > 0 }
        val focal = exif.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH, 0.0).takeIf { it > 0 }
        val aperture = exif.getAttributeDouble(ExifInterface.TAG_F_NUMBER, 0.0).takeIf { it > 0 }
        val flash = exif.getAttribute(ExifInterface.TAG_FLASH)?.toIntOrNull()?.let { (it and 0x1) == 1 }
        val model = listOfNotNull(
            exif.getAttribute(ExifInterface.TAG_MAKE)?.trim()?.takeIf { it.isNotBlank() },
            exif.getAttribute(ExifInterface.TAG_MODEL)?.trim()?.takeIf { it.isNotBlank() },
        ).joinToString(" ").takeIf { it.isNotBlank() }

        return RawExif(
            dateMillis = dateMillis,
            latLon = latLon,
            width = width,
            height = height,
            exposureSeconds = exposure,
            iso = iso,
            focalLengthMm = focal,
            aperture = aperture,
            flashFired = flash,
            cameraModel = model,
        )
    }

    private companion object {
        const val HEAD_BYTES = 512 * 1024 // 512 KB reichen für EXIF + Bild-Header
    }
}
