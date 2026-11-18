package dev.kodakiko.ui.feature.viewer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.kodakiko.contract.EntryMetadataV1
import dev.kodakiko.contract.MediaKind
import dev.kodakiko.data.db.DeviceSettings
import dev.kodakiko.data.secure.AccountMode
import dev.kodakiko.index.IndexedEntry
import dev.kodakiko.js.JsDate
import dev.kodakiko.service.index.IndexStatus
import dev.kodakiko.ui.core.navigation.Route
import dev.kodakiko.ui.core.notice.GalleryNotice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

private data class ViewerContentState(
    val ready: Boolean = false,
    val summary: IndexedEntry? = null,
    val metadata: EntryMetadataV1? = null,
    val ownerName: String? = null,
)

private data class ViewerUi(
    val page: Int = 0,
    val zoomed: Boolean = false,
    val pinned: Boolean = false,
    val sheet: ViewerSheet? = null,
    val video: InlineVideoUi? = null,
    val visible: Boolean = true,
)

private data class VideoCandidate(val position: Int, val mediaId: String, val settings: AutoplaySettings, val wifi: Boolean)

private data class VideoTarget(val position: Int, val mediaId: String)

private class PlayingVideo(val video: PreparedVideo) {
    private val released = AtomicBoolean(false)

    fun release() {
        if (released.compareAndSet(false, true)) video.release()
    }
}

internal fun pagesOf(metadata: EntryMetadataV1): List<ViewerPage> = metadata.media
    .filter { it.kind != MediaKind.AUDIO }
    .map { ViewerPage(it.mediaId, it.kind, it.width, it.height, it.caption) }

class EntryViewerViewModel(private val deps: ViewerDeps, private val entryId: String) : ViewModel() {
    private val eventChannel = Channel<ViewerEvent>(Channel.UNLIMITED)
    private val content = MutableStateFlow(ViewerContentState())
    private val ui = MutableStateFlow(ViewerUi())
    private var leaving = false
    private var ownerRequested = false
    private var metadataJob: Job? = null
    private var playing: PlayingVideo? = null

    val events: Flow<ViewerEvent> = eventChannel.receiveAsFlow()

    val state: StateFlow<ViewerState> = combine(content, ui, deps.accountMode, deps.voice.playing) { loaded, current, mode, voice ->
        build(loaded, current, mode, voice)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(ViewerConstants.STATE_SUBSCRIPTION_MS),
        build(content.value, ui.value, deps.accountMode.value, deps.voice.playing.value),
    )

    init {
        viewModelScope.launch {
            val pinned = attempt { deps.media.isPinned(entryId) } ?: false
            ui.update { it.copy(pinned = pinned) }
        }
        viewModelScope.launch { deps.index.version.collect { refresh() } }
        viewModelScope.launch { autoplay() }
    }

    fun onAction(action: ViewerAction) {
        when (action) {
            is ViewerAction.PageChanged -> ui.update { it.copy(page = action.page, zoomed = false) }
            is ViewerAction.ZoomChanged -> ui.update { it.copy(zoomed = action.zoomed) }
            ViewerAction.ToggleFavorite -> toggleFavorite()
            ViewerAction.OpenDetails -> send(ViewerEvent.Open(Route.EntryDetails(entryId)))
            ViewerAction.Edit -> send(ViewerEvent.Open(Route.EntryEdit(entryId)))
            is ViewerAction.OpenPlayer -> send(ViewerEvent.Open(Route.Play(entryId, action.mediaId)))
            ViewerAction.Back -> send(ViewerEvent.Back)
            ViewerAction.OpenMap -> content.value.metadata?.location?.let { send(ViewerEvent.OpenUri(mapUri(it.latitude, it.longitude))) }
            is ViewerAction.OpenLink -> send(ViewerEvent.OpenUri(action.url))
            is ViewerAction.ShowTag -> showTag(action.tag)
            ViewerAction.OpenMore -> ui.update { it.copy(sheet = ViewerSheet.MORE) }
            ViewerAction.ToggleKeepOffline -> toggleKeepOffline()
            ViewerAction.AddMedia -> addMedia()
            ViewerAction.Trash -> trash()
            ViewerAction.AskDeletePermanently -> ui.update { it.copy(sheet = ViewerSheet.CONFIRM_DELETE) }
            ViewerAction.ConfirmDeletePermanently -> deletePermanently()
            ViewerAction.DismissSheet -> ui.update { it.copy(sheet = null) }
            ViewerAction.Restore -> viewModelScope.launch { attempt { deps.entries.restore(listOf(entryId)) } }
            ViewerAction.Share -> share()
            is ViewerAction.ToggleVoice -> viewModelScope.launch { attempt { deps.voice.toggle(entryId, action.mediaId) } }
            is ViewerAction.ScreenVisible -> screenVisible(action.visible)
            ViewerAction.ToggleMute -> ui.update { current -> current.copy(video = current.video?.let { it.copy(muted = !it.muted) }) }
        }
    }

