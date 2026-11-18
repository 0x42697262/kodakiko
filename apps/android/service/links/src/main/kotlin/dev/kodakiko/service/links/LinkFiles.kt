package dev.kodakiko.service.links

import dev.kodakiko.data.files.FileStore
import dev.kodakiko.data.media.CiphertextSource

interface LinkFiles {
    fun openCiphertext(objectId: String): CiphertextSource

    fun readRange(objectId: String, offset: Long, length: Int): ByteArray

    fun writeObject(objectId: String, chunks: Sequence<ByteArray>): Long

    fun delete(objectId: String)
}

class FileStoreLinkFiles(private val store: () -> FileStore) : LinkFiles {
    override fun openCiphertext(objectId: String): CiphertextSource = CiphertextSource.of(store().paths.objectFile(objectId))

    override fun readRange(objectId: String, offset: Long, length: Int): ByteArray = store().readRange(objectId, offset, length)

    override fun writeObject(objectId: String, chunks: Sequence<ByteArray>): Long = store().writeObject(objectId, chunks)

    override fun delete(objectId: String) {
        store().delete(objectId)
    }
}
