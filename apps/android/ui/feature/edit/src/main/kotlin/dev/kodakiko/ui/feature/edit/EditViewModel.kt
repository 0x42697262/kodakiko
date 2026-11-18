package dev.kodakiko.ui.feature.edit

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.kodakiko.contract.CAPTION_MAX_CHARS
import dev.kodakiko.contract.EntryMetadataV1
import dev.kodakiko.contract.MediaDescriptorV1
import dev.kodakiko.contract.MediaKind
import dev.kodakiko.contract.TAGS_PER_ENTRY_MAX
import dev.kodakiko.domain.EntryEdit
import dev.kodakiko.domain.EntryTimeEdit
import dev.kodakiko.domain.localDateOf
import dev.kodakiko.domain.localPartsToUtc
import dev.kodakiko.domain.localTimeOf
import dev.kodakiko.index.orderTags
import dev.kodakiko.js.JsDate
import dev.kodakiko.js.jsonStringifyNumber
import dev.kodakiko.markdown.TextSelection
import dev.kodakiko.markdown.applyMarkdownAction
import dev.kodakiko.markdown.markdownPlainText
import dev.kodakiko.service.entries.EntriesConstants
import dev.kodakiko.service.entries.ImportOutcome
import dev.kodakiko.service.entries.ImportSlots
import dev.kodakiko.service.entries.MediaInput
import dev.kodakiko.service.entries.VoiceNoteInfo
import dev.kodakiko.ui.core.format.formatClock
import dev.kodakiko.ui.core.navigation.CaptureMode
import dev.kodakiko.ui.core.navigation.Route
import dev.kodakiko.ui.core.notice.GalleryNotice
import dev.kodakiko.ui.core.tags.toggleTagChoice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private data class EditUi(
    val metadata: EntryMetadataV1? = null,
    val draft: EditDraft? = null,
    val favorite: Boolean = false,
    val imported: Set<String> = emptySet(),
    val page: Int = 0,
    val sheet: EditSheet? = null,
    val noteEditor: NoteEditorUi? = null,
    val timeEditor: TimeEditorUi? = null,
    val recording: RecordingUi? = null,
    val canPaste: Boolean = false,
    val failed: Boolean = false,
    val voiceFailed: Boolean = false,
)

class EditViewModel(private val deps: EditDeps, private val entryId: String, private val isNew: Boolean) : ViewModel() {
    private val eventChannel = Channel<EditEvent>(Channel.UNLIMITED)
    private val ui = MutableStateFlow(EditUi())
    private val work = Mutex()
    private val unregister = deps.flushes.register(::saveForLock)
    private var ticker: Job? = null
    private var leaving = false

    @Volatile
    private var cropping: String? = null

    val events: Flow<EditEvent> = eventChannel.receiveAsFlow()

    val state: StateFlow<EditState> = combine(ui, deps.voice.playing) { current, playing -> build(current, playing) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(EditConstants.STATE_SUBSCRIPTION_MS), build(ui.value, deps.voice.playing.value))

    init {
        viewModelScope.launch {
            reload()
            checkClipboard()
        }
    }

