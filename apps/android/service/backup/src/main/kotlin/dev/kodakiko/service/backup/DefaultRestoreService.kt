package dev.kodakiko.service.backup

import dev.kodakiko.contract.EntryLocation
import dev.kodakiko.contract.LocationSource
import dev.kodakiko.contract.OptionalValue
import dev.kodakiko.crypto.wipe
import dev.kodakiko.domain.EntryTimeEdit
import dev.kodakiko.exporting.EXPORT_NOTE_FILE
import dev.kodakiko.exporting.ParsedEntryNote
import dev.kodakiko.exporting.ParsedLocation
import dev.kodakiko.exporting.RandomSource
import dev.kodakiko.exporting.ZipSourceEntry
import dev.kodakiko.exporting.copyZipEntry
import dev.kodakiko.exporting.deriveKdkxKey
import dev.kodakiko.exporting.listZip
import dev.kodakiko.exporting.openKdkx
import dev.kodakiko.exporting.parseEntryNote
import dev.kodakiko.exporting.readKdkxParams
import dev.kodakiko.exporting.readZipEntry
import dev.kodakiko.exporting.startsWithKdkx
import dev.kodakiko.service.entries.CreateEntryInput
import dev.kodakiko.service.entries.ImportOutcome
import dev.kodakiko.service.entries.ImportedMedia
import kotlinx.coroutines.withContext

private const val NOTE_SUFFIX = BackupConstants.NOTE_SUFFIX + EXPORT_NOTE_FILE

private data class DuplicateKey(val takenAtUtc: String, val mediaCount: Int)

private class Located(val source: RandomSource, val entry: ZipSourceEntry)

private fun textOf(bytes: ByteArray): String {
    val mark = BackupConstants.BYTE_ORDER_MARK
    val marked = bytes.size >= mark.size && mark.indices.all { bytes[it] == mark[it] }
    return bytes.decodeToString(if (marked) mark.size else 0, bytes.size)
}

private fun locationOf(parsed: ParsedLocation?): EntryLocation? = parsed?.let {
    EntryLocation(it.latitude, it.longitude, null, if (it.source == LocationSource.DEVICE.wire) LocationSource.DEVICE else LocationSource.EXIF)
}

class DefaultRestoreService(private val deps: RestoreServiceDeps) : RestoreService {
    override suspend fun restore(files: List<RestoreFile>, passphrase: String?, onProgress: (RestoreProgress) -> Unit): RestoreSummary {
        val existing = deps.existingEntries()
        return withContext(deps.ioDispatcher) { Run(existing, onProgress).execute(files, passphrase) }
    }

    private fun openPart(file: RestoreFile, passphrase: String?): RandomSource? {
        if (!file.source.startsWithKdkx()) return null
        if (passphrase == null || passphrase == BackupConstants.NO_PASSPHRASE) throw RestorePassphraseException(file.name)
        val key = deriveKdkxKey(deps.sodium, passphrase, readKdkxParams(file.source))
        try {
            return openKdkx(deps.sodium, key, file.source)
        } catch (_: Exception) {
            throw RestorePassphraseException(file.name)
        } finally {
            wipe(key)
        }
    }

    private inner class Run(existing: List<ExistingEntry>, private val onProgress: (RestoreProgress) -> Unit) {
        private val seen = existing.mapTo(HashSet()) { DuplicateKey(it.takenAtUtc, it.mediaCount) }
        private val failed = mutableListOf<RestoreFailure>()
        private var entries = 0
        private var media = 0
        private var duplicates = 0

        suspend fun execute(files: List<RestoreFile>, passphrase: String?): RestoreSummary {
            val decrypted = mutableListOf<RandomSource>()
            try {
                val contents = LinkedHashMap<String, Located>()
                for (file in files) {
                    val source = openPart(file, passphrase)?.also { decrypted += it } ?: file.source
                    for (entry in listZip(source)) contents[entry.name] = Located(source, entry)
                }
                val notes = contents.keys.filter { it.endsWith(NOTE_SUFFIX) }.sorted()
                for ((done, path) in notes.withIndex()) {
                    onProgress(RestoreProgress(done, notes.size))
                    restoreEntry(path, contents)
                }
                onProgress(RestoreProgress(notes.size, notes.size))
                return RestoreSummary(entries, media, duplicates, failed.toList())
            } finally {
                decrypted.forEach(RandomSource::close)
            }
        }

        private fun parse(path: String, contents: Map<String, Located>): ParsedEntryNote? =
            try {
                val located = contents.getValue(path)
                parseEntryNote(textOf(readZipEntry(located.source, located.entry)))
            } catch (_: Exception) {
                null
            }

        private suspend fun restoreEntry(path: String, contents: Map<String, Located>) {
            val parsed = parse(path, contents)
            if (parsed == null) {
                failed += RestoreFailure(path, RestoreFailureReason.UNREADABLE)
                return
            }
            val key = DuplicateKey(parsed.takenAtUtc, parsed.media.size)
            if (key in seen) {
                duplicates += 1
                return
            }
            val folder = path.substring(0, path.length - EXPORT_NOTE_FILE.length)
            val items = mutableListOf<ImportedMedia>()
            val captions = LinkedHashMap<String, String>()
            var problem: RestoreFailureReason? = null
            for ((position, item) in parsed.media.withIndex()) {
                val located = contents[folder + item.file]
                if (located == null) {
                    problem = RestoreFailureReason.MISSING_MEDIA
                    continue
                }
                val file = RestoreMediaFile(item.originalName ?: item.file, located.entry.size) { sink -> copyZipEntry(located.source, located.entry, sink) }
                when (val outcome = deps.importMedia(file, position)) {
                    is ImportOutcome.Imported -> {
                        items += outcome.media
                        item.caption?.let { captions[outcome.media.mediaId] = it }
                    }
                    is ImportOutcome.Refused -> problem = RestoreFailureReason.REFUSED
                }
            }
            if (items.isEmpty()) {
                failed += RestoreFailure(path, problem ?: RestoreFailureReason.MISSING_MEDIA)
                return
            }
            val entryId = deps.createEntry(
                CreateEntryInput(
                    note = parsed.note,
                    tags = parsed.tags,
                    time = EntryTimeEdit(parsed.takenAtUtc, parsed.utcOffsetMinutes),
                    location = OptionalValue.Present(locationOf(parsed.location)),
                ),
                items,
            )
            if (parsed.favorite) deps.setFavorite(listOf(entryId), true)
            if (captions.isNotEmpty()) deps.setCaptions(entryId, captions)
            seen += key
            entries += 1
            media += items.size
            problem?.let { failed += RestoreFailure(path, it) }
        }
    }
}
