package dev.kodakiko.ui.feature.viewer

import android.content.Context
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import androidx.media3.ui.compose.ContentFrame

@OptIn(UnstableApi::class)
object ExoVideoSurfaces : VideoSurfaces {
    private const val SILENT = 0f
    private const val FULL_VOLUME = 1f

    private val MOVIE_ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
        .setUsage(C.USAGE_MEDIA)
        .build()

    private fun player(context: Context, video: PreparedVideo, handleAudioFocus: Boolean): ExoPlayer =
        ExoPlayer.Builder(context.applicationContext)
            .setAudioAttributes(MOVIE_ATTRIBUTES, handleAudioFocus)
            .build()
            .apply {
                setMediaSource(ProgressiveMediaSource.Factory(video.dataSourceFactory).createMediaSource(MediaItem.fromUri(video.uri)))
                prepare()
            }

    @Composable
    override fun Inline(video: PreparedVideo, muted: Boolean, modifier: Modifier) {
        val context = LocalContext.current
        val player = remember(video) {
            player(context, video, handleAudioFocus = false).apply {
                repeatMode = Player.REPEAT_MODE_ONE
                volume = SILENT
                playWhenReady = true
            }
        }
        DisposableEffect(player) { onDispose { player.release() } }
        LaunchedEffect(player, muted) { player.volume = if (muted) SILENT else FULL_VOLUME }
        ContentFrame(player = player, modifier = modifier, contentScale = ContentScale.Fit)
    }

    @Composable
    override fun FullScreen(video: PreparedVideo, onProblem: (VideoProblem) -> Unit, modifier: Modifier) {
        val context = LocalContext.current
        val currentOnProblem by rememberUpdatedState(onProblem)
        val player = remember(video) { player(context, video, handleAudioFocus = true).apply { playWhenReady = true } }
        DisposableEffect(player) {
            val listener = object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    currentOnProblem(playbackProblemOf(error.errorCode))
                }
            }
            player.addListener(listener)
            onDispose {
                player.removeListener(listener)
                player.release()
            }
        }
        AndroidView(
            factory = { viewContext ->
                PlayerView(viewContext).apply {
                    this.player = player
                    keepScreenOn = true
                    setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                }
            },
            update = { view -> view.player = player },
            onRelease = { view -> view.player = null },
            modifier = modifier,
        )
    }
}
