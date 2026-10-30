package com.melone.gallery.data.transfer

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import com.melone.gallery.data.model.MediaItem
import com.melone.gallery.data.model.MediaSource
import com.melone.gallery.data.smb.SmbManager
import com.melone.gallery.data.smb.SmbNameCollisionException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream

/** Kopier-/Verschiebe-Ziel. */
sealed interface TransferTarget {
    /** Lokaler Ordner (SAF-Baum-URI, z. B. über die System-Ordnerauswahl). */
    data class LocalTree(val treeUri: Uri) : TransferTarget

    /** Server-Ordner (Freigabe + relativer Pfad). */
    data class Server(val share: String, val dir: String) : TransferTarget
}

/** Ergebnis einer erfolgreichen, geprüften Übertragung. */
data class TransferResult(
    /** Tatsächlicher Dateiname im Ziel. */
    val fileName: String,
    /** True, wenn es den Namen im Ziel schon gab und deshalb „name (1).jpg" & Co. vergeben wurde. */
    val renamed: Boolean,
)

/** Quelle und Ziel sind derselbe Ordner: Es wird nichts kopiert und nichts gelöscht. */
class SameFolderException : IOException("Die Datei liegt bereits in diesem Ordner")

/**
 * Kopiert/verschiebt Medien zwischen Gerät (SAF) und Server (SMB). Streamt die
 * Daten (kein Voll-Buffer). Verschieben = Kopieren + Quelle löschen; das Löschen
 * lokaler Quellen erledigt der Aufrufer (MediaStore-Zustimmung).
 *
 * Schutz vor Datenverlust: Eine vorhandene Zieldatei wird nie überschrieben, sondern
 * die Kopie bekommt wie bei Android/Samsung einen freien Namen („foto (1).jpg"). Ist
 * das Ziel der Ordner der Quelle, bricht [copy] mit [SameFolderException] ab. Erfolg
 * meldet [copy] erst, wenn die Zieldatei existiert und ihre Größe stimmt; erst dann
 * darf der Aufrufer beim Verschieben die Quelle löschen.
 */
