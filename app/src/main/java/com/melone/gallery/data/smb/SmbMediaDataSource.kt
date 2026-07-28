package com.melone.gallery.data.smb

import android.media.MediaDataSource
import com.hierynomus.smbj.share.File

/**
 * android.media.MediaDataSource über SMB. Erlaubt dem [android.media.MediaMetadataRetriever],
 * für die Frame-Extraktion nur die benötigten Datei-Bereiche zu lesen (kein Voll-Download).
 *
 * Der Retriever fordert typischerweise sehr viele **kleine** Bereiche an (Header, moov-Index,
 * Sample-Daten). Über das hochlatente Tailscale-Netz wäre je Mini-Read ein eigener SMB-Roundtrip
 * viel zu teuer. Deshalb liest diese Quelle in **ausgerichteten Blöcken** ([BLOCK_SIZE]) und hält
 * die zuletzt benutzten Blöcke in einem kleinen LRU-Cache. So werden aus tausenden Mini-Reads nur
 * wenige größere SMB-Reads.
 *
 * smbj-Reads sind zustandslos in Bezug auf die Position (Offset wird je Read übergeben), daher
 * genügt eine offene [File] für wahlfreien Zugriff. Eine Instanz wird nur von einem Thread
 * benutzt (dem Retriever-Thread), daher ist keine Synchronisation nötig.
 */
class SmbMediaDataSource(
    smb: SmbManager,
    share: String,
    path: String,
) : MediaDataSource() {

    private val file: File = smb.openFile(share, path)
    private val size: Long = file.fileInformation.standardInformation.endOfFile

    /** LRU-Cache ausgerichteter Blöcke (Schlüssel = Blockanfang). accessOrder=true → ältester zuerst. */
    private val blocks = object : LinkedHashMap<Long, ByteArray>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ByteArray>): Boolean =
            size > MAX_BLOCKS
    }

    override fun getSize(): Long = size

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (position >= this.size) return -1
        val want = minOf(size.toLong(), this.size - position).toInt()
        if (want <= 0) return 0

        var copied = 0
        var pos = position
        while (copied < want) {
            val blockStart = (pos / BLOCK_SIZE) * BLOCK_SIZE
            val block = blockAt(blockStart) ?: break
            val within = (pos - blockStart).toInt()
            if (within >= block.size) break
            val n = minOf(block.size - within, want - copied)
            System.arraycopy(block, within, buffer, offset + copied, n)
            copied += n
            pos += n
        }
        return if (copied == 0) -1 else copied
    }

    private fun blockAt(blockStart: Long): ByteArray? {
        blocks[blockStart]?.let { return it }
        val len = minOf(BLOCK_SIZE.toLong(), size - blockStart).toInt()
        if (len <= 0) return null
        val buf = ByteArray(len)
        var read = 0
        while (read < len) {
            val r = file.read(buf, blockStart + read, read, len - read)
            if (r <= 0) break
            read += r
        }
        if (read <= 0) return null
        val block = if (read == len) buf else buf.copyOf(read)
        blocks[blockStart] = block
        return block
    }

    override fun close() {
        blocks.clear()
        runCatching { file.close() }
    }

    private companion object {
        const val BLOCK_SIZE = 256 * 1024       // 256 KB pro SMB-Read
        const val MAX_BLOCKS = 16               // ~4 MB Cache je Video-Extraktion
    }
}
