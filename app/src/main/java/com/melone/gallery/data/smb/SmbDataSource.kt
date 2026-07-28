package com.melone.gallery.data.smb

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import com.hierynomus.smbj.share.File

/**
 * Media3-DataSource, die ein Server-Video per SMB streamt (seekbar).
 * URI-Form: smb://<share>/<pfad innerhalb der Freigabe>
 */
@UnstableApi
class SmbDataSource(private val smb: SmbManager) : BaseDataSource(/* isNetwork = */ true) {

    private var uri: Uri? = null
    private var file: File? = null
    private var position: Long = 0
    private var bytesRemaining: Long = 0
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        val u = dataSpec.uri
        uri = u
        val share = u.host ?: throw IllegalArgumentException("SMB-URI ohne Share: $u")
        val path = (u.path ?: "").trimStart('/')

        val f = smb.openFile(share, path)
        file = f
        val size = f.fileInformation.standardInformation.endOfFile

        position = dataSpec.position
        bytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
            dataSpec.length
        } else {
            (size - dataSpec.position).coerceAtLeast(0)
        }

        opened = true
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        val f = file ?: return C.RESULT_END_OF_INPUT

        val toRead = minOf(length.toLong(), bytesRemaining).toInt()
        val read = f.read(buffer, position, offset, toRead)
        if (read <= 0) return C.RESULT_END_OF_INPUT

        position += read
        bytesRemaining -= read
        bytesTransferred(read)
        return read
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        uri = null
        try {
            runCatching { file?.close() }
            file = null
        } finally {
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }

    class Factory(private val smb: SmbManager) : DataSource.Factory {
        @UnstableApi
        override fun createDataSource(): DataSource = SmbDataSource(smb)
    }
}
