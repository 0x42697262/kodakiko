package dev.kodakiko.data.media

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import java.io.IOException

@OptIn(UnstableApi::class)
class DecryptingDataSource(private val openReader: () -> StreamFileReader) : BaseDataSource(false) {
    private var reader: StreamFileReader? = null
    private var uri: Uri? = null
    private var position = 0L
    private var bytesRemaining = 0L
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        val current = reader ?: decrypting(openReader).also { reader = it }
        if (dataSpec.position > current.size) {
            throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        }
        position = dataSpec.position
        val available = current.size - position
        bytesRemaining = if (dataSpec.length == C.LENGTH_UNSET.toLong()) available else minOf(dataSpec.length, available)
        opened = true
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        val current = checkNotNull(reader)
        val plaintext = decrypting { current.read(position, minOf(length.toLong(), bytesRemaining).toInt()) }
        if (plaintext.isEmpty()) return C.RESULT_END_OF_INPUT
        plaintext.copyInto(buffer, offset)
        plaintext.fill(0)
        position += plaintext.size
        bytesRemaining -= plaintext.size
        bytesTransferred(plaintext.size)
        return plaintext.size
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        uri = null
        try {
            reader?.close()
        } finally {
            reader = null
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }

    private inline fun <T> decrypting(work: () -> T): T = try {
        work()
    } catch (failure: IOException) {
        throw failure
    } catch (failure: Exception) {
        throw DataSourceException(failure, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
    }

    @OptIn(UnstableApi::class)
    class Factory(private val openReader: () -> StreamFileReader) : DataSource.Factory {
        override fun createDataSource(): DataSource = DecryptingDataSource(openReader)
    }
}
