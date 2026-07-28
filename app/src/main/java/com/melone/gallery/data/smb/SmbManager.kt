package com.melone.gallery.data.smb

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.protocol.commons.EnumWithValue.EnumUtils
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.share.File
import com.rapid7.client.dcerpc.mssrvs.ServerService
import com.rapid7.client.dcerpc.transport.SMBTransportFactories
import java.util.EnumSet
import java.util.concurrent.TimeUnit

data class SmbCredentials(
    val host: String,
    val username: String,
    val password: String,
)

/** Ein Eintrag aus einem SMB-Verzeichnislisting. */
data class SmbEntry(
    val name: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val lastModified: Long,
)

/** Ein Eintrag im Server-Papierkorb (.trash). */
data class SmbTrashEntry(
    val share: String,
    val trashName: String,
    val origPath: String,
    val trashMillis: Long,
    val sizeBytes: Long,
)

/**
 * Verwaltet EINE SMB-Verbindung/Session und cached geöffnete Freigaben.
 * Threadsicher über ein einfaches Lock; smbj erlaubt parallele Requests auf
 * einer Session, aber wir halten Auf-/Abbau bewusst simpel.
 */
class SmbManager {

    private val lock = Any()

    @Volatile
    private var creds: SmbCredentials? = null

    private var client: SMBClient? = null
    private var connection: Connection? = null
    private var session: Session? = null
    private val shareCache = HashMap<String, DiskShare>()

    fun currentCredentials(): SmbCredentials? = creds

    /** Von den Einstellungen aufgerufen, wenn sich Serverdaten ändern. */
    fun updateCredentials(newCreds: SmbCredentials?) {
        synchronized(lock) {
            if (newCreds != creds) {
                closeInternal()
                creds = newCreds
            }
        }
    }