    override fun onCleared() {
        playing?.let(::stopPlaying)
        deps.voice.stop()
        super.onCleared()
    }

    private fun send(event: ViewerEvent) {
        eventChannel.trySend(event)
    }

    private suspend fun <T> attempt(work: suspend () -> T): T? = try {
        work()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    private fun leave(notice: GalleryNotice?) {
        leaving = true
        notice?.let { deps.notices.value = it }
        send(ViewerEvent.Leave)
    }

    private fun refresh() {
        if (leaving) return
        val index = deps.index.index()
        if (deps.index.status() != IndexStatus.READY || index == null) {
            content.update { it.copy(ready = false) }
            return
        }
        val summary = index.get(entryId)
        if (summary == null) {
            leave(GalleryNotice.Gone)
            return
        }
        content.update { it.copy(ready = true, summary = summary) }
        if (!summary.isOwn && !ownerRequested) {
            ownerRequested = true
            viewModelScope.launch {
                val owner = attempt { deps.owners(entryId) }
                content.update { it.copy(ownerName = owner) }
            }
        }
        if (summary.unreadable) {
            metadataJob?.cancel()
            content.update { it.copy(metadata = null) }
            return
        }
        reloadMetadata()
    }

    private fun reloadMetadata() {
        metadataJob?.cancel()
        metadataJob = viewModelScope.launch {
            val metadata = attempt { deps.entries.readMetadata(entryId) }
            val before = content.value.metadata?.let { previous -> pagesOf(previous).map { it.mediaId } }
            val after = metadata?.let { next -> pagesOf(next).map { it.mediaId } }
            if (before != null && after != null && before != after) ui.update { it.copy(page = 0, zoomed = false) }
            content.update { it.copy(metadata = metadata) }
        }
    }

    private fun build(loaded: ViewerContentState, current: ViewerUi, mode: AccountMode, voice: String?): ViewerState {
        val summary = loaded.summary
        val entry = if (!loaded.ready || summary == null) {
            null
        } else {
            ViewerEntry(
                owner = summary.isOwn,
                trashed = summary.trashed,
                unreadable = summary.unreadable,
                sharedBy = if (summary.isOwn) null else loaded.ownerName,
                body = if (summary.unreadable) null else loaded.metadata?.let(::bodyOf),
            )
        }
        return ViewerState(
            entryId = entryId,
            entry = entry,
            page = current.page,
            zoomed = current.zoomed,
            pinned = current.pinned,
            sheet = current.sheet,
            video = current.video,
            voicePlaying = voice,
            local = mode == AccountMode.LOCAL,
            sharingAvailable = sharingAvailable(),
        )
    }

    private fun bodyOf(metadata: EntryMetadataV1): ViewerBody {
        val now = deps.clock.nowMs()
        return ViewerBody(
            pages = pagesOf(metadata),
            time = ViewerTime(metadata.takenAtUtc, metadata.utcOffsetMinutes, JsDate.toIsoString(now.toDouble()), deps.clock.deviceOffsetMinutes(now)),
            location = metadata.location,
            voiceNotes = metadata.media.filter { it.kind == MediaKind.AUDIO }.map { VoiceNote(it.mediaId, it.durationMs) },
            tags = metadata.tags,
            note = metadata.note,
            favorite = metadata.favorite == true,
        )
    }

    private fun sharingAvailable(): Boolean = deps.config.current()?.let { it.sharingEnabled || it.publicLinksEnabled } == true

    private fun toggleFavorite() {
        val favorite = content.value.metadata?.favorite == true
        viewModelScope.launch { attempt { deps.entries.setFavorite(listOf(entryId), !favorite) } }
    }

    private fun showTag(tag: String) {
        deps.gallery(tag)
        leave(null)
    }

    private fun toggleKeepOffline() {
        val next = !ui.value.pinned
        ui.update { it.copy(sheet = null) }
        viewModelScope.launch {
            if (attempt { deps.media.setPinned(entryId, next) } != null) ui.update { it.copy(pinned = next) }
        }
    }

    private fun addMedia() {
        ui.update { it.copy(sheet = null) }
        send(ViewerEvent.Open(Route.Capture(entryId = entryId)))
    }

    private fun trash() {
        ui.update { it.copy(sheet = null) }
        leaving = true
        viewModelScope.launch {
            if (attempt { deps.entries.trash(listOf(entryId)) } == null) {
                leaving = false
                return@launch
            }
            leave(GalleryNotice.Trashed(entryId))
        }
    }

    private fun deletePermanently() {
        ui.update { it.copy(sheet = null) }
        leaving = true
        viewModelScope.launch {
            if (attempt { deps.entries.deletePermanently(listOf(entryId)) } == null) {
                leaving = false
                return@launch
            }
            leave(null)
        }
    }

    private fun share() {
        val owner = content.value.summary?.isOwn == true
        ui.update { it.copy(sheet = null) }
        send(ViewerEvent.Share(listOf(entryId), inKodakiko = owner && sharingAvailable()))
    }

    private fun screenVisible(visible: Boolean) {
        ui.update { it.copy(visible = visible) }
        if (!visible) deps.voice.stop()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun autoplay() {
        combine(ui, content, deps.settings, deps.wifi) { current, loaded, settings, wifi -> candidate(current, loaded, settings, wifi) }
            .distinctUntilChanged()
            .mapLatest { candidate -> candidate?.let { target(it) } }
            .distinctUntilChanged()
            .collectLatest { target -> if (target != null) play(target) }
    }

    private fun candidate(current: ViewerUi, loaded: ViewerContentState, settings: DeviceSettings, wifi: Boolean): VideoCandidate? {
        if (!current.visible || loaded.summary?.unreadable != false) return null
        val page = loaded.metadata?.let(::pagesOf)?.getOrNull(current.page) ?: return null
        if (page.kind != MediaKind.VIDEO) return null
        return VideoCandidate(current.page, page.mediaId, AutoplaySettings(settings.autoplayVideos, settings.autoplayOnMobileData), wifi)
    }

    private suspend fun target(candidate: VideoCandidate): VideoTarget? {
        val cached = attempt { deps.media.isOriginalOnDevice(candidate.mediaId) } ?: false
        val wanted = shouldAutoplay(candidate.settings, AutoplayState(active = true, cached = cached, wifi = candidate.wifi))
        return if (wanted) VideoTarget(candidate.position, candidate.mediaId) else null
    }

    private suspend fun play(target: VideoTarget) {
        delay(ViewerConstants.AUTOPLAY_SETTLE_MS)
        val prepared = attempt { deps.videos.prepare(entryId, target.mediaId) {} } ?: return
        val holder = PlayingVideo(prepared)
        playing = holder
        try {
            currentCoroutineContext().ensureActive()
            ui.update { it.copy(video = InlineVideoUi(target.position, target.mediaId, prepared, muted = true)) }
            awaitCancellation()
        } finally {
            stopPlaying(holder)
        }
    }

    private fun stopPlaying(holder: PlayingVideo) {
        if (playing === holder) playing = null
        ui.update { current -> if (current.video?.video === holder.video) current.copy(video = null) else current }
        holder.release()
    }
}
