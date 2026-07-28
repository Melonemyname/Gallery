package com.melone.gallery.data.model

/** Herkunft eines Medienelements. */
enum class MediaSource { LOCAL, SERVER }

/**
 * Gemeinsames Modell für lokale (MediaStore) und Server-Medien (SMB),
 * damit die Galerie beide einheitlich anzeigen/sortieren kann.
 */
data class MediaItem(
    /** Stabiler Schlüssel: bei LOCAL die content://-URI, bei SERVER "smb://share/pfad". */
    val id: String,
    val source: MediaSource,
    val displayName: String,
    /** Voller Pfad: content-URI (lokal) bzw. "share/relativer/pfad" (Server). */
    val fullPath: String,
    val sizeBytes: Long,
    /** Aufnahme-/Erstelldatum in ms seit Epoch (EXIF bevorzugt, sonst mtime). */
    val dateTaken: Long,
    val width: Int,
    val height: Int,
    val mimeType: String,
    val isVideo: Boolean,
    /** Nur SERVER: Freigabename. */
    val smbShare: String? = null,
    /** Nur SERVER: relativer Pfad innerhalb der Freigabe. */
    val smbPath: String? = null,
    /** Nur LOCAL/Video: Dauer in ms (falls bekannt). */
    val durationMs: Long? = null,
) {
    val megapixel: Double
        get() = if (width > 0 && height > 0) (width.toLong() * height.toLong()) / 1_000_000.0 else 0.0

    /** Coil-Modell zum Laden von Vorschaubild/Vollbild. */
    val coilModel: Any
        get() = when (source) {
            MediaSource.LOCAL -> android.net.Uri.parse(id)
            MediaSource.SERVER ->
                if (isVideo) SmbVideoModel(smbShare!!, smbPath!!)
                else SmbImageModel(smbShare!!, smbPath!!)
        }
}

/** Coil-Modell für SMB-Bilder (wird vom SmbCoilFetcher aufgelöst). */
data class SmbImageModel(
    val share: String,
    val path: String,
)

/** Coil-Modell für SMB-Videos (der SmbVideoFetcher extrahiert einen Frame). */
data class SmbVideoModel(
    val share: String,
    val path: String,
)
