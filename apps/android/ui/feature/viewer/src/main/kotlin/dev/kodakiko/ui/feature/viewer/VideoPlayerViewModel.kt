package dev.kodakiko.ui.feature.viewer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class VideoPlayerViewModel(private val deps: VideoPlayerDeps, private val entryId: String, private val mediaId: String) : ViewModel() {
    private val eventChannel = Channel<VideoPlayerEvent>(Channel.UNLIMITED)
    private val current = MutableStateFlow(VideoPlayerState())
    private var job: Job? = null
    private var prepared: PreparedVideo? = null
    private var closed = false

    val state: StateFlow<VideoPlayerState> = current.asStateFlow()

    val events: Flow<VideoPlayerEvent> = eventChannel.receiveAsFlow()

    init {
        prepare()
    }

    fun onAction(action: VideoPlayerAction) {
        when (action) {
            VideoPlayerAction.Retry -> if (current.value.problem?.retry == true) prepare()
            VideoPlayerAction.Close -> close()
            is VideoPlayerAction.PlaybackFailed -> {
                releaseVideo()
                current.value = VideoPlayerState(progress = current.value.progress, video = null, problem = action.problem)
            }
        }
    }

    override fun onCleared() {
        closed = true
        job?.cancel()
        releaseVideo()
        super.onCleared()
    }

    private fun prepare() {
        if (closed) return
        job?.cancel()
        releaseVideo()
        current.value = VideoPlayerState()
        job = viewModelScope.launch {
            val video = try {
                deps.source.prepare(entryId, mediaId) { progress -> current.update { it.copy(progress = progress) } }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                current.update { it.copy(video = null, problem = videoProblemOf(failure)) }
                return@launch
            }
            if (closed || !currentCoroutineContext().isActive) {
                video.release()
                return@launch
            }
            prepared = video
            current.update { it.copy(video = video, problem = null) }
        }
    }

    private fun close() {
        closed = true
        job?.cancel()
        releaseVideo()
        eventChannel.trySend(VideoPlayerEvent.Back)
    }

    private fun releaseVideo() {
        val video = prepared ?: return
        prepared = null
        current.update { if (it.video === video) it.copy(video = null) else it }
        video.release()
    }
}
