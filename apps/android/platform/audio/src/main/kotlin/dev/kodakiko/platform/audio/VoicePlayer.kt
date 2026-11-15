package dev.kodakiko.platform.audio

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource

data class VoicePlayerState(val ready: Boolean, val playing: Boolean, val ended: Boolean, val error: Throwable?)

@OptIn(UnstableApi::class)
class VoicePlayer(context: Context) {
    private val listeners = mutableListOf<(VoicePlayerState) -> Unit>()
    private val player: ExoPlayer = ExoPlayer.Builder(context.applicationContext)
        .setAudioAttributes(VOICE_ATTRIBUTES, true)
        .setHandleAudioBecomingNoisy(true)
        .build()

    init {
        player.addListener(
            object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) = publish()
            },
        )
    }

    fun load(factory: DataSource.Factory, uri: Uri) {
        player.setMediaSource(ProgressiveMediaSource.Factory(factory).createMediaSource(MediaItem.fromUri(uri)))
        player.prepare()
    }

    fun play() {
        if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
        player.play()
    }

    fun pause() = player.pause()

    fun seekTo(ms: Long) = player.seekTo(ms)

    fun positionMs(): Long = player.currentPosition

    fun durationMs(): Long = player.duration.takeUnless { it == C.TIME_UNSET } ?: AudioConstants.NO_DURATION_MS

    fun isPlaying(): Boolean = player.isPlaying

    fun subscribe(listener: (VoicePlayerState) -> Unit): () -> Unit {
        listeners += listener
        listener(state())
        return { listeners.remove(listener) }
    }

    fun release() {
        listeners.clear()
        player.release()
    }

    private fun state() = VoicePlayerState(
        ready = player.playbackState == Player.STATE_READY,
        playing = player.isPlaying,
        ended = player.playbackState == Player.STATE_ENDED,
        error = player.playerError,
    )

    private fun publish() {
        val current = state()
        listeners.toList().forEach { it(current) }
    }

    private companion object {
        val VOICE_ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
            .setUsage(C.USAGE_MEDIA)
            .build()
    }
}