    fun onAction(action: EditAction) {
        when (action) {
            EditAction.Back -> requestExit(EditExit.BACK)
            EditAction.Done -> done()
            EditAction.ViewEntry -> requestExit(EditExit.VIEW)
            EditAction.SaveChanges -> saveAndExit()
            EditAction.DiscardChanges -> discard()
            EditAction.KeepDeviceOriginals -> keepDeviceOriginals()
            EditAction.DeleteDeviceOriginals -> deleteDeviceOriginals()
            EditAction.Trash -> trash()
            is EditAction.PageChanged -> ui.update { it.copy(page = action.page) }
            is EditAction.ScreenVisible -> screenVisible(action.visible)
            is EditAction.OpenMediaMenu -> openMediaMenu(action.mediaId)
            EditAction.DismissSheet -> dismissSheet()
            is EditAction.EditCaption -> ui.update { it.copy(sheet = EditSheet.Caption(action.mediaId, it.draft?.captions?.get(action.mediaId).orEmpty())) }
            is EditAction.SaveCaption -> saveCaption(action.mediaId, action.caption)
            is EditAction.SetCover -> setCover(action.mediaId)
            is EditAction.ShareItem -> closeSheetThen { deps.sharing.shareItem(entryId, action.mediaId) }
            is EditAction.SaveToDevice -> closeSheetThen { deps.sharing.saveToDevice(entryId, action.mediaId) }
            is EditAction.Crop -> crop(action.mediaId)
            is EditAction.AskRemove -> askRemove(action.mediaId)
            is EditAction.Remove -> removeItem(action.mediaId, resetPage = true)
            is EditAction.OpenPlayer -> send(EditEvent.Open(Route.Play(entryId, action.mediaId)))
            is EditAction.Capture -> openCapture(action.mode)
            EditAction.AddFromDevice -> pickMedia()
            is EditAction.Picked -> picked(action.uris)
            EditAction.ToggleFavorite -> toggleFavorite()
            EditAction.Paste -> paste()
            EditAction.ToggleRecording -> if (ui.value.recording == null) startRecording() else stopRecording()
            EditAction.StopRecording -> stopRecording()
            EditAction.Share -> send(EditEvent.Share(listOf(entryId), sharingAvailable()))
            is EditAction.ToggleVoice -> viewModelScope.launch { attempt { deps.voice.toggle(entryId, action.mediaId) } }
            is EditAction.RemoveVoice -> removeItem(action.mediaId, resetPage = false)
            EditAction.ToggleTimeEditor -> toggleTimeEditor()
            is EditAction.ApplyTime -> applyTime(action.date, action.time)
            EditAction.ResetTime -> resetTime()
            EditAction.OpenMap -> ui.value.draft?.location?.let { send(EditEvent.OpenUri(mapUri(it.latitude, it.longitude))) }
            EditAction.RemoveLocation -> change { it.copy(location = null) }
            EditAction.ChooseTags -> ui.update { it.copy(sheet = EditSheet.ChooseTags(tagChoices())) }
            is EditAction.ToggleTag -> toggleTag(action.tag)
            is EditAction.RemoveTag -> change { draft -> draft.copy(tags = draft.tags.filter { it != action.tag }) }
            EditAction.EditTags -> {
                ui.update { it.copy(sheet = null) }
                send(EditEvent.Open(Route.Tags))
            }
            EditAction.EditNote -> editNote()
            is EditAction.NoteChanged -> noteChanged(action.text, action.selection)
            is EditAction.Toolbar -> toolbar(action)
            EditAction.NoteDone -> ui.update { it.copy(noteEditor = null) }
            is EditAction.NoteMode -> ui.update { current -> current.copy(noteEditor = current.noteEditor?.copy(preview = action.preview)) }
            EditAction.CopyNote -> copyNote()
            is EditAction.OpenLink -> send(EditEvent.OpenUri(action.url))
        }
    }

    override fun onCleared() {
        unregister()
        ticker?.cancel()
        deps.voice.stop()
        super.onCleared()
    }

    private fun send(event: EditEvent) {
        eventChannel.trySend(event)
    }

