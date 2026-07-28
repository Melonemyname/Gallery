package com.melone.gallery.domain

import java.util.Locale

/** Formatierungen für das Info-Overlay. */
object Formatters {

    fun fileSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var i = 0
        while (value >= 1024 && i < units.lastIndex) {
            value /= 1024
            i++
        }
        return if (i == 0) "$bytes B" else String.format(Locale.GERMANY, "%.1f %s", value, units[i])
    }

    fun resolution(width: Int, height: Int): String? {
        if (width <= 0 || height <= 0) return null
        val mp = (width.toLong() * height.toLong()) / 1_000_000.0
        return String.format(Locale.GERMANY, "%d × %d (%.1f MP)", width, height, mp)
    }

    fun duration(ms: Long?): String? {
        if (ms == null || ms <= 0) return null
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) String.format(Locale.GERMANY, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.GERMANY, "%d:%02d", m, s)
    }

    fun exposureTime(seconds: Double?): String? {
        if (seconds == null || seconds <= 0) return null
        return if (seconds < 1) "1/${Math.round(1 / seconds)} s"
        else String.format(Locale.GERMANY, "%.1f s", seconds)
    }

    fun focalLength(mm: Double?): String? =
        if (mm == null || mm <= 0) null else String.format(Locale.GERMANY, "%.0f mm", mm)

    fun aperture(f: Double?): String? =
        if (f == null || f <= 0) null else String.format(Locale.GERMANY, "f/%.1f", f)

    fun iso(iso: Int?): String? = if (iso == null || iso <= 0) null else "ISO $iso"
}
