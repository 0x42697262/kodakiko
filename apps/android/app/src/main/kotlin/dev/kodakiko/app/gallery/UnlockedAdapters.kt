package dev.kodakiko.app.gallery

import dev.kodakiko.service.entries.ImportOutcome
import dev.kodakiko.service.entries.ImportPipeline
import dev.kodakiko.service.entries.ImportSlots
import dev.kodakiko.service.entries.MediaInput
import dev.kodakiko.service.entries.VoiceNoteInfo
import dev.kodakiko.service.lock.UnlockedWork
import dev.kodakiko.service.shareout.ShareOutResult
import dev.kodakiko.service.shareout.ShareOutService
import dev.kodakiko.service.shareout.SharePickItem
import dev.kodakiko.service.sharing.ShareSelection
import dev.kodakiko.service.sharing.ShareTextOption

class UnlockedImportPipeline(private val delegate: ImportPipeline, private val work: UnlockedWork) : ImportPipeline {
    override suspend fun importOne(input: MediaInput, slots: ImportSlots): ImportOutcome = work.runUnlocked { delegate.importOne(input, slots) }

    override suspend fun importVoiceNote(input: MediaInput, voice: VoiceNoteInfo, slots: ImportSlots): ImportOutcome =
        work.runUnlocked { delegate.importVoiceNote(input, voice, slots) }
}

class UnlockedShareOut(private val delegate: ShareOutService, private val work: UnlockedWork) : ShareOutService {
    override suspend fun items(entryIds: List<String>): List<SharePickItem> = work.runUnlocked { delegate.items(entryIds) }

    override fun selection(items: List<SharePickItem>, chosen: Set<String>): ShareSelection = delegate.selection(items, chosen)

    override suspend fun share(items: List<SharePickItem>, chosen: Set<String>, option: ShareTextOption): ShareOutResult =
        work.runUnlocked { delegate.share(items, chosen, option) }

    override suspend fun releaseAll() = delegate.releaseAll()
}