    private suspend fun <T> attempt(work: suspend () -> T): T? = try {
        work()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    private fun closeSheetThen(task: suspend () -> Unit) {
        ui.update { it.copy(sheet = null) }
        viewModelScope.launch { attempt(task) }
    }

    private suspend fun write(draft: EditDraft, favorite: Boolean): EntryMetadataV1 {
        val current = deps.entries.readMetadata(entryId)
        val diff = diffDraft(current, draft)
        for (mediaId in diff.removedMediaIds) deps.entries.removeMedia(entryId, mediaId)
        if (diff.edit != EntryEdit()) deps.entries.edit(entryId, diff.edit)
        if (favorite != (current.favorite == true)) deps.entries.setFavorite(listOf(entryId), favorite)
        return deps.entries.readMetadata(entryId)
    }

    private suspend fun saveForLock() {
        save()
    }

    private suspend fun save(): Boolean = work.withLock { saveLocked() }

    private suspend fun saveLocked(): Boolean {
        val current = ui.value
        val draft = current.draft ?: return true
        val fresh = attempt { write(draft, current.favorite) }
        if (fresh == null) {
            ui.update { it.copy(failed = true) }
            return false
        }
        ui.update { it.copy(metadata = fresh, imported = emptySet(), failed = false) }
        return true
    }

    private fun dirty(current: EditUi): Boolean {
        val metadata = current.metadata ?: return false
        val draft = current.draft ?: return false
        return draftChanged(diffDraft(metadata, draft)) || current.favorite != (metadata.favorite == true) || current.imported.isNotEmpty()
    }

    private fun requestExit(exit: EditExit) {
        val current = ui.value
        if (current.draft != null && (isNew || dirty(current))) {
            ui.update { it.copy(sheet = EditSheet.Unsaved(exit, isNew)) }
        } else {
            exitNow(exit)
        }
    }

    private fun exitNow(exit: EditExit) {
        when {
            isNew -> send(EditEvent.Home)
            exit == EditExit.VIEW -> send(EditEvent.Replace(Route.Entry(entryId)))
            else -> send(EditEvent.Back)
        }
    }

    private fun pendingExit(): EditExit = (ui.value.sheet as? EditSheet.Unsaved)?.exit ?: EditExit.BACK

    private fun done() {
        viewModelScope.launch {
            if (!save()) return@launch
            savedExit(EditExit.BACK, notice = true)
        }
    }

    private fun saveAndExit() {
        val exit = pendingExit()
        ui.update { it.copy(sheet = null) }
        viewModelScope.launch {
            if (!save()) return@launch
            savedExit(exit, notice = exit == EditExit.BACK)
        }
    }

    private fun savedExit(exit: EditExit, notice: Boolean) {
        val mediaIds = ui.value.metadata?.media?.map { it.mediaId }.orEmpty()
        val count = deps.originals.deletableCount(mediaIds)
        if (count > 0) {
            ui.update { it.copy(sheet = EditSheet.DeviceOriginals(count, mediaIds, exit, notice)) }
        } else {
            leaveSaved(exit, notice)
        }
    }

    private fun leaveSaved(exit: EditExit, notice: Boolean) {
        if (notice) deps.notices.value = GalleryNotice.Saved(deps.clock.nowMs())
        exitNow(exit)
    }

    private fun dismissSheet() {
        if (ui.value.sheet is EditSheet.DeviceOriginals) keepDeviceOriginals() else ui.update { it.copy(sheet = null) }
    }

    private fun takeDeviceOriginalsSheet(): EditSheet.DeviceOriginals? {
        val sheet = ui.value.sheet as? EditSheet.DeviceOriginals ?: return null
        ui.update { it.copy(sheet = null) }
        return sheet
    }

    private fun keepDeviceOriginals() {
        val sheet = takeDeviceOriginalsSheet() ?: return
        deps.originals.keep(sheet.mediaIds)
        leaveSaved(sheet.exit, sheet.notice)
    }

    private fun deleteDeviceOriginals() {
        val sheet = takeDeviceOriginalsSheet() ?: return
        viewModelScope.launch {
            attempt { deps.originals.delete(sheet.mediaIds) }
            leaveSaved(sheet.exit, sheet.notice)
        }
    }

    private fun discard() {
        val exit = pendingExit()
        val current = ui.value
        val mediaIds = current.metadata?.media?.map { it.mediaId }.orEmpty() + current.imported
        ui.update { it.copy(sheet = null) }
        viewModelScope.launch {
            val discarded = work.withLock { if (isNew) deleteNew() else removeImported() }
            if (discarded) {
                deps.originals.forget(mediaIds)
                exitNow(exit)
            } else {
                ui.update { it.copy(failed = true) }
            }
        }
    }

    private suspend fun deleteNew(): Boolean = attempt { deps.entries.deletePermanently(listOf(entryId)) } != null

    private suspend fun removeImported(): Boolean {
        val imported = ui.value.imported
        if (imported.isEmpty()) return true
        val present = attempt { deps.entries.readMetadata(entryId) }?.media?.map { it.mediaId } ?: return false
        val removable = present.filter { it in imported }.let { if (it.size >= present.size) it.dropLast(EditConstants.SINGLE_ITEM) else it }
        var removed = true
        for (mediaId in removable) {
            if (attempt { deps.entries.removeMedia(entryId, mediaId) } == null) removed = false
        }
        return removed
    }

    private fun missing() {
        if (leaving) return
        leaving = true
        send(EditEvent.Back)
    }

    private suspend fun reloadLocked() {
        val fresh = attempt { deps.entries.readMetadata(entryId) }
        if (fresh == null) {
            missing()
            return
        }
        val target = cropping
        var consumed = false
        var replacedIds: Map<String, String> = emptyMap()
        ui.update { current ->
            val previous = current.metadata
            val draft = current.draft
            if (previous == null || draft == null) {
                return@update current.copy(metadata = fresh, draft = draftFromMetadata(fresh), favorite = fresh.favorite == true)
            }
            val before = previous.media.map { it.mediaId }
            val after = fresh.media.map { it.mediaId }
            val added = after.filter { it !in before }
            val removed = before.filter { it !in after }
            val replaced = if (target != null && target in removed && added.isNotEmpty()) mapOf(target to added.first()) else emptyMap()
            consumed = replaced.isNotEmpty()
            replacedIds = replaced
            val imported = (current.imported.map { replaced[it] ?: it } + added.filter { it !in replaced.values }).filter { it in after }.toSet()
            val wasFavorite = previous.favorite == true
            current.copy(
                metadata = fresh,
                draft = mergeDraft(draftFromMetadata(previous), draftFromMetadata(fresh), draft, replaced),
                favorite = if (current.favorite == wasFavorite) fresh.favorite == true else current.favorite,
                imported = imported,
            )
        }
        if (consumed) cropping = null
        replacedIds.forEach { (old, new) -> deps.originals.replaced(old, new) }
    }

    private suspend fun reload() {
        work.withLock { reloadLocked() }
    }

    private suspend fun checkClipboard() {
        val available = attempt { deps.clipboard.hasImage() } ?: false
        ui.update { it.copy(canPaste = available) }
    }

    private fun change(patch: (EditDraft) -> EditDraft) {
        ui.update { current ->
            val draft = current.draft ?: return@update current
            current.copy(draft = patch(draft))
        }
    }

    private fun trash() {
        viewModelScope.launch {
            work.withLock { saveLocked() }
            attempt { deps.entries.trash(listOf(entryId)) } ?: return@launch
            deps.notices.value = GalleryNotice.Trashed(entryId)
            send(EditEvent.Home)
        }
    }

    private fun screenVisible(visible: Boolean) {
        if (!visible) {
            deps.voice.stop()
            return
        }
        viewModelScope.launch {
            reload()
            checkClipboard()
        }
    }

    private fun items(current: EditUi): List<MediaDescriptorV1> {
        val metadata = current.metadata ?: return emptyList()
        val draft = current.draft ?: return emptyList()
        val byId = metadata.media.associateBy { it.mediaId }
        return draft.mediaIds.mapNotNull { byId[it] }
    }

    private fun openMediaMenu(mediaId: String) {
        val media = items(ui.value)
        val position = media.indexOfFirst { it.mediaId == mediaId }
        val item = media.getOrNull(position) ?: return
        ui.update { it.copy(sheet = EditSheet.MediaMenu(mediaId, item.kind, canSetCover = position != 0)) }
    }

    private fun saveCaption(mediaId: String, caption: String) {
        val text = caption.take(CAPTION_MAX_CHARS).trim()
        ui.update { it.copy(sheet = null) }
        change { draft ->
            draft.copy(captions = if (text.isEmpty()) draft.captions - mediaId else draft.captions + (mediaId to text))
        }
    }

    private fun setCover(mediaId: String) {
        ui.update { it.copy(sheet = null, page = 0) }
        change { draft -> draft.copy(mediaIds = listOf(mediaId) + draft.mediaIds.filter { it != mediaId }) }
    }

    private fun crop(mediaId: String) {
        ui.update { it.copy(sheet = null) }
        cropping = mediaId
        send(EditEvent.Open(Route.EntryCrop(entryId, mediaId)))
    }

    private fun askRemove(mediaId: String) {
        val media = items(ui.value)
        val item = media.firstOrNull { it.mediaId == mediaId } ?: return
        ui.update { it.copy(sheet = EditSheet.Remove(mediaId, item.kind, last = media.size <= EditConstants.SINGLE_ITEM)) }
    }

    private fun removeItem(mediaId: String, resetPage: Boolean) {
        ui.update { it.copy(sheet = null) }
        if (items(ui.value).size <= EditConstants.SINGLE_ITEM) return
        if (resetPage) ui.update { it.copy(page = 0) }
        change { draft -> draft.copy(mediaIds = draft.mediaIds.filter { it != mediaId }) }
    }

    private fun openCapture(mode: CaptureMode) {
        send(EditEvent.Open(Route.Capture(entryId, mode)))
    }

    private fun toggleFavorite() {
        ui.update { it.copy(favorite = !it.favorite) }
    }

    private fun limit(): Int = (deps.config.current()?.maxMediaPerEntry ?: EntriesConstants.FALLBACK_MAX_MEDIA_PER_ENTRY).toInt()

    private fun paste() {
        viewModelScope.launch {
            val input = attempt { deps.clipboard.readImage() } ?: return@launch
            work.withLock {
                if (attempt { importLocked(input) } == null) ui.update { it.copy(failed = true) }
                reloadLocked()
            }
        }
    }

    private fun pickMedia() {
        val used = ui.value.metadata?.media?.size ?: return
        send(EditEvent.PickMedia(limit() - used))
    }

    private fun picked(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            work.withLock {
                for (uri in uris) {
                    val input = attempt { deps.picks.copy(uri) }
                    val mediaId = input?.let { attempt { importLocked(it) } }
                    if (input != null) deps.picks.imported(input, mediaId)
                    if (mediaId == null) ui.update { it.copy(failed = true) }
                }
                reloadLocked()
            }
        }
    }

