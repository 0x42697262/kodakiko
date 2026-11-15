package dev.kodakiko.service.links

import dev.kodakiko.account.AccountKeys
import dev.kodakiko.contract.EntryMetadataV1
import dev.kodakiko.contract.LinkExpiry
import dev.kodakiko.crypto.Sodium
import dev.kodakiko.service.account.RowStorePort
import dev.kodakiko.service.entries.EntryKeys
import dev.kodakiko.service.sync.SyncClock
import dev.kodakiko.service.sync.SyncHttp
import dev.kodakiko.service.sync.Uploader
import kotlinx.coroutines.CoroutineDispatcher

data class CreateLinkInput(
    val entryIds: List<String>,
    val mediaIds: Set<String>,
    val title: String,
    val expiry: LinkExpiry,
    val includeNotes: Boolean,
)

enum class CreateLinkStep {
    PREPARING,
    UPLOADING,
    REGISTERING,
}

data class CreateLinkProgress(val step: CreateLinkStep, val done: Int, val total: Int, val bytesDone: Long, val bytesTotal: Long)

data class CreateLinkResult(
    val url: String,
    val linkId: String,
    val skippedEntries: Int,
    val skippedUndecodable: Int,
    val skippedUnsupportedVideo: Int,
)

data class OwnerLink(
    val linkId: String,
    val url: String,
    val title: String,
    val expiresAt: String?,
    val maxViews: Long?,
    val viewCount: Long,
    val createdAt: String,
)

interface PublicLinksService {
    suspend fun create(input: CreateLinkInput, onProgress: (CreateLinkProgress) -> Unit): CreateLinkResult

    suspend fun list(): List<OwnerLink>

    suspend fun revoke(linkId: String)
}

class PublicLinksServiceDeps(
    val http: SyncHttp,
    val sodium: Sodium,
    val account: () -> AccountKeys?,
    val readMetadata: suspend (String) -> EntryMetadataV1,
    val entryKeys: EntryKeys,
    val rowStore: RowStorePort,
    val files: LinkFiles,
    val fetchObject: suspend (String) -> Unit,
    val stripper: LinkStripper,
    val uploader: Uploader,
    val baseUrl: suspend () -> String,
    val randomId: () -> String,
    val clock: SyncClock,
    val ioDispatcher: CoroutineDispatcher,
)
