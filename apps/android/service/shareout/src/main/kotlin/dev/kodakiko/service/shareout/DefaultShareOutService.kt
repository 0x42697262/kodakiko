package dev.kodakiko.service.shareout

import dev.kodakiko.contract.encodeBytes
import dev.kodakiko.crypto.STREAM_HEADER_BYTES
import dev.kodakiko.crypto.createStreamDecryptor
import dev.kodakiko.crypto.wipe
import dev.kodakiko.data.db.ObjectLocalState
import dev.kodakiko.data.db.ObjectRow
import dev.kodakiko.data.db.ObjectUploadState
import dev.kodakiko.data.db.ObjectVariant
import dev.kodakiko.platform.picker.PickerConstants
import dev.kodakiko.platform.picker.SharedFile
import dev.kodakiko.platform.picker.Sharing
import dev.kodakiko.service.sharing.ShareItem
import dev.kodakiko.service.sharing.ShareSelection
import dev.kodakiko.service.sharing.ShareTextOption
import dev.kodakiko.service.sharing.shareSelection
import dev.kodakiko.service.sharing.shareText
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

private class DecryptedOriginal(val file: File, val mimeType: String)

class DefaultShareOutService(private val deps: ShareOutDeps) : ShareOutService {
    private val open = LinkedHashSet<File>()

    private val directory: File get() = File(deps.context.cacheDir, PickerConstants.SHARE_DIRECTORY)

    override suspend fun items(entryIds: List<String>): List<SharePickItem> =
        entryIds.map { entryId -> entryId to deps.readMetadata(entryId) }.flatMap { (entryId, metadata) ->
            metadata.media.mapIndexed { position, media -> SharePickItem(entryId, media, position, metadata.note) }
        }

    override fun selection(items: List<SharePickItem>, chosen: Set<String>): ShareSelection = shareSelection(model(items), chosen)

    override suspend fun share(items: List<SharePickItem>, chosen: Set<String>, option: ShareTextOption): ShareOutResult {
        val picked = items.filter { it.media.mediaId in chosen }
        val notes = picked.map { it.note }.distinct().joinToString(ShareOutConstants.NOTE_SEPARATOR)
        val text = shareText(option, model(items), chosen, notes)
        val refs = mutableListOf<File>()
        val files = mutableListOf<SharedFile>()
        var missing = 0
        try {
            for (item in picked) {
                val original = openOriginal(item.entryId, item.media.mediaId)
                if (original == null) {
                    missing += 1
                    continue
                }
                refs += original.file
                files += SharedFile(original.file, shareFileName(item.media, item.position), original.mimeType)
            }
            if (files.isNotEmpty()) deps.launcher.launch(Sharing.chooser(deps.context, deps.authority, files, text))
        } finally {
            scheduleRelease(refs.toList())
        }
        return ShareOutResult(shared = files.size, missing = missing)
    }

    override suspend fun releaseAll() {
        val files = synchronized(open) { open.toList().also { open.clear() } }
        withContext(NonCancellable + deps.ioDispatcher) { files.forEach { it.delete() } }
    }

    private fun model(items: List<SharePickItem>): List<ShareItem> =
        items.map { ShareItem(it.media.mediaId, it.media.originalPlaintextBytes, it.media.caption) }

    private fun scheduleRelease(files: List<File>) {
        if (files.isEmpty()) return
        deps.scope.launch {
            delay(ShareOutConstants.RELEASE_DELAY_MS)
            withContext(deps.ioDispatcher) { files.forEach(::release) }
        }
    }

    private fun release(file: File) {
        val tracked = synchronized(open) { open.remove(file) }
        if (tracked) file.delete()
    }

    private suspend fun originalRow(mediaId: String): ObjectRow? =
        withContext(deps.ioDispatcher) { deps.rowStore.transaction { tx -> tx.objects.byMedia(mediaId) } }.firstOrNull { it.variant == ObjectVariant.ORIGINAL }

    private suspend fun openOriginal(entryId: String, mediaId: String): DecryptedOriginal? {
        val row = originalRow(mediaId) ?: return null
        val found = openLocal(entryId, mediaId)
        if (found != null) return found
        if (row.uploadState != ObjectUploadState.UPLOADED || row.localState == ObjectLocalState.UNAVAILABLE) return null
        deps.fetchOriginal(row.id)
        return openLocal(entryId, mediaId)
    }

    private suspend fun openLocal(entryId: String, mediaId: String): DecryptedOriginal? {
        val row = originalRow(mediaId)
        if (row == null || row.localState != ObjectLocalState.PRESENT) return null
        val descriptor = deps.readMetadata(entryId).media.firstOrNull { it.mediaId == mediaId } ?: return null
        val key = deps.entryKeys.variantKey(entryId, mediaId, ObjectVariant.ORIGINAL)
        val file = try {
            withContext(deps.ioDispatcher) { decryptInto(row, key, descriptor.mimeType) }
        } finally {
            wipe(key)
        }
        synchronized(open) { open += file }
        withContext(deps.ioDispatcher) {
            deps.rowStore.transaction { tx ->
                tx.objects.get(row.id)?.let { current -> tx.objects.put(current.copy(lastAccessAt = deps.now())) }
            }
        }
        return DecryptedOriginal(file, descriptor.mimeType)
    }

    private fun decryptInto(row: ObjectRow, key: ByteArray, mimeType: String): File {
        val header = deps.files.readRange(row.id, 0, STREAM_HEADER_BYTES)
        val decryptor = createStreamDecryptor(deps.sodium, key, header, row.plaintextSize)
        val target = newShareFile(mimeType)
        try {
            FileOutputStream(target).use { output ->
                val step = decryptor.layout.chunkCiphertextSize
                var offset = STREAM_HEADER_BYTES.toLong()
                while (offset < row.sizeBytes) {
                    val chunk = deps.files.readRange(row.id, offset, minOf(step.toLong(), row.sizeBytes - offset).toInt())
                    output.write(decryptor.decryptNext(chunk))
                    offset += step
                }
                decryptor.finish()
            }
            return target
        } catch (error: Throwable) {
            target.delete()
            throw error
        }
    }

    private fun newShareFile(mimeType: String): File {
        val directory = directory.apply { mkdirs() }
        val extension = ShareOutConstants.FILE_EXTENSIONS[mimeType] ?: ShareOutConstants.FALLBACK_EXTENSION
        val name = encodeBytes(deps.sodium.randomBytes(ShareOutConstants.FILE_ID_BYTES)) + ShareOutConstants.EXTENSION_SEPARATOR + extension
        return File(directory, name)
    }
}
