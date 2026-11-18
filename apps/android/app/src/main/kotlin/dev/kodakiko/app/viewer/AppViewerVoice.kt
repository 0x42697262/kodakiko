package dev.kodakiko.app.viewer

import android.content.Context
import android.net.Uri
import dev.kodakiko.app.gallery.AccountMedia
import dev.kodakiko.data.media.DecryptingDataSource
import dev.kodakiko.data.media.MediaUnavailableException
import dev.kodakiko.data.media.OriginalHandle
import dev.kodakiko.platform.audio.VoicePlayer
import dev.kodakiko.ui.feature.viewer.ViewerVoice
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

class AppViewerVoice(context: Context, private val media: AccountMedia, private val ioDispatcher: CoroutineDispatcher) : ViewerVoice {
    private val appContext = context.applicationContext
    private val state = MutableStateFlow<String?>(null)
    private var player: VoicePlayer? = null
    private var loaded: String? = null
    private var handle: OriginalHandle? = null

    override val playing: StateFlow<String?> = state.asStateFlow()

    override suspend fun toggle(entryId: String, mediaId: String) {
        if (state.value == mediaId) {
            player?.pause()
            state.value = null
            return
        }
        if (loaded != mediaId) load(entryId, mediaId) ?: return
        player?.play()
        state.value = mediaId
    }

    override fun stop() {
        player?.release()
        player = null
        loaded = null
        handle?.release()
        handle = null
        state.value = null
    }

    private suspend fun load(entryId: String, mediaId: String): Unit? {
        val opened = withContext(ioDispatcher) { media.openOriginal(entryId, mediaId) } ?: return null
        opened.reader.close()
        stop()
        handle = opened.handle
        val voice = VoicePlayer(appContext)
        voice.subscribe { current -> if (current.ended || current.error != null) state.value = null }
        voice.load(
            DecryptingDataSource.Factory {
                media.openOriginal(entryId, mediaId)?.let { reopened ->
                    reopened.handle.release()
                    reopened.reader
                } ?: throw MediaUnavailableException()
            },
            Uri.Builder().scheme(ViewerAdapterConstants.MEDIA_URI_SCHEME).authority(mediaId).build(),
        )
        player = voice
        loaded = mediaId
        return Unit
    }
}
