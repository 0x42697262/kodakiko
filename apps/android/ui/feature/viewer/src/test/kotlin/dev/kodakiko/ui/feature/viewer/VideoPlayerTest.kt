package dev.kodakiko.ui.feature.viewer

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import dev.kodakiko.crypto.DecryptionError
import dev.kodakiko.ui.core.TestTags
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
class VideoPlayerViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val source = FakeVideoSource()

    private fun TestScope.open(): Pair<VideoPlayerViewModel, Observed<VideoPlayerState, VideoPlayerEvent>> {
        val vm = VideoPlayerViewModel(VideoPlayerDeps(source = source), ENTRY, "m1")
        return vm to observe(vm.state, vm.events)
    }

    @Test
    fun preparesTheVideoAndReportsProgressUntilItIsReady() = runTest {
        val gate = CompletableDeferred<Unit>()
        source.gate = gate
        source.progress = listOf(0.25f, 0.5f)
        val (_, screen) = open()
        assertEquals(listOf(ENTRY to "m1"), source.requests)
        assertEquals(0.5f, screen.value.progress, 0f)
        assertNull(screen.value.video)
        assertNull(screen.value.problem)
        gate.complete(Unit)
        assertEquals(source.prepared.single(), screen.value.video)
        assertNull(screen.value.problem)
    }

    @Test
    fun aDamagedVideoCannotBeRetried() = runTest {
        source.failure = DecryptionError()
        val (_, screen) = open()
        assertEquals(VideoProblem.DAMAGED, screen.value.problem)
        assertNull(screen.value.video)
    }

    @Test
    fun aFailedDownloadCanBeRetried() = runTest {
        source.failure = IOException("reset")
        val (vm, screen) = open()
        assertEquals(VideoProblem.DOWNLOAD_FAILED, screen.value.problem)
        source.failure = null
        val gate = CompletableDeferred<Unit>()
        source.gate = gate
        source.progress = emptyList()
        vm.onAction(VideoPlayerAction.Retry)
        assertNull(screen.value.problem)
        assertEquals(0f, screen.value.progress, 0f)
        assertEquals(2, source.requests.size)
        gate.complete(Unit)
        assertEquals(source.prepared.single(), screen.value.video)
    }

    @Test
    fun aPlaybackErrorReleasesTheVideoAndShowsTheProblem() = runTest {
        val (vm, screen) = open()
        val first = source.prepared.single()
        vm.onAction(VideoPlayerAction.PlaybackFailed(VideoProblem.STOPPED))
        assertEquals(VideoProblem.STOPPED, screen.value.problem)
        assertNull(screen.value.video)
        assertEquals(1, first.released)
        vm.onAction(VideoPlayerAction.Retry)
        assertEquals(2, source.prepared.size)
        assertEquals(source.prepared.last(), screen.value.video)
        assertNull(screen.value.problem)
    }

    @Test
    fun closeReleasesTheSourceAndGoesBack() = runTest {
        val (vm, screen) = open()
        vm.onAction(VideoPlayerAction.Close)
        assertEquals(listOf<VideoPlayerEvent>(VideoPlayerEvent.Back), screen.events)
        assertEquals(1, source.prepared.single().released)
    }

    @Test
    fun clearingTheViewModelReleasesTheSource() = runTest {
        val (vm, _) = open()
        clear(vm)
        assertEquals(1, source.prepared.single().released)
    }

    @Test
    fun aVideoReadyAfterClosingIsReleasedAtOnce() = runTest {
        val gate = CompletableDeferred<Unit>()
        source.gate = gate
        val (vm, screen) = open()
        vm.onAction(VideoPlayerAction.Close)
        clear(vm)
        gate.complete(Unit)
        assertTrue(source.prepared.all { it.released == 1 })
        assertEquals(listOf<VideoPlayerEvent>(VideoPlayerEvent.Back), screen.events)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h640dp-mdpi")
class VideoPlayerContentTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val actions = mutableListOf<VideoPlayerAction>()

    private fun show(state: VideoPlayerState, surfaces: VideoSurfaces = FakeVideoSurfaces()) {
        compose.show(surfaces = surfaces) { VideoPlayerContent(state = state, onAction = { actions += it }) }
    }

    @Test
    fun beforeAnyProgressItSaysLoading() {
        show(VideoPlayerState(progress = 0f, video = null, problem = null))
        compose.onNodeWithTag(TestTags.Video.SCREEN).assertExists()
        compose.onNodeWithTag(TestTags.Video.PREPARING).assertTextEquals("Loading…")
        assertFalse(compose.exists(TestTags.Video.PLAYER))
    }

    @Test
    fun whilePreparingItShowsThePercent() {
        show(VideoPlayerState(progress = 0.429f, video = null, problem = null))
        compose.onNodeWithTag(TestTags.Video.PREPARING).assertTextEquals("Preparing video… 42%")
    }

    @Test
    fun aFullProgressWithoutVideoSaysLoading() {
        show(VideoPlayerState(progress = 1f, video = null, problem = null))
        compose.onNodeWithTag(TestTags.Video.PREPARING).assertTextEquals("Loading…")
    }

    @Test
    fun aPreparedVideoPlaysFullScreen() {
        val surfaces = FakeVideoSurfaces()
        show(VideoPlayerState(progress = 1f, video = FakePreparedVideo("m1"), problem = null), surfaces)
        assertTrue(compose.exists(TestTags.Video.PLAYER))
        assertEquals("m1", surfaces.fullScreen.last())
        assertFalse(compose.exists(TestTags.Video.PREPARING))
    }

    @Test
    fun aPlaybackProblemReportedByThePlayerBecomesAnAction() {
        val surfaces = FakeVideoSurfaces().apply { reportProblem = VideoProblem.FORMAT_UNSUPPORTED }
        show(VideoPlayerState(progress = 1f, video = FakePreparedVideo("m1"), problem = null), surfaces)
        assertTrue(actions.toString(), VideoPlayerAction.PlaybackFailed(VideoProblem.FORMAT_UNSUPPORTED) in actions)
    }

    @Test
    fun problemsReadAsV031AndOnlyRetryableOnesOfferRetry() {
        val texts = mapOf(
            VideoProblem.DOWNLOAD_FAILED to "Couldn't download the video. Check your connection and try again.",
            VideoProblem.DAMAGED to "This video can't be opened. It may be damaged.",
            VideoProblem.FORMAT_UNSUPPORTED to "This video's format can't be played here. Try the Android app.",
            VideoProblem.STOPPED to "Playback stopped. Tap to retry.",
        )
        var state by mutableStateOf(VideoPlayerState(progress = 0f, video = null, problem = VideoProblem.DOWNLOAD_FAILED))
        compose.show { VideoPlayerContent(state = state, onAction = { actions += it }) }
        for ((problem, text) in texts) {
            state = VideoPlayerState(progress = 0f, video = null, problem = problem)
            compose.waitForIdle()
            compose.onNodeWithTag(TestTags.Video.PROBLEM).assertTextEquals(text)
            assertEquals(problem.name, problem.retry, compose.exists(TestTags.Video.RETRY))
            assertFalse(compose.exists(TestTags.Video.PREPARING))
        }
    }

    @Test
    fun retryAndCloseSendTheirActions() {
        show(VideoPlayerState(progress = 0f, video = null, problem = VideoProblem.STOPPED))
        compose.onNodeWithTag(TestTags.Video.RETRY).assertTextEquals("Retry").performClick()
        compose.onNodeWithTag(TestTags.Video.CLOSE).assertTextEquals("Close").performClick()
        assertEquals(listOf(VideoPlayerAction.Retry, VideoPlayerAction.Close), actions)
    }
}
