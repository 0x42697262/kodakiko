package dev.kodakiko.service.backup

import dev.kodakiko.crypto.Sodium
import dev.kodakiko.service.entries.CreateEntryInput
import dev.kodakiko.service.entries.ImportOutcome
import dev.kodakiko.service.entries.ImportedMedia
import kotlinx.coroutines.CoroutineDispatcher

class RestoreFile(val name: String, val bytes: ByteArray)

class RestoreMediaFile(val bytes: ByteArray, val fileName: String)

enum class RestoreFailureReason {
    UNREADABLE,
    MISSING_MEDIA,
    REFUSED,
}

data class RestoreFailure(val path: String, val reason: RestoreFailureReason)

data class RestoreSummary(val entries: Int, val media: Int, val duplicates: Int, val failed: List<RestoreFailure>)

data class RestoreProgress(val done: Int, val total: Int)

data class ExistingEntry(val takenAtUtc: String, val mediaCount: Int)

class RestorePassphraseException(val fileName: String) : Exception(fileName)

interface RestoreService {
    suspend fun restore(files: List<RestoreFile>, passphrase: String?, onProgress: (RestoreProgress) -> Unit): RestoreSummary
}

class RestoreServiceDeps(
    val sodium: Sodium,
    val importMedia: suspend (RestoreMediaFile, Int) -> ImportOutcome,
    val createEntry: suspend (CreateEntryInput, List<ImportedMedia>) -> String,
    val setFavorite: suspend (List<String>, Boolean) -> Unit,
    val setCaptions: suspend (String, Map<String, String>) -> Unit,
    val existingEntries: () -> List<ExistingEntry>,
    val ioDispatcher: CoroutineDispatcher,
)
