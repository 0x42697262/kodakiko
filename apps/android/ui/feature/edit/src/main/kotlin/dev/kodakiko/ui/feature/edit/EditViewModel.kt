package dev.kodakiko.ui.feature.edit

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

private data class EditUi(
    val metadata: EntryMetadataV1? = null,
    val draft: EditDraft? = null,
    val favorite: Boolean = false,
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
    private val saver = Autosaver(viewModelScope, ::write) { ui.update { it.copy(failed = true) } }
    private val unregister = deps.flushes.register(saver::flush)
    private var ticker: Job? = null
    private var leaving = false

    val events: Flow<EditEvent> = eventChannel.receiveAsFlow()

    val state: StateFlow<EditState> = combine(ui, deps.voice.playing) { current, playing -> build(current, playing) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(EditConstants.STATE_SUBSCRIPTION_MS), build(ui.value, deps.voice.playing.value))

    init {
        viewModelScope.launch {
            load()
            checkClipboard()
        }
    }

    fun onAction(action: EditAction) {
        when (action) {
            EditAction.Back, EditAction.Done -> leave()
            EditAction.ViewEntry -> afterFlush { send(EditEvent.Replace(Route.Entry(entryId))) }
            EditAction.Trash -> trash()
            is EditAction.PageChanged -> ui.update { it.copy(page = action.page) }
            is EditAction.ScreenVisible -> screenVisible(action.visible)
            is EditAction.OpenMediaMenu -> openMediaMenu(action.mediaId)
            EditAction.DismissSheet -> ui.update { it.copy(sheet = null) }
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
            EditAction.AddFromDevice -> openCapture(CaptureMode.PHOTO)
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
                afterFlush { send(EditEvent.Open(Route.Tags)) }
            }
            EditAction.EditNote -> editNote()
            is EditAction.NoteChanged -> noteChanged(action.text, action.selection)
            is EditAction.Toolbar -> toolbar(action)
            EditAction.NoteDone -> ui.update { it.copy(noteEditor = null) }
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

    private fun afterFlush(then: suspend () -> Unit) {
        viewModelScope.launch {
            saver.flush()
            then()
        }
    }

    private fun closeSheetThen(work: suspend () -> Unit) {
        ui.update { it.copy(sheet = null) }
        viewModelScope.launch { attempt(work) }
    }

    private suspend fun write(next: EditDraft) {
        val current = deps.entries.readMetadata(entryId)
        val diff = diffDraft(current, next)
        for (mediaId in diff.removedMediaIds) deps.entries.removeMedia(entryId, mediaId)
        if (diff.edit != EntryEdit()) deps.entries.edit(entryId, diff.edit)
        ui.update { it.copy(failed = false) }
    }

    private suspend fun load(): Boolean {
        val metadata = attempt { deps.entries.readMetadata(entryId) }
        if (metadata == null) {
            if (!leaving) {
                leaving = true
                send(EditEvent.Back)
            }
            return false
        }
        ui.update { it.copy(metadata = metadata, draft = draftFromMetadata(metadata), favorite = metadata.favorite == true) }
        return true
    }

    private suspend fun reload() {
        saver.flush()
        load()
    }

    private suspend fun checkClipboard() {
        val available = attempt { deps.clipboard.hasImage() } ?: false
        ui.update { it.copy(canPaste = available) }
    }

    private fun change(debounced: Boolean = false, patch: (EditDraft) -> EditDraft) {
        var next: EditDraft? = null
        ui.update { current ->
            val draft = current.draft ?: return@update current
            val patched = patch(draft)
            next = patched
            current.copy(draft = patched)
        }
        next?.let { saver.change(it, debounced) }
    }

    private fun leave() {
        viewModelScope.launch {
            saver.flush()
            deps.notices.value = GalleryNotice.Saved(deps.clock.nowMs())
            send(if (isNew) EditEvent.Home else EditEvent.Back)
        }
    }

    private fun trash() {
        viewModelScope.launch {
            saver.flush()
            attempt { deps.entries.trash(listOf(entryId)) } ?: return@launch
            deps.notices.value = GalleryNotice.Trashed(entryId)
            send(EditEvent.Home)
        }
    }

    private fun screenVisible(visible: Boolean) {
        if (!visible) {
            deps.voice.stop()
            viewModelScope.launch { saver.flush() }
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
        afterFlush { send(EditEvent.Open(Route.EntryCrop(entryId, mediaId))) }
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
        afterFlush { send(EditEvent.Open(Route.Capture(entryId, mode))) }
    }

    private fun toggleFavorite() {
        var next = false
        ui.update { current ->
            next = !current.favorite
            current.copy(favorite = next)
        }
        viewModelScope.launch {
            saver.flush()
            attempt { deps.entries.setFavorite(listOf(entryId), next) }
        }
    }

    private fun limit(): Int = (deps.config.current()?.maxMediaPerEntry ?: EntriesConstants.FALLBACK_MAX_MEDIA_PER_ENTRY).toInt()

    private fun paste() {
        viewModelScope.launch {
            val input = attempt { deps.clipboard.readImage() } ?: return@launch
            saver.flush()
            if (attempt { addInput(input) } == null) ui.update { it.copy(failed = true) }
            reload()
        }
    }

    private suspend fun addInput(input: MediaInput) {
        val current = deps.entries.readMetadata(entryId)
        val outcome = deps.imports.importOne(input, ImportSlots(used = current.media.size, limit = limit()))
        if (outcome is ImportOutcome.Imported) {
            deps.entries.addMedia(entryId, listOf(outcome.media))
        } else {
            ui.update { it.copy(failed = true) }
        }
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

    private suspend fun saveRecording(): Boolean {
        val recorded = deps.recorder.stop() ?: return true
        saver.flush()
        val current = deps.entries.readMetadata(entryId)
        val outcome = deps.imports.importVoiceNote(
            recorded.input,
            VoiceNoteInfo(recorded.durationMs, recorded.mimeType),
            ImportSlots(used = current.media.size, limit = limit()),
        )
        if (outcome !is ImportOutcome.Imported) return false
        deps.entries.addMedia(entryId, listOf(outcome.media))
        reload()
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
        change(debounced = true) { it.copy(note = text) }
    }

    private fun toolbar(action: EditAction.Toolbar) {
        val current = ui.value
        val note = current.draft?.note ?: return
        val selection = current.noteEditor?.selection ?: TextSelection(note.length, note.length)
        val edited = applyMarkdownAction(action.action, note, selection, clockText())
        ui.update { it.copy(noteEditor = NoteEditorUi(edited.selection)) }
        change(debounced = true) { it.copy(note = edited.text) }
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
