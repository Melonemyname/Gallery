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
 * Vom Editor erzeugte Datei. [certain] heißt: sicher dieser Bearbeitung zugeordnet
 * (unsere abgelegte Kopie wurde überschrieben, oder genau eine neue Datei mit ihrem
 * Namen liegt im selben Ordner). Sonst ist es nur das jüngste neue Bild, und das kann
 * auch ein Screenshot oder Kamerafoto aus der Zwischenzeit sein; dann bietet der Dialog
 * nur „Als Kopie" an, damit nie ein fremdes Bild das Server-Original ersetzt.
 */
data class EditedImage(val uri: Uri, val certain: Boolean)

/**
 * Sucht die vom Editor erzeugte Datei. Deckt beide Samsung-Wege ab: Original
 * überschreiben (gleicher Eintrag, geänderte Datei) und als Kopie speichern
 * (neuer Eintrag, Name mit angehängtem „(1)"). Erst danach, als unsicherer Treffer,
 * irgendein neues Bild seit Beginn der Bearbeitung.
 */
fun findEditedImage(context: Context, entry: EditWatchStore.Entry): EditedImage? = runCatching {
    val resolver = context.contentResolver
    val images = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    fun uriOf(id: Long) = ContentUris.withAppendedId(images, id)

    // 1) Unsere Kopie selbst wurde überschrieben (Samsung „Speichern").
    // Name und Ordner unserer Kopie merken: Die Medienbibliothek kann den Namen beim
    // Ablegen selbst geändert haben, wenn es ihn dort schon gab.
    var ownName = entry.displayName
    var ownFolder: String? = null
    val folderCol = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
        MediaStore.Images.Media.RELATIVE_PATH
    } else {
        null
    }
    resolver.query(
        uriOf(entry.savedId),
        listOfNotNull(MediaStore.Images.Media.DATE_MODIFIED, MediaStore.Images.Media.DISPLAY_NAME, folderCol)
            .toTypedArray(),
        null, null, null,
    )?.use { c ->
        if (c.moveToFirst()) {
            c.getString(1)?.let { ownName = it }
            if (folderCol != null) ownFolder = c.getString(2)
            if (c.getLong(0) > entry.startSec + 5) return@runCatching EditedImage(uriOf(entry.savedId), certain = true)
        }
    }

    // 2) Neue Datei mit dem Namen unserer Kopie im selben Ordner ("name(1).jpg" o. ä.).
    val dot = ownName.lastIndexOf('.')
    val base = if (dot > 0) ownName.substring(0, dot) else ownName
    val folder = ownFolder
    if (folderCol != null && folder != null) {
        val pattern = Regex("^" + Regex.escape(base) + "(?:[ _]?\\(\\d+\\)|_\\d+)?\\.[^.]+$", RegexOption.IGNORE_CASE)
        val matches = ArrayList<Long>()
        resolver.query(
            images,
            arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME),
            "${MediaStore.Images.Media.DATE_ADDED} >= ? AND $folderCol = ? AND ${MediaStore.Images.Media._ID} != ?",
            arrayOf(entry.startSec.toString(), folder, entry.savedId.toString()),
            null,
        )?.use { c ->
            while (c.moveToNext()) {
                if (pattern.matches(c.getString(1) ?: continue)) matches += c.getLong(0)
            }
        }
        if (matches.size == 1) return@runCatching EditedImage(uriOf(matches[0]), certain = true)
    }

    // 3) Unsicher: jüngstes neues Bild seit Beginn der Bearbeitung (ohne unsere Kopie).
    resolver.query(
        images,
        arrayOf(MediaStore.Images.Media._ID),
        "${MediaStore.Images.Media.DATE_ADDED} >= ? AND ${MediaStore.Images.Media._ID} != ?",
        arrayOf(entry.startSec.toString(), entry.savedId.toString()),
        "${MediaStore.Images.Media.DATE_ADDED} DESC",
    )?.use { c ->
        if (c.moveToFirst()) return@runCatching EditedImage(uriOf(c.getLong(0)), certain = false)
    }
    null
}.getOrNull()

/**
 * Schreibt die bearbeitete Datei auf den Server: anstelle des Originals ([overwrite])
 * oder als „<name>_bearbeitet.<ext>" daneben. Überschrieben wird dabei nie etwas:
 * Beim Ersetzen wandert das Original zuerst in den Server-Papierkorb (dort
 * wiederherstellbar), bei der Kopie bekommt ein schon vorhandener Name einen Zähler.
 */
fun uploadEditedToServer(
    context: Context,
    entry: EditWatchStore.Entry,
    edited: Uri,
    overwrite: Boolean,
): Boolean = runCatching {
    val app = context.applicationContext as GalleryApplication
    val smb = app.container.smbManager
    val resolver = context.contentResolver
    fun write(path: String): Long {
        val written = resolver.openInputStream(edited)?.use { input -> smb.createNewFile(entry.share, path, input) }
            ?: error("Konnte die bearbeitete Datei nicht lesen")
        if (smb.fileSize(entry.share, path) != written) {
            runCatching { smb.deleteFile(entry.share, path) }
            error("Datei auf dem Server unvollständig")
        }
        return written
    }

    if (overwrite) {
        val trashName = smb.moveToTrash(entry.share, entry.path, System.currentTimeMillis())
        try {
            write(entry.path)
        } catch (e: Exception) {
            // Original zurückholen, damit am Ende nicht beides fehlt.
            runCatching { smb.restoreFromTrash(entry.share, trashName, entry.path) }
            throw e
        }
        // Das server-seitig vorgenerierte Vorschaubild ist veraltet: löschen, sonst zeigt
        // die App weiter die alte Fassung. Das Server-Skript legt es neu an.
        runCatching {
            smb.deleteFile(entry.share, com.melone.gallery.data.smb.SmbThumbFetcher.serverThumbPath(entry.path))
        }
    } else {
        val name = com.melone.gallery.data.transfer.FileNames.sanitize(entry.displayName)
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ".jpg"
        val dir = entry.path.substringBeforeLast('/', "")
        val copyName = base + "_bearbeitet" + ext
        val stored = (0..999).firstNotNullOfOrNull { n ->
            val candidate = if (n == 0) copyName else com.melone.gallery.data.transfer.FileNames.numbered(copyName, n)
            val path = (if (dir.isEmpty()) "" else "$dir/") + candidate
            if (smb.fileExists(entry.share, path)) return@firstNotNullOfOrNull null
            try {
                write(path)
            } catch (_: com.melone.gallery.data.smb.SmbNameCollisionException) {
                null
            }
        }
        stored ?: error("Kein freier Dateiname für die Kopie")
    }
    true
}.getOrDefault(false)
