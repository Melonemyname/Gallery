package com.melone.gallery.data.transfer

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.melone.gallery.data.model.MediaItem
import com.melone.gallery.data.model.MediaSource
import com.melone.gallery.data.smb.SmbManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream

/** Kopier-/Verschiebe-Ziel. */
sealed interface TransferTarget {
    /** Lokaler Ordner (SAF-Baum-URI, z. B. über die System-Ordnerauswahl). */
    data class LocalTree(val treeUri: Uri) : TransferTarget

    /** Server-Ordner (Freigabe + relativer Pfad). */
    data class Server(val share: String, val dir: String) : TransferTarget
}

/**
 * Kopiert/verschiebt Medien zwischen Gerät (SAF) und Server (SMB). Streamt die
 * Daten (kein Voll-Buffer). Verschieben = Kopieren + Quelle löschen; das Löschen
 * lokaler Quellen erledigt der Aufrufer (MediaStore-Zustimmung).
 */
class MediaTransfer(
    private val context: Context,
    private val smb: SmbManager,
) {

    /** Kopiert [item] ins Ziel. Ergebnis meldet Erfolg/Fehler. */
    suspend fun copy(item: MediaItem, target: TransferTarget): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            when (target) {
                is TransferTarget.Server -> {
                    val destPath = if (target.dir.isEmpty()) item.displayName else "${target.dir}/${item.displayName}"
                    openSource(item).use { input -> smb.writeFile(target.share, destPath, input) }
                }
                is TransferTarget.LocalTree -> {
                    val dir = DocumentFile.fromTreeUri(context, target.treeUri)
                        ?: error("Zielordner nicht verfügbar")
                    val mime = item.mimeType.ifBlank { "application/octet-stream" }
                    val existing = dir.findFile(item.displayName)
                    val doc = existing ?: dir.createFile(mime, item.displayName)
                        ?: error("Datei konnte nicht angelegt werden")
                    val out = context.contentResolver.openOutputStream(doc.uri, "wt")
                        ?: error("Ziel nicht beschreibbar")
                    out.use { o -> openSource(item).use { it.copyTo(o) } }
                }
            }
            Unit
        }
    }

    /** Löscht die Server-Quelle (nur SERVER). Für lokale Quellen macht das der Aufrufer. */
    suspend fun deleteServerSource(item: MediaItem): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching { smb.deleteFile(item.smbShare!!, item.smbPath!!) }
    }

    /** Verschiebt die Server-Quelle in den Papierkorp (.trash, 30-Tage-Auto-Löschung). */
    suspend fun trashServerSource(item: MediaItem, trashMillis: Long): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching { smb.moveToTrash(item.smbShare!!, item.smbPath!!, trashMillis) }
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
}
