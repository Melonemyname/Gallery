package com.melone.gallery.data.transfer

import java.io.File
import java.io.IOException

/**
 * Dateinamen aus fremder Quelle (Server-Listing, Medienbibliothek) sicher verwenden.
 *
 * Ein Name vom Server landet an mehreren Stellen als Pfadbestandteil: im App-Cache zum
 * Teilen, als Zielname beim Kopieren, als Name der bearbeiteten Kopie. Enthielte er `/`,
 * `\` oder `..`, könnte er dort aus dem vorgesehenen Ordner herausführen. Deshalb gehen
 * alle diese Stellen über [sanitize] bzw. [childFile].
 */
object FileNames {

    private const val FALLBACK = "datei"

    /** Längster erlaubter Name; die meisten Dateisysteme erlauben 255 Byte. */
    private const val MAX_LENGTH = 200

    /**
     * Reiner Dateiname ohne Ordneranteil: nur das letzte Segment, Steuerzeichen raus,
     * `.`/`..` und leere Namen werden durch [fallback] ersetzt.
     */
    fun sanitize(name: String, fallback: String = FALLBACK): String {
        val last = name.substringAfterLast('/').substringAfterLast('\\')
        val cleaned = last.filter { it >= ' ' && it != '\u007f' }.trim()
        if (cleaned.isEmpty() || cleaned.all { it == '.' }) return fallback
        if (cleaned.length <= MAX_LENGTH) return cleaned
        // Zu lang: hinten kürzen, die Endung aber behalten.
        val ext = extensionOf(cleaned).takeIf { it.length <= 16 } ?: ""
        return cleaned.substring(0, MAX_LENGTH - ext.length) + ext
    }

    /**
     * Datei [name] in [dir], nach [sanitize] und mit Gegenprobe über den kanonischen Pfad:
     * Was nicht direkt in [dir] landet, wird abgewiesen.
     */
    fun childFile(dir: File, name: String): File {
        val file = File(dir, sanitize(name))
        val base = dir.canonicalFile
        if (file.canonicalFile.parentFile != base) throw IOException("Ungültiger Dateiname: $name")
        return file
    }

    /**
     * Name mit Zähler wie bei Android/Samsung, wenn es die Datei schon gibt:
     * `foto.jpg` → `foto (1).jpg`, `foto (2).jpg`, …
     */
    fun numbered(name: String, n: Int): String {
        val ext = extensionOf(name)
        return name.dropLast(ext.length) + " ($n)" + ext
    }

    /** Endung samt Punkt (`.jpg`) oder leer; ein führender Punkt zählt nicht als Endung. */
    private fun extensionOf(name: String): String {
        val dot = name.lastIndexOf('.')
        return if (dot > 0) name.substring(dot) else ""
    }
}
