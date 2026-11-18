package dev.kodakiko.service.sharing

import dev.kodakiko.account.AccountKeys
import dev.kodakiko.contract.ShareDto
import dev.kodakiko.crypto.Sodium
import dev.kodakiko.service.account.RowStorePort
import dev.kodakiko.service.entries.EntryFiles
import dev.kodakiko.service.entries.EntryKeys
import dev.kodakiko.service.sync.SyncClock
import dev.kodakiko.service.sync.SyncHttp
import kotlinx.coroutines.CoroutineDispatcher

class SharingAccount(val userId: String, val keys: AccountKeys)

class Recipient(val userId: String, val username: String, val publicKey: ByteArray, val fingerprint: String)

data class AddEntriesResult(val added: List<String>, val alreadyIn: List<String>, val notSynced: List<String>, val failed: List<String>)

interface SharingService {
    suspend fun createJournal(name: String): String

    suspend fun renameJournal(id: String, name: String)

    suspend fun deleteJournal(id: String)

    suspend fun addEntries(collectionId: String, entryIds: List<String>): AddEntriesResult

    suspend fun removeEntries(collectionId: String, entryIds: List<String>)

    suspend fun lookupRecipient(username: String): Recipient

    suspend fun share(collectionId: String, recipient: Recipient)

    suspend fun listShares(collectionId: String): List<ShareDto>

    suspend fun unshare(collectionId: String, recipientUserId: String)

    suspend fun leave(collectionId: String)

    fun myFingerprint(): String
}

class SharingServiceDeps(
    val sodium: Sodium,
    val http: SyncHttp,
    val rowStore: RowStorePort,
    val files: EntryFiles,
    val entryKeys: EntryKeys,
    val account: () -> SharingAccount?,
    val sharingEnabled: () -> Boolean,
    val randomId: () -> String,
    val clock: SyncClock,
    val afterChange: (List<String>) -> Unit,
    val ioDispatcher: CoroutineDispatcher,
)