    /**
     * Testet nur die Anmeldung (verbindet + authentifiziert), ohne eine bestimmte
     * Freigabe zu listen. Freigaben/Ordner wählt der Nutzer danach im Ordner-Browser.
     */
    fun testAuth(test: SmbCredentials): Result<Unit> {
        return try {
            synchronized(lock) {
                // temporär mit Testdaten verbinden, ohne die laufende Verbindung zu stören
                closeInternal()
                creds = test
                ensureConnected()
                Result.success(Unit)
            }
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    private fun ensureConnected(): Session {
        val c = creds ?: throw IllegalStateException("Keine Server-Zugangsdaten gesetzt")
        var s = session
        if (s != null && connection?.isConnected == true) return s

        closeInternal()
        val config = SmbConfig.builder()
            .withTimeout(20, TimeUnit.SECONDS)
            .withSoTimeout(60, TimeUnit.SECONDS)
            .withDfsEnabled(false)
            .build()
        val cl = SMBClient(config)
        val conn = cl.connect(c.host)
        val ac = AuthenticationContext(c.username, c.password.toCharArray(), null)
        s = conn.authenticate(ac)
        client = cl
        connection = conn
        session = s
        return s
    }

    /**
     * Liefert die (ggf. gecachte) Freigabe. Nur der Verbindungsaufbau und der
     * Share-Cache laufen unter dem Lock; die eigentliche Netz-Operation macht der
     * Aufrufer danach LOCKFREI, damit z. B. Thumbnail-Reads das Auflisten nicht
     * blockieren (smbj erlaubt parallele Requests auf einer Session).
     */
    private fun shareLocked(shareName: String): DiskShare = synchronized(lock) {
        shareCache[shareName]?.let { if (it.isConnected) return@synchronized it }
        val s = ensureConnected()
        val ds = s.connectShare(shareName) as DiskShare
        shareCache[shareName] = ds
        ds
    }

    fun list(shareName: String, path: String): List<SmbEntry> {
        val ds = shareLocked(shareName)
        val smbPath = normalize(path)
        val result = ArrayList<SmbEntry>()
        for (info: FileIdBothDirectoryInformation in ds.list(smbPath)) {
            val name = info.fileName
            if (name == "." || name == "..") continue
            val attrs = info.fileAttributes
            val isDir = EnumUtils.isSet(attrs, FileAttributes.FILE_ATTRIBUTE_DIRECTORY)
            result += SmbEntry(
                name = name,
                isDirectory = isDir,
                sizeBytes = info.endOfFile,
                lastModified = runCatching { info.lastWriteTime.toDate().time }.getOrDefault(0L),
            )
        }
        return result
    }

    /**
     * Listet die Freigaben ("Platten") des Servers über MS-SRVS (NetShareEnum).
     * Admin-/versteckte Freigaben ($-Endung) und Nicht-Disk-Freigaben werden gefiltert.
     */
    fun listShares(): List<String> {
        val session = synchronized(lock) { ensureConnected() }
        val transport = SMBTransportFactories.SRVSVC.getTransport(session)
        val service = ServerService(transport)
        return service.shares1
            .asSequence()
            .filter { (it.type and 0x00000003) == 0 } // STYPE_DISKTREE (Disk-Freigabe)
            .mapNotNull { it.netName }
            .filter { it.isNotBlank() && !it.endsWith("$") }
            .distinct()
            .sortedBy { it.lowercase() }
            .toList()
    }

    /**
     * Liest das Versions-Token einer Freigabe (`.galerie-version`, vom Server-Watcher
     * bei Änderungen aktualisiert). Ein schneller SMB-Read; null, wenn die Datei fehlt
     * (kein Watcher eingerichtet) oder der Server nicht erreichbar ist.
     */
    fun readVersionToken(shareName: String): String? = runCatching {
        val f = openFile(shareName, ".galerie-version")
        try {
            val bytes = f.inputStream.use { it.readBytes() }
            String(bytes, Charsets.UTF_8).trim().takeIf { it.isNotEmpty() }
        } finally {
            runCatching { f.close() }
        }
    }.getOrNull()

    /** Öffnet eine Datei lesend. Aufrufer schließt sie über [File.close]. */
    fun openFile(shareName: String, path: String): File {
        val ds = shareLocked(shareName)
        return ds.openFile(
            normalize(path),
            EnumSet.of(AccessMask.GENERIC_READ),
            null,
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN,
            null,
        )
    }

    /** Schreibt/überschreibt eine Datei streamend aus [input]. */
    fun writeFile(shareName: String, path: String, input: java.io.InputStream) {
        val ds = shareLocked(shareName)
        val f = ds.openFile(
            normalize(path),
            EnumSet.of(AccessMask.GENERIC_WRITE),
            null,
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OVERWRITE_IF,
            null,
        )
        try {
            f.outputStream.use { out -> input.copyTo(out) }
        } finally {
            runCatching { f.close() }
        }
    }

    /** Löscht eine Datei. */
    fun deleteFile(shareName: String, path: String) {
        val ds = shareLocked(shareName)
        ds.rm(normalize(path))
    }

    /**
     * Verschiebt eine Datei server-seitig in den Papierkorb ".trash" der Freigabe
     * (schnell, kein Datentransfer). Dateiname wird mit [trashMillis] präfixiert, damit
     * [purgeTrash] das Papierkorb-Datum kennt.
     */
    fun moveToTrash(shareName: String, path: String, trashMillis: Long) {
        val ds = shareLocked(shareName)
        runCatching { ds.mkdir(".trash") }
        val rel = path.trim().trim('/')
        val encoded = android.util.Base64.encodeToString(
            rel.toByteArray(Charsets.UTF_8),
            android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING,
        )
        val f = ds.openFile(
            normalize(path),
            EnumSet.of(AccessMask.GENERIC_READ, AccessMask.DELETE),
            null,
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN,
            null,
        )
        try {
            f.rename(".trash\\${trashMillis}_$encoded", true)
        } finally {
            runCatching { f.close() }
        }
    }

    /** Listet den Papierkorb einer Freigabe (Originalpfad wird dekodiert). */
    fun listTrash(shareName: String): List<SmbTrashEntry> {
        val ds = shareLocked(shareName)
        if (!ds.folderExists(".trash")) return emptyList()
        val out = ArrayList<SmbTrashEntry>()
        for (info in ds.list(".trash")) {
            val n = info.fileName
            if (n == "." || n == "..") continue
            val ts = n.substringBefore('_').toLongOrNull() ?: continue
            val enc = n.substringAfter('_', "")
            val orig = runCatching {
                String(
                    android.util.Base64.decode(enc, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING),
                    Charsets.UTF_8,
                )
            }.getOrNull() ?: continue
            out += SmbTrashEntry(shareName, n, orig, ts, info.endOfFile)
        }
        return out
    }

    /** Stellt einen Papierkorb-Eintrag am Originalpfad wieder her. */
    fun restoreFromTrash(shareName: String, trashName: String, origPath: String) {
        val ds = shareLocked(shareName)
        val dir = origPath.trim('/').substringBeforeLast('/', "")
        if (dir.isNotEmpty()) {
            var acc = ""
            for (seg in dir.split('/')) {
                acc = if (acc.isEmpty()) seg else "$acc/$seg"
                runCatching { ds.mkdir(normalize(acc)) }
            }
        }
        val f = ds.openFile(
            ".trash\\$trashName",
            EnumSet.of(AccessMask.GENERIC_READ, AccessMask.DELETE),
            null,
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN,
            null,
        )
        try {
            f.rename(normalize(origPath), true)
        } finally {
            runCatching { f.close() }
        }
    }

    /** Löscht einen Papierkorb-Eintrag endgültig. */
    fun deleteTrashEntry(shareName: String, trashName: String) {
        val ds = shareLocked(shareName)
        ds.rm(".trash\\$trashName")
    }

    /** Löscht Papierkorb-Einträge, die älter als [cutoffMillis] sind (30-Tage-Auto-Löschung). */
    fun purgeTrash(shareName: String, cutoffMillis: Long) {
        runCatching {
            val ds = shareLocked(shareName)
            if (ds.folderExists(".trash")) {
                for (info in ds.list(".trash")) {
                    val n = info.fileName
                    if (n == "." || n == "..") continue
                    val ts = n.substringBefore('_').toLongOrNull() ?: continue
                    if (ts < cutoffMillis) runCatching { ds.rm(".trash\\$n") }
                }
            }
        }
    }

    /** True, wenn die Datei existiert. */
    fun fileExists(shareName: String, path: String): Boolean {
        val ds = shareLocked(shareName)
        return ds.fileExists(normalize(path))
    }

    /** Dateigröße in Bytes. */
    fun fileSize(shareName: String, path: String): Long {
        val f = openFile(shareName, path)
        return try {
            f.fileInformation.standardInformation.endOfFile
        } finally {
            runCatching { f.close() }
        }
    }

    private fun normalize(path: String): String =
        path.trim().trim('/').replace('/', '\\')

    private fun closeInternal() {
        shareCache.values.forEach { runCatching { it.close() } }
        shareCache.clear()
        runCatching { session?.close() }
        runCatching { connection?.close() }
        runCatching { client?.close() }
        session = null
        connection = null
        client = null
    }

    fun shutdown() = synchronized(lock) { closeInternal() }
}
