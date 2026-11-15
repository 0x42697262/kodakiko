package dev.kodakiko.service.shareout

import android.content.Context
import android.content.Intent
import dev.kodakiko.contract.EntryMetadataV1
import dev.kodakiko.contract.MediaDescriptorV1
import dev.kodakiko.crypto.Sodium
import dev.kodakiko.data.files.FileStore
import dev.kodakiko.service.account.RowStorePort
import dev.kodakiko.service.entries.EntryKeys
import dev.kodakiko.service.sharing.ShareSelection
import dev.kodakiko.service.sharing.ShareTextOption
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope

data class SharePickItem(val entryId: String, val media: MediaDescriptorV1, val position: Int, val note: String)

data class ShareOutResult(val shared: Int, val missing: Int)

fun interface ShareLauncher {
    fun launch(chooser: Intent)
}

interface OriginalFiles {
    fun readRange(objectId: String, offset: Long, length: Int): ByteArray
}

class FileStoreOriginalFiles(private val store: () -> FileStore) : OriginalFiles {
    override fun readRange(objectId: String, offset: Long, length: Int): ByteArray = store().readRange(objectId, offset, length)
}

interface ShareOutService {
    suspend fun items(entryIds: List<String>): List<SharePickItem>

    fun selection(items: List<SharePickItem>, chosen: Set<String>): ShareSelection

    suspend fun share(items: List<SharePickItem>, chosen: Set<String>, option: ShareTextOption): ShareOutResult

    suspend fun releaseAll()
}

class ShareOutDeps(
    val context: Context,
    val authority: String,
    val sodium: Sodium,
    val rowStore: RowStorePort,
    val files: OriginalFiles,
    val entryKeys: EntryKeys,
    val readMetadata: suspend (String) -> EntryMetadataV1,
    val fetchOriginal: suspend (String) -> Unit,
    val launcher: ShareLauncher,
    val now: () -> Long,
    val scope: CoroutineScope,
    val ioDispatcher: CoroutineDispatcher,
)

fun shareFileName(media: MediaDescriptorV1, position: Int): String =
    media.originalFileName ?: (
        ShareOutConstants.GENERATED_NAME_PREFIX + (position + ShareOutConstants.FIRST_NUMBER) + ShareOutConstants.EXTENSION_SEPARATOR +
            (ShareOutConstants.NAME_EXTENSIONS[media.mimeType] ?: ShareOutConstants.FALLBACK_EXTENSION)
        )