    private suspend fun importLocked(input: MediaInput): String? {
        val current = deps.entries.readMetadata(entryId)
        val outcome = deps.imports.importOne(input, ImportSlots(used = current.media.size, limit = limit()))
        if (outcome !is ImportOutcome.Imported) return null
        deps.entries.addMedia(entryId, listOf(outcome.media))
        return outcome.media.mediaId
    }

    private fun startRecording() {
        ui.update { it.copy(voiceFailed = false) }
        viewModelScope.launch {
            if (attempt { deps.recorder.start() } == null) {
                ui.update { it.copy(voiceFailed = true, recording = null) }
                return@launch
            }
            ui.update { it.copy(recording = RecordingUi(0)) }
            ticker?.cancel()
            ticker = viewModelScope.launch { tick() }
        }
    }

    private suspend fun tick() {
        while (true) {
            delay(EditConstants.VOICE_TICK_MS)
            val elapsed = deps.recorder.elapsedMs()
            ui.update { current -> if (current.recording == null) current else current.copy(recording = RecordingUi(elapsed)) }
            if (elapsed >= EditConstants.VOICE_MAX_MS) {
                stopRecording()
                return
            }
        }
    }

    private fun stopRecording() {
        if (ui.value.recording == null) return
        ticker?.cancel()
        ticker = null
        ui.update { it.copy(recording = null) }
        viewModelScope.launch {
            val saved = attempt { saveRecording() } ?: false
            if (!saved) ui.update { it.copy(voiceFailed = true) }
        }
    }

