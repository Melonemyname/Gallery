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

    /** Coil-Modell fürs Vollbild (Original in voller Auflösung). */
    val coilModel: Any
        get() = when (source) {
            MediaSource.LOCAL -> android.net.Uri.parse(id)
            MediaSource.SERVER ->
                if (isVideo) SmbVideoModel(smbShare!!, smbPath!!)
                else SmbImageModel(smbShare!!, smbPath!!)
        }

    /**
     * Coil-Modell für Vorschaubilder (Raster/Album/Liste). Bei Server-Medien
     * bevorzugt es ein server-seitig vorgeneriertes Mini-JPEG (`.thumbs/…`); erst
     * wenn keines existiert, fällt es auf Original-Laden bzw. Frame-Extraktion zurück.
     */
    val thumbModel: Any
        get() = when (source) {
            MediaSource.LOCAL -> android.net.Uri.parse(id)
            MediaSource.SERVER -> SmbThumbModel(smbShare!!, smbPath!!, isVideo)
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

/**
 * Coil-Modell für Server-Vorschaubilder: zuerst `.thumbs/<pfad>.jpg` (server-seitig
 * vorgeneriert), sonst Fallback auf Original/Frame-Extraktion (siehe SmbThumbFetcher).
 */
data class SmbThumbModel(
    val share: String,
    val path: String,
    val isVideo: Boolean,
)
