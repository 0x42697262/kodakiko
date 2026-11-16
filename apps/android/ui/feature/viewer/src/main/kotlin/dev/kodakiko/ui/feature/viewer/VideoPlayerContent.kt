package dev.kodakiko.ui.feature.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import dev.kodakiko.ui.core.R
import dev.kodakiko.ui.core.TestTags
import kotlin.math.floor

@Composable
fun VideoPlayerContent(state: VideoPlayerState, onAction: (VideoPlayerAction) -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .testTag(TestTags.Video.SCREEN),
        contentAlignment = Alignment.Center,
    ) {
        val video = state.video
        val problem = state.problem
        if (video != null && problem == null) {
            Box(modifier = Modifier.fillMaxSize().testTag(TestTags.Video.PLAYER)) {
                LocalVideoSurfaces.current.FullScreen(video, { onAction(VideoPlayerAction.PlaybackFailed(it)) }, Modifier.fillMaxSize())
            }
        }
        if (video == null && problem == null) {
            MediaText(preparingText(state.progress), Modifier.testTag(TestTags.Video.PREPARING))
        }
        if (problem != null) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(ViewerDimens.SCREEN_PADDING),
            ) {
                MediaText(stringResource(problemText(problem)), Modifier.testTag(TestTags.Video.PROBLEM))
                if (problem.retry) {
                    Button(onClick = { onAction(VideoPlayerAction.Retry) }, modifier = Modifier.testTag(TestTags.Video.RETRY)) {
                        Text(text = stringResource(R.string.video_retry))
                    }
                }
            }
        }
        FilledTonalButton(
            onClick = { onAction(VideoPlayerAction.Close) },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .windowInsetsPadding(WindowInsets.systemBars)
                .padding(ViewerDimens.CONTROL_PADDING)
                .testTag(TestTags.Video.CLOSE),
        ) {
            Text(text = stringResource(R.string.video_close))
        }
    }
}

@Composable
private fun MediaText(text: String, modifier: Modifier) {
    Text(
        text = text,
        color = Color.White,
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.bodyLarge,
        modifier = modifier.padding(ViewerDimens.SCREEN_PADDING),
    )
}

@Composable
private fun preparingText(progress: Float): String {
    if (progress <= 0f || progress >= 1f) return stringResource(R.string.video_loading)
    return stringResource(R.string.video_preparing, floor(progress * ViewerConstants.PERCENT).toInt())
}

private fun problemText(problem: VideoProblem): Int = when (problem) {
    VideoProblem.DOWNLOAD_FAILED -> R.string.video_download_failed
    VideoProblem.DAMAGED -> R.string.video_damaged
    VideoProblem.FORMAT_UNSUPPORTED -> R.string.video_format_unsupported
    VideoProblem.STOPPED -> R.string.video_stopped
}
