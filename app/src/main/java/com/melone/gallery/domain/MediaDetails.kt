package com.melone.gallery.domain

import com.melone.gallery.data.model.MediaItem

/** Aufbereitete Metadaten fürs Info-Overlay ("Details"). */
data class MediaDetails(
    val dateText: String?,
    val locationText: String?,
    val resolutionText: String?,
    val sizeText: String,
    val exposureText: String?,
    val isoText: String?,
    val focalText: String?,
    val apertureText: String?,
    val flashText: String?,
    val cameraModel: String?,
    val durationText: String?,
    val fileName: String,
    val filePath: String,
)

/** Baustein: die reinen Werte, bevor sie in [MediaDetails] formatiert werden. */
data class RawExif(
    val dateMillis: Long? = null,
    val latLon: Pair<Double, Double>? = null,
    val width: Int = 0,
    val height: Int = 0,
    val exposureSeconds: Double? = null,
    val iso: Int? = null,
    val focalLengthMm: Double? = null,
    val aperture: Double? = null,
    val flashFired: Boolean? = null,
    val cameraModel: String? = null,
)

fun buildDetails(item: MediaItem, exif: RawExif): MediaDetails {
    val width = if (item.width > 0) item.width else exif.width
    val height = if (item.height > 0) item.height else exif.height
    val dateMs = exif.dateMillis ?: item.dateTaken.takeIf { it > 0 }

    return MediaDetails(
        dateText = dateMs?.let { DateFormatters.fullDateTime(it) },
        locationText = exif.latLon?.let { (lat, lon) ->
            String.format(java.util.Locale.GERMANY, "%.5f, %.5f", lat, lon)
        },
        resolutionText = Formatters.resolution(width, height),
        sizeText = Formatters.fileSize(item.sizeBytes),
        exposureText = Formatters.exposureTime(exif.exposureSeconds),
        isoText = Formatters.iso(exif.iso),
        focalText = Formatters.focalLength(exif.focalLengthMm),
        apertureText = Formatters.aperture(exif.aperture),
        flashText = exif.flashFired?.let { if (it) "Blitz verwend." else "Kein Blitz" },
        cameraModel = exif.cameraModel?.takeIf { it.isNotBlank() },
        durationText = Formatters.duration(item.durationMs),
        fileName = item.displayName,
        filePath = item.fullPath,
    )
}
