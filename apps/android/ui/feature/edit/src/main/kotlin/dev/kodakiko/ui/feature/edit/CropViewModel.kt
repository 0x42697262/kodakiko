package dev.kodakiko.ui.feature.edit

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.kodakiko.service.entries.EntriesConstants
import dev.kodakiko.service.entries.FULL_CROP
import dev.kodakiko.service.entries.ReplaceResult
import dev.kodakiko.service.entries.replaceMedia
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

class CropViewModel(private val deps: CropDeps, private val entryId: String, private val mediaId: String) : ViewModel() {
    private val eventChannel = Channel<CropEvent>(Channel.UNLIMITED)
    private val current = MutableStateFlow(CropState(image = null, rect = FULL_CROP, busy = false, failed = false))
    private val released = AtomicBoolean(false)
    private var original: CropOriginal? = null

    val events: Flow<CropEvent> = eventChannel.receiveAsFlow()

    val state: StateFlow<CropState> = current.asStateFlow()

    init {
        viewModelScope.launch { open() }
    }

    fun onAction(action: CropAction) {
        when (action) {
            is CropAction.RectChanged -> current.update { it.copy(rect = action.rect) }
            CropAction.Apply -> apply()
            CropAction.Cancel -> {
                release()
                eventChannel.trySend(CropEvent.Back)
            }
        }
    }

    override fun onCleared() {
        release()
        super.onCleared()
    }

    private suspend fun <T> attempt(work: suspend () -> T): T? = try {
        work()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    private suspend fun open() {
        val descriptor = attempt { deps.entries.readMetadata(entryId) }?.media?.firstOrNull { it.mediaId == mediaId }
        val opened = descriptor?.let { attempt { deps.originals.open(entryId, mediaId) } }
        if (descriptor == null || opened == null) {
            eventChannel.trySend(CropEvent.Back)
            return
        }
        if (released.get()) {
            opened.release()
            return
        }
        original = opened
        current.update { it.copy(image = CropImage(opened.bitmap, descriptor.width, descriptor.height)) }
    }

    private fun apply() {
        val opened = original ?: return
        val state = current.value
        if (state.busy || state.image == null) return
        current.update { it.copy(busy = true, failed = false) }
        viewModelScope.launch {
            val replaced = attempt { replace(opened, state) } == ReplaceResult.REPLACED
            if (!replaced) {
                current.update { it.copy(busy = false, failed = true) }
                return@launch
            }
            release()
            eventChannel.trySend(CropEvent.Done)
        }
    }

    private suspend fun replace(opened: CropOriginal, state: CropState): ReplaceResult {
        val input = opened.crop(state.rect)
        val limit = (deps.config.current()?.maxMediaPerEntry ?: EntriesConstants.FALLBACK_MAX_MEDIA_PER_ENTRY).toInt()
        return replaceMedia(deps.entries, deps.imports::importOne, limit, entryId, mediaId, input)
    }

    private fun release() {
        if (released.compareAndSet(false, true)) original?.release()
    }
}
