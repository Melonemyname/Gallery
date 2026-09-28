package com.melone.gallery.data.smb

import android.content.Context
import coil.decode.DataSource
import coil.decode.ImageSource
import coil.disk.DiskCache
import coil.fetch.SourceResult
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import okio.buffer
import okio.source
import java.io.Closeable
import java.io.File

/**
 * Gemeinsamer Unterbau der SMB-Fetcher.
 *
 * **Wichtig ist hier vor allem eines: Es wird nie eine ganze Datei in den Arbeitsspeicher
 * gelesen.** Früher holte jeder Fetcher die Datei mit `readByteArray()` am Stück. Bei einem
 * Server-Bild ohne vorgeneriertes Vorschaubild sind das schnell 5 bis 20 MB, und das Raster
 * startet für jede sichtbare Kachel einen eigenen Auftrag. Zusammen mit der Zwischenablage
 * von okio (die Daten liegen dabei doppelt vor) lief der 256-MB-Heap voll: Erst wurde das
 * Nachladen zäh, weil die Speicherbereinigung dauernd lief, dann kam der `OutOfMemoryError`.
 *
 * Jetzt wandern die Bytes direkt vom Netz auf die Platte, der Speicherbedarf ist unabhängig
 * von der Dateigröße.
 */

/** Ergebnis aus Coils Platten-Cache, oder null, wenn dort nichts liegt. */
internal fun cachedSourceResult(
    diskCache: DiskCache?,
    cacheKey: String,
    mimeType: String?,
): SourceResult? {
    val snapshot = diskCache?.openSnapshot(cacheKey) ?: return null
    return SourceResult(
        source = ImageSource(
            file = snapshot.data,
            fileSystem = diskCache.fileSystem,
            diskCacheKey = cacheKey,
            closeable = snapshot,
        ),
        mimeType = mimeType,
        dataSource = DataSource.DISK,
    )
}

/**
 * Lädt eine Datei über SMB und liefert sie als [SourceResult]. Die Daten werden **gestreamt**,
 * bevorzugt direkt in Coils Platten-Cache, sonst in eine temporäre Datei, die beim Schließen
 * wieder verschwindet.
 *
 * Wirft, wenn die Datei nicht geöffnet werden kann (beim Vorschaubild ist genau das das
 * Zeichen, dass der Server noch keines erzeugt hat).
 */
internal fun smbSourceResult(
    smb: SmbManager,
    share: String,
    path: String,
    diskCache: DiskCache?,
    cacheKey: String,
    mimeType: String?,
    context: Context,
): SourceResult {
    val smbFile = smb.openFile(share, path)
    try {
        if (diskCache != null) {
            val editor = diskCache.openEditor(cacheKey)
            if (editor != null) {
                try {
                    diskCache.fileSystem.write(editor.data) {
                        smbFile.inputStream.source().buffer().use { writeAll(it) }
                    }
                    editor.commit()
                } catch (t: Throwable) {
                    runCatching { editor.abort() }
                    throw t
                }
                cachedSourceResult(diskCache, cacheKey, mimeType)?.let {
                    return SourceResult(it.source, it.mimeType, DataSource.NETWORK)
                }
            }
        }

        // Kein Cache verfügbar (oder ein anderer Auftrag schreibt gerade denselben
        // Schlüssel): in eine temporäre Datei streamen statt in den Speicher.
        val tmp = File.createTempFile("smbfetch", null, context.cacheDir)
        FileSystem.SYSTEM.write(tmp.toOkioPath()) {
            smbFile.inputStream.source().buffer().use { writeAll(it) }
        }
        return SourceResult(
            source = ImageSource(
                file = tmp.toOkioPath(),
                fileSystem = FileSystem.SYSTEM,
                closeable = Closeable { tmp.delete() },
            ),
            mimeType = mimeType,
            dataSource = DataSource.NETWORK,
        )
    } finally {
        runCatching { smbFile.close() }
    }
}

/** Schreibt fertige Bytes (z. B. ein extrahiertes Video-Einzelbild) in den Platten-Cache. */
internal fun cacheBytes(diskCache: DiskCache?, cacheKey: String, bytes: ByteArray): Boolean {
    if (diskCache == null) return false
    val editor = diskCache.openEditor(cacheKey) ?: return false
    return try {
        diskCache.fileSystem.write(editor.data) { write(bytes) }
        editor.commit()
        true
    } catch (t: Throwable) {
        runCatching { editor.abort() }
        false
    }
}
