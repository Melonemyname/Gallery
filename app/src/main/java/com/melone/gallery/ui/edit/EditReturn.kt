package com.melone.gallery.ui.edit

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.melone.gallery.GalleryApplication

/**
 * Externe Bearbeitung eines Server-Bildes: Weil der Editor (Samsung) speicherhungrig
 * ist, kann Android unsere App währenddessen beenden. Die Notiz „hier läuft eine
 * Bearbeitung" wird deshalb dauerhaft abgelegt und überlebt einen Neustart.
 */
object EditWatchStore {

    data class Entry(
        val share: String,
        val path: String,
        val displayName: String,
        /** Die von uns aufs Gerät gelegte Kopie (nicht das Ergebnis). */
        val savedId: Long,
        val startSec: Long,
    )

    private const val PREFS = "edit_watch"

    fun save(context: Context, entry: Entry) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("share", entry.share)
            .putString("path", entry.path)
            .putString("name", entry.displayName)
            .putLong("savedId", entry.savedId)
            .putLong("startSec", entry.startSec)
            .apply()
    }

    fun load(context: Context): Entry? {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val share = p.getString("share", null) ?: return null
        val path = p.getString("path", null) ?: return null
        val name = p.getString("name", null) ?: return null
        val started = p.getLong("startSec", 0L)
        // Nach 6 Stunden verfallen lassen, damit nie eine alte Notiz nachwirkt.
        if (started <= 0L || System.currentTimeMillis() / 1000 - started > 6 * 3600) {
            clear(context)
            return null
        }
        return Entry(share, path, name, p.getLong("savedId", -1L), started)
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }
}

/**
 * Speichert ein Server-Bild als echtes Foto auf dem Gerät (Bilder/Galerie). Nur so
 * bietet der Samsung-Editor seine Werkzeuge an; Dateien aus dem App-Zwischenspeicher
 * öffnet er nur in einer eingeschränkten Ansicht.
 */
fun saveServerImageToGallery(context: Context, share: String, path: String, displayName: String, mimeType: String): Uri? =
    runCatching {
        val app = context.applicationContext as GalleryApplication
        val resolver = context.contentResolver
        val values = android.content.ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, mimeType)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Galerie")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("Konnte keinen Eintrag in der Medienbibliothek anlegen")
        val f = app.container.smbManager.openFile(share, path)
        try {
            resolver.openOutputStream(uri)?.use { out -> f.inputStream.use { it.copyTo(out) } }
                ?: error("Kein Schreibzugriff")
        } finally {
            runCatching { f.close() }
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            val done = android.content.ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)
        }
        uri
    }.getOrNull()

/**
 * Sucht die vom Editor erzeugte Datei. Deckt beide Samsung-Wege ab: Original
 * überschreiben (gleicher Name, geänderte Datei) und als Kopie speichern
 * (neuer Eintrag bzw. Name mit angehängtem „(1)").
 */
fun findEditedImage(context: Context, entry: EditWatchStore.Entry): Uri? = runCatching {
    val resolver = context.contentResolver

    // 1) Neu hinzugekommenes Bild seit Beginn der Bearbeitung (ohne unsere Kopie).
    resolver.query(
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        arrayOf(MediaStore.Images.Media._ID),
        "${MediaStore.Images.Media.DATE_ADDED} >= ?",
        arrayOf(entry.startSec.toString()),
        "${MediaStore.Images.Media.DATE_ADDED} DESC",
    )?.use { c ->
        val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
        while (c.moveToNext()) {
            val id = c.getLong(idCol)
            if (id != entry.savedId) {
                return@runCatching ContentUris
                    .withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
            }
        }
    }

    // 2) Gleicher/ähnlicher Name: überschriebene Kopie oder "name(1).jpg".
    val dot = entry.displayName.lastIndexOf('.')
    val base = if (dot > 0) entry.displayName.substring(0, dot) else entry.displayName
    resolver.query(
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_MODIFIED),
        "${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?",
        arrayOf("$base%"),
        "${MediaStore.Images.Media.DATE_MODIFIED} DESC",
    )?.use { c ->
        val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
        val modCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
        while (c.moveToNext()) {
            val id = c.getLong(idCol)
            if (id != entry.savedId || c.getLong(modCol) > entry.startSec + 5) {
                return@runCatching ContentUris
                    .withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
            }
        }
    }
    null
}.getOrNull()

/**
 * Schreibt die bearbeitete Datei auf den Server: über das Original ([overwrite])
 * oder als „<name>_bearbeitet.<ext>" daneben.
 */
fun uploadEditedToServer(
    context: Context,
    entry: EditWatchStore.Entry,
    edited: Uri,
    overwrite: Boolean,
): Boolean = runCatching {
    val app = context.applicationContext as GalleryApplication
    val target = if (overwrite) {
        entry.path
    } else {
        val dot = entry.displayName.lastIndexOf('.')
        val base = if (dot > 0) entry.displayName.substring(0, dot) else entry.displayName
        val ext = if (dot > 0) entry.displayName.substring(dot) else ".jpg"
        val dir = entry.path.substringBeforeLast('/', "")
        (if (dir.isEmpty()) "" else "$dir/") + base + "_bearbeitet" + ext
    }
    context.contentResolver.openInputStream(edited)?.use { input ->
        app.container.smbManager.writeFile(entry.share, target, input)
    } ?: error("Konnte die bearbeitete Datei nicht lesen")

    // Beim Ersetzen ist das server-seitig vorgenerierte Vorschaubild veraltet: löschen,
    // sonst zeigt die App weiter die alte Fassung. Das Server-Skript legt es neu an.
    if (overwrite) {
        runCatching {
            app.container.smbManager.deleteFile(
                entry.share,
                com.melone.gallery.data.smb.SmbThumbFetcher.serverThumbPath(entry.path),
            )
        }
    }
    true
}.getOrDefault(false)