    private suspend fun saveRecording(): Boolean = work.withLock { saveRecordingLocked() }

    private suspend fun saveRecordingLocked(): Boolean {
        val recorded = deps.recorder.stop() ?: return true
        val current = deps.entries.readMetadata(entryId)
        val outcome = deps.imports.importVoiceNote(
            recorded.input,
            VoiceNoteInfo(recorded.durationMs, recorded.mimeType),
            ImportSlots(used = current.media.size, limit = limit()),
        )
        if (outcome !is ImportOutcome.Imported) return false
        deps.entries.addMedia(entryId, listOf(outcome.media))
        reloadLocked()
        return true
    }

    private fun toggleTimeEditor() {
        ui.update { current ->
            val draft = current.draft ?: return@update current
            val editor = if (current.timeEditor == null) {
                TimeEditorUi(
                    date = localDateOf(draft.time.takenAtUtc, draft.time.utcOffsetMinutes),
                    time = localTimeOf(draft.time.takenAtUtc, draft.time.utcOffsetMinutes),
                    invalid = false,
                )
            } else {
                null
            }
            current.copy(timeEditor = editor)
        }
    }

    private fun applyTime(date: String, time: String) {
        val draft = ui.value.draft ?: return
        val offset = draft.time.utcOffsetMinutes
        val utc = localPartsToUtc(date, time, offset)
        if (utc == null) {
            ui.update { it.copy(timeEditor = TimeEditorUi(date, time, invalid = true)) }
            return
        }
        ui.update { it.copy(timeEditor = null) }
        change { it.copy(time = EntryTimeEdit(utc, offset)) }
    }