class MediaTransfer(
    private val context: Context,
    private val smb: SmbManager,
) {

    /** Kopiert [item] ins Ziel. Ergebnis meldet Erfolg (mit Zielname) oder Fehler. */
    suspend fun copy(item: MediaItem, target: TransferTarget): Result<TransferResult> = withContext(Dispatchers.IO) {
        runCatching {
            if (isSameFolder(item, target)) throw SameFolderException()
            val name = FileNames.sanitize(item.displayName)
            val expected = sourceSize(item)
            when (target) {
                is TransferTarget.Server -> copyToServer(item, target, name, expected)
                is TransferTarget.LocalTree -> copyToLocal(item, target, name, expected)
            }
        }
    }

    /** Löscht die Server-Quelle (nur SERVER). Für lokale Quellen macht das der Aufrufer. */
    suspend fun deleteServerSource(item: MediaItem): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching { smb.deleteFile(item.smbShare!!, item.smbPath!!) }
    }

    /** Verschiebt die Server-Quelle in den Papierkorp (.trash, 30-Tage-Auto-Löschung). */
    suspend fun trashServerSource(item: MediaItem, trashMillis: Long): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching { smb.moveToTrash(item.smbShare!!, item.smbPath!!, trashMillis); Unit }
    }

    private fun copyToServer(item: MediaItem, target: TransferTarget.Server, name: String, expected: Long): TransferResult {
        for (n in 0..MAX_NUMBER) {
            val candidate = if (n == 0) name else FileNames.numbered(name, n)
            val path = if (target.dir.isEmpty()) candidate else "${target.dir}/$candidate"
            // Vorab prüfen spart das Öffnen der Quelle; die eigentliche Sicherung ist
            // FILE_CREATE in createNewFile (falls jemand gleichzeitig dort anlegt).
            if (smb.fileExists(target.share, path)) continue
            val written = try {
                openSource(item).use { input -> smb.createNewFile(target.share, path, input) }
            } catch (_: SmbNameCollisionException) {
                continue
            }
            val actual = runCatching { smb.fileSize(target.share, path) }.getOrDefault(-1L)
            if (actual != written || (expected >= 0 && written != expected)) {
                runCatching { smb.deleteFile(target.share, path) }
                throw incomplete(written, expected)
            }
            return TransferResult(candidate, renamed = n > 0)
        }
        throw IOException("Kein freier Dateiname im Zielordner")
    }

    private fun copyToLocal(item: MediaItem, target: TransferTarget.LocalTree, name: String, expected: Long): TransferResult {
        val dir = DocumentFile.fromTreeUri(context, target.treeUri)
            ?: error("Zielordner nicht verfügbar")
        val mime = item.mimeType.ifBlank { "application/octet-stream" }
        // Vorhandene Datei NIE wiederverwenden (früher: findFile + "wt" → Ziel gekürzt).
        val candidate = (0..MAX_NUMBER)
            .asSequence()
            .map { n -> if (n == 0) name else FileNames.numbered(name, n) }
            .firstOrNull { dir.findFile(it) == null }
            ?: throw IOException("Kein freier Dateiname im Zielordner")
        val doc = dir.createFile(mime, candidate) ?: error("Datei konnte nicht angelegt werden")
        // Der Anbieter kann den Namen selbst noch anpassen (Endung, eigener Zähler).
        val actualName = doc.name ?: candidate
        var complete = false
        try {
            val written = context.contentResolver.openOutputStream(doc.uri, "w")
                ?.use { out -> openSource(item).use { it.copyTo(out) } }
                ?: error("Ziel nicht beschreibbar")
            val actual = doc.length()
            if (actual != written || (expected >= 0 && written != expected)) throw incomplete(written, expected)
            complete = true
            return TransferResult(actualName, renamed = actualName != name)
        } finally {
            // Nur die eben angelegte, unvollständige Datei entfernen, nie etwas Vorhandenes.
            if (!complete) runCatching { doc.delete() }
        }
    }

    private fun incomplete(written: Long, expected: Long) = IOException(
        "Zieldatei unvollständig ($written von ${if (expected >= 0) expected else "?"} Bytes), Original bleibt erhalten",
    )

    /** Größe der Quelle in Bytes, -1 wenn unbekannt. */
    private fun sourceSize(item: MediaItem): Long = when (item.source) {
        MediaSource.SERVER -> runCatching { smb.fileSize(item.smbShare!!, item.smbPath!!) }.getOrDefault(-1L)
        MediaSource.LOCAL -> runCatching {
            context.contentResolver.openAssetFileDescriptor(Uri.parse(item.id), "r")?.use { it.length } ?: -1L
        }.getOrDefault(-1L)
    }

    /**
     * True, wenn das Ziel der Ordner ist, in dem die Quelle schon liegt. Server: gleiche
     * Freigabe und gleicher Ordner. Gerät: Ordner der Medienbibliothek gegen den gewählten
     * Ordner (nur beim internen Speicher sicher erkennbar; sonst greift der freie Name).
     */
    private fun isSameFolder(item: MediaItem, target: TransferTarget): Boolean = when (target) {
        is TransferTarget.Server -> item.source == MediaSource.SERVER &&
            item.smbShare.equals(target.share, ignoreCase = true) &&
            normDir(item.smbPath.orEmpty().replace('\\', '/').substringBeforeLast('/', ""))
                .equals(normDir(target.dir), ignoreCase = true)
        is TransferTarget.LocalTree -> item.source == MediaSource.LOCAL &&
            runCatching { localFolderOf(item) }.getOrNull()?.let { src ->
                treeFolderOf(target.treeUri)?.equals(src, ignoreCase = true)
            } == true
    }

    private fun normDir(dir: String) = dir.replace('\\', '/').trim().trim('/')

    /** Ordner eines lokalen Mediums relativ zum internen Speicher („DCIM/Camera"), sonst null. */
    private fun localFolderOf(item: MediaItem): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        context.contentResolver.query(
            Uri.parse(item.id),
            arrayOf(MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.VOLUME_NAME),
            null, null, null,
        )?.use { c ->
            if (!c.moveToFirst()) return null
            if (c.getString(1) != MediaStore.VOLUME_EXTERNAL_PRIMARY) return null
            return c.getString(0)?.let { normDir(it) }
        }
        return null
    }

    /** Ordner einer SAF-Baum-URI auf dem internen Speicher („primary:DCIM/Camera" → „DCIM/Camera"). */
    private fun treeFolderOf(treeUri: Uri): String? {
        if (treeUri.authority != "com.android.externalstorage.documents") return null
        val docId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull() ?: return null
        if (!docId.startsWith("primary:")) return null
        return normDir(docId.removePrefix("primary:"))
    }

    private fun openSource(item: MediaItem): InputStream = when (item.source) {
        MediaSource.LOCAL ->
            context.contentResolver.openInputStream(Uri.parse(item.id)) ?: error("Quelle nicht lesbar")
        MediaSource.SERVER -> {
            val file = smb.openFile(item.smbShare!!, item.smbPath!!)
            val ins = file.inputStream
            object : InputStream() {
                override fun read(): Int = ins.read()
                override fun read(b: ByteArray, off: Int, len: Int): Int = ins.read(b, off, len)
                override fun close() {
                    runCatching { ins.close() }
                    runCatching { file.close() }
                }
            }
        }
    }

    private companion object {
        /** Höchster Zähler für „name (n)"; darüber hinaus wird abgebrochen. */
        const val MAX_NUMBER = 999
    }
}
