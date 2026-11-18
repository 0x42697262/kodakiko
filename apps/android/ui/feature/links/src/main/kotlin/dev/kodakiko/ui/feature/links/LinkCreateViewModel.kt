package dev.kodakiko.ui.feature.links

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.kodakiko.contract.COLLECTION_NAME_MAX_CHARS
import dev.kodakiko.contract.EntryMetadataV1
import dev.kodakiko.contract.LinkExpiry
import dev.kodakiko.contract.MediaKind
import dev.kodakiko.data.net.NetworkError
import dev.kodakiko.js.jsTrim
import dev.kodakiko.service.links.CreateLinkInput
import dev.kodakiko.service.links.CreateLinkProgress
import dev.kodakiko.service.links.CreateLinkResult
import dev.kodakiko.service.links.CreateLinkStep
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@Immutable
data class LinkMedia(val mediaId: String, val position: Int, val kind: MediaKind)

@Immutable
data class LinkSection(val entryId: String, val heading: String, val media: List<LinkMedia>)

enum class LinkError {
    OFFLINE,
    FAILED,
}

@Immutable
data class LinkCreateState(
    val sections: List<LinkSection>?,
    val selected: Set<String>,
    val title: String,
    val includeNotes: Boolean,
    val expiry: LinkExpiry,
    val estimateBytes: Long,
    val progress: CreateLinkProgress?,
    val result: CreateLinkResult?,
    val error: LinkError?,
    val copied: Boolean,
)

sealed interface LinkCreateAction {
    data class Toggle(val mediaId: String) : LinkCreateAction

    data class SelectEntry(val entryId: String, val on: Boolean) : LinkCreateAction

    data object SelectAll : LinkCreateAction

    data object SelectNone : LinkCreateAction

    data class TitleChanged(val title: String) : LinkCreateAction

    data object ToggleNotes : LinkCreateAction

    data class ChooseExpiry(val expiry: LinkExpiry) : LinkCreateAction

    data object Create : LinkCreateAction

    data object Copy : LinkCreateAction

    data object Share : LinkCreateAction

    data object Cancel : LinkCreateAction

    data object Done : LinkCreateAction
}

sealed interface LinkCreateEvent {
    data object Back : LinkCreateEvent
}

private class LoadedEntry(val entryId: String, val metadata: EntryMetadataV1)

private fun heading(metadata: EntryMetadataV1): String {
    val parts = listOf(metadata.takenAtUtc.take(LinksConstants.DATE_CHARS), metadata.note.substringBefore(LinksConstants.NOTE_LINE_BREAK))
    return parts.filter { it.isNotEmpty() }.joinToString(LinksConstants.HEADING_SEPARATOR)
}

private fun sectionsOf(entries: List<LoadedEntry>): List<LinkSection> {
    var position = 0
    return entries.map { entry ->
        LinkSection(entry.entryId, heading(entry.metadata), entry.metadata.media.map { LinkMedia(it.mediaId, position++, it.kind) })
    }
}

class LinkCreateViewModel(private val deps: LinksDeps, private val entryIds: List<String>) : ViewModel() {
    private val mutableState = MutableStateFlow(
        LinkCreateState(
            sections = null,
            selected = emptySet(),
            title = deps.defaultTitle,
            includeNotes = true,
            expiry = LinkExpiry.SEVEN_DAYS,
            estimateBytes = 0,
            progress = null,
            result = null,
            error = null,
            copied = false,
        ),
    )
    private val channel = Channel<LinkCreateEvent>(Channel.BUFFERED)
    private var entries: List<LoadedEntry> = emptyList()

    val state: StateFlow<LinkCreateState> = mutableState.asStateFlow()

    val events: Flow<LinkCreateEvent> = channel.receiveAsFlow()

    init {
        viewModelScope.launch { load() }
    }

    fun onAction(action: LinkCreateAction) {
        when (action) {
            is LinkCreateAction.Toggle -> select(listOf(action.mediaId), action.mediaId !in mutableState.value.selected)
            is LinkCreateAction.SelectEntry -> select(mediaOf(entries.filter { it.entryId == action.entryId }), action.on)
            LinkCreateAction.SelectAll -> select(mediaOf(entries), true)
            LinkCreateAction.SelectNone -> setSelected(emptySet())
            is LinkCreateAction.TitleChanged -> mutableState.update { it.copy(title = action.title.take(COLLECTION_NAME_MAX_CHARS)) }
            LinkCreateAction.ToggleNotes -> mutableState.update { it.copy(includeNotes = !it.includeNotes) }
            is LinkCreateAction.ChooseExpiry -> mutableState.update { it.copy(expiry = action.expiry) }
            LinkCreateAction.Create -> create()
            LinkCreateAction.Copy -> copy()
            LinkCreateAction.Share -> mutableState.value.result?.let { deps.sharing.share(it.url) }
            LinkCreateAction.Cancel, LinkCreateAction.Done -> channel.trySend(LinkCreateEvent.Back)
        }
    }

    private suspend fun load() {
        val loaded = try {
            entryIds.map { LoadedEntry(it, deps.readMetadata(it)) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            emptyList()
        }
        entries = loaded
        mutableState.update { it.copy(sections = sectionsOf(loaded)) }
        setSelected(mediaOf(loaded).toSet())
    }

    private fun mediaOf(loaded: List<LoadedEntry>): List<String> = loaded.flatMap { entry -> entry.metadata.media.map { it.mediaId } }

    private fun select(mediaIds: List<String>, on: Boolean) {
        val current = mutableState.value.selected
        setSelected(if (on) current + mediaIds else current - mediaIds.toSet())
    }

    private fun setSelected(selected: Set<String>) {
        val estimate = entries.flatMap { it.metadata.media }
            .filter { it.mediaId in selected }
            .sumOf { it.originalPlaintextBytes + it.thumbnail.plaintextBytes }
        mutableState.update { it.copy(selected = selected, estimateBytes = estimate) }
    }

    private fun create() {
        val current = mutableState.value
        if (current.progress != null || current.result != null || current.selected.isEmpty() || jsTrim(current.title).isEmpty()) return
        val input = CreateLinkInput(
            entryIds = entries.map { it.entryId },
            mediaIds = current.selected,
            title = current.title,
            expiry = current.expiry,
            includeNotes = current.includeNotes,
        )
        mutableState.update {
            it.copy(error = null, progress = CreateLinkProgress(CreateLinkStep.PREPARING, 0, entries.size, 0, 0))
        }
        viewModelScope.launch {
            try {
                val result = deps.links.create(input) { progress -> mutableState.update { it.copy(progress = progress) } }
                mutableState.update { it.copy(result = result, progress = null) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                mutableState.update { it.copy(error = if (failure is NetworkError) LinkError.OFFLINE else LinkError.FAILED, progress = null) }
            }
        }
    }

    private fun copy() {
        val result = mutableState.value.result ?: return
        deps.sharing.copy(result.url)
        mutableState.update { it.copy(copied = true) }
    }
}