    private fun resetTime() {
        val metadata = ui.value.metadata ?: return
        ui.update { it.copy(timeEditor = null) }
        change { it.copy(time = EntryTimeEdit(metadata.takenAtUtc, metadata.utcOffsetMinutes)) }
    }

    private fun tagChoices(): List<String> {
        val counted = deps.index.index()?.allTags().orEmpty()
        return orderTags(counted, deps.settings.value.tagOrder).map { it.tag }
    }

    private fun toggleTag(tag: String) {
        val draft = ui.value.draft ?: return
        if (tag !in draft.tags && draft.tags.size >= TAGS_PER_ENTRY_MAX) return
        change { current -> current.copy(tags = toggleTagChoice(current.tags, tag, single = false)) }
    }

    private fun editNote() {
        val note = ui.value.draft?.note ?: return
        ui.update { it.copy(noteEditor = NoteEditorUi(TextSelection(note.length, note.length))) }
    }

    private fun noteChanged(text: String, selection: TextSelection) {
        ui.update { current -> current.copy(noteEditor = current.noteEditor?.copy(selection = selection)) }
        change { it.copy(note = text) }
    }

    private fun toolbar(action: EditAction.Toolbar) {
        val current = ui.value
        val note = current.draft?.note ?: return
        val selection = current.noteEditor?.selection ?: TextSelection(note.length, note.length)
        val edited = applyMarkdownAction(action.action, note, selection, clockText())
        ui.update { it.copy(noteEditor = it.noteEditor?.copy(selection = edited.selection) ?: NoteEditorUi(edited.selection)) }
        change { it.copy(note = edited.text) }
    }

    private fun copyNote() {
        val current = ui.value
        val note = current.draft?.note ?: return
        val text = if (current.noteEditor?.preview == true) markdownPlainText(note) else note
        if (text.isNotEmpty()) deps.clipboard.copyText(text)
    }

    private fun clockText(): String {
        val now = deps.clock.nowMs()
        val minutes = Math.floorMod(Math.floorDiv(now, EditConstants.MS_PER_MINUTE) + deps.clock.deviceOffsetMinutes(now), EditConstants.MINUTES_PER_HOUR * EditConstants.HOURS_PER_DAY)
        val time = pad(minutes / EditConstants.MINUTES_PER_HOUR) + EditConstants.CLOCK_SEPARATOR + pad(minutes % EditConstants.MINUTES_PER_HOUR)
        return formatClock(time, deps.settings.value.timeFormat)
    }

    private fun pad(value: Long): String = value.toString().padStart(EditConstants.CLOCK_PAD, EditConstants.CLOCK_PAD_CHAR)

    private fun sharingAvailable(): Boolean = deps.config.current()?.let { it.sharingEnabled || it.publicLinksEnabled } == true

    private fun build(current: EditUi, playing: String?): EditState = EditState(
        entryId = entryId,
        isNew = isNew,
        body = bodyOf(current),
        page = current.page,
        sheet = current.sheet,
        noteEditor = current.noteEditor,
        timeEditor = current.timeEditor,
        recording = current.recording,
        voicePlaying = playing,
        canPaste = current.canPaste,
        failed = current.failed,
        voiceFailed = current.voiceFailed,
    )

    private fun bodyOf(current: EditUi): EditBody? {
        val draft = current.draft ?: return null
        val media = items(current)
        val now = deps.clock.nowMs()
        return EditBody(
            pages = media.filter { it.kind != MediaKind.AUDIO }.map { EditPage(it.mediaId, it.kind, it.width, it.height, draft.captions[it.mediaId]) },
            voiceNotes = media.filter { it.kind == MediaKind.AUDIO }.map { EditVoiceNote(it.mediaId, it.durationMs) },
            itemCount = media.size,
            time = EditTime(draft.time.takenAtUtc, draft.time.utcOffsetMinutes, JsDate.toIsoString(now.toDouble()), deps.clock.deviceOffsetMinutes(now)),
            location = draft.location,
            tags = draft.tags,
            note = draft.note,
            favorite = current.favorite,
        )
    }
}

fun mapUri(latitude: Double, longitude: Double): String {
    val point = jsonStringifyNumber(latitude) + EditConstants.GEO_SEPARATOR + jsonStringifyNumber(longitude)
    return EditConstants.GEO_SCHEME + point + EditConstants.GEO_QUERY + point
}
