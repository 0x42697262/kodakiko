@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.kodakiko.ui.feature.viewer

import dev.kodakiko.contract.EntryMetadataV1
import dev.kodakiko.data.db.DeviceSettingsDefaults
import dev.kodakiko.ui.core.navigation.Route
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class InlineVideoViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val h = ViewerHarness()

    private val mixed = metadata(media = listOf(video("m1"), photo("p2"), video("m3")))

    private fun TestScope.open(meta: EntryMetadataV1 = mixed): Pair<EntryViewerViewModel, Observed<ViewerState, ViewerEvent>> {
        h.entries.store(ENTRY, meta)
        val vm = EntryViewerViewModel(h.deps, ENTRY)
        return vm to observe(vm.state, vm.events)
    }

    private fun TestScope.settle(ms: Long = SETTLE_MS) {
        advanceTimeBy(ms)
        runCurrent()
    }

    @Test
    fun theCurrentVideoPlaysMutedOnlyAfterItWasShownFor800Ms() = runTest {
        val (_, screen) = open()
        settle(SETTLE_MS - 1)
        assertEquals(emptyList<Pair<String, String>>(), h.videos.requests)
        assertNull(screen.value.video)
        settle(1)
        assertEquals(listOf(ENTRY to "m1"), h.videos.requests)
        val inline = requireNotNull(screen.value.video)
        assertEquals(0, inline.position)
        assertEquals("m1", inline.mediaId)
        assertTrue(inline.muted)
        assertEquals(h.videos.prepared.single(), inline.video)
    }

    @Test
    fun quickSwipesDownloadNothing() = runTest {
        val (vm, screen) = open()
        settle(SETTLE_MS / 2)
        vm.onAction(ViewerAction.PageChanged(1))
        vm.onAction(ViewerAction.PageChanged(2))
        settle(SETTLE_MS / 2)
        vm.onAction(ViewerAction.PageChanged(1))
        settle(SETTLE_MS * 4)
        assertEquals(emptyList<Pair<String, String>>(), h.videos.requests)
        assertNull(screen.value.video)
    }

    @Test
    fun aVideoOnALaterPagePlaysOnceThatPageIsShown() = runTest {
        val (vm, screen) = open(metadata(media = listOf(photo("p1"), video("m2"))))
        settle(SETTLE_MS * 2)
        assertEquals(emptyList<Pair<String, String>>(), h.videos.requests)
        vm.onAction(ViewerAction.PageChanged(1))
        settle()
        assertEquals(listOf(ENTRY to "m2"), h.videos.requests)
        assertEquals(1, screen.value.video?.position)
    }

    @Test
    fun leavingTheVideoPageStopsAndReleasesIt() = runTest {
        val (vm, screen) = open()
        settle()
        val first = h.videos.prepared.single()
        vm.onAction(ViewerAction.PageChanged(1))
        assertNull(screen.value.video)
        assertEquals(1, first.released)
        vm.onAction(ViewerAction.PageChanged(2))
        settle()
        assertEquals(listOf(ENTRY to "m1", ENTRY to "m3"), h.videos.requests)
        assertEquals("m3", screen.value.video?.mediaId)
    }

    @Test
    fun theSpeakerButtonUnmutesAndMutesAgain() = runTest {
        val (vm, screen) = open()
        settle()
        vm.onAction(ViewerAction.ToggleMute)
        assertEquals(false, screen.value.video?.muted)
        vm.onAction(ViewerAction.ToggleMute)
        assertEquals(true, screen.value.video?.muted)
    }

    @Test
    fun aVideoShownAgainStartsMutedAgain() = runTest {
        val (vm, screen) = open()
        settle()
        vm.onAction(ViewerAction.ToggleMute)
        vm.onAction(ViewerAction.PageChanged(1))
        vm.onAction(ViewerAction.PageChanged(0))
        settle()
        assertEquals(true, screen.value.video?.muted)
        assertEquals(2, h.videos.prepared.size)
    }

    @Test
    fun withAutoplayOffNothingPlaysAndPlayStaysTheWay() = runTest {
        h.settings.value = DeviceSettingsDefaults.ANDROID.copy(autoplayVideos = false)
        val (_, screen) = open()
        settle(SETTLE_MS * 4)
        assertEquals(emptyList<Pair<String, String>>(), h.videos.requests)
        assertNull(screen.value.video)
    }

    @Test
    fun turningAutoplayOffStopsThePlayingVideo() = runTest {
        val (_, screen) = open()
        settle()
        h.settings.value = DeviceSettingsDefaults.ANDROID.copy(autoplayVideos = false)
        assertNull(screen.value.video)
        assertEquals(1, h.videos.prepared.single().released)
    }

    @Test
    fun aVideoNotOnTheDevicePlaysOnlyOnWifi() = runTest {
        h.wifi.value = false
        val (_, screen) = open()
        settle(SETTLE_MS * 2)
        assertEquals(emptyList<Pair<String, String>>(), h.videos.requests)
        h.wifi.value = true
        settle(SETTLE_MS - 1)
        assertEquals(emptyList<Pair<String, String>>(), h.videos.requests)
        settle(1)
        assertEquals(listOf(ENTRY to "m1"), h.videos.requests)
        assertEquals("m1", screen.value.video?.mediaId)
    }

    @Test
    fun aVideoOnTheDevicePlaysWithoutWifi() = runTest {
        h.wifi.value = false
        h.media.onDevice += "m1"
        open()
        settle()
        assertEquals(listOf(ENTRY to "m1"), h.videos.requests)
    }

    @Test
    fun autoplayOnMobileDataLetsADownloadPlayWithoutWifi() = runTest {
        h.wifi.value = false
        h.settings.value = DeviceSettingsDefaults.ANDROID.copy(autoplayOnMobileData = true)
        open()
        settle()
        assertEquals(listOf(ENTRY to "m1"), h.videos.requests)
    }

    @Test
    fun leavingTheScreenReleasesTheVideoAndComingBackPlaysItAgainAfter800Ms() = runTest {
        val (vm, screen) = open()
        settle()
        vm.onAction(ViewerAction.ScreenVisible(false))
        assertNull(screen.value.video)
        assertEquals(1, h.videos.prepared.single().released)
        settle(SETTLE_MS * 2)
        assertEquals(1, h.videos.requests.size)
        vm.onAction(ViewerAction.ScreenVisible(true))
        settle(SETTLE_MS - 1)
        assertEquals(1, h.videos.requests.size)
        settle(1)
        assertEquals(2, h.videos.requests.size)
        assertEquals("m1", screen.value.video?.mediaId)
    }

    @Test
    fun aVideoPreparedAfterThePageWasLeftIsReleasedAtOnce() = runTest {
        val gate = CompletableDeferred<Unit>()
        h.videos.gate = gate
        val (vm, screen) = open()
        settle()
        vm.onAction(ViewerAction.PageChanged(1))
        gate.complete(Unit)
        runCurrent()
        assertNull(screen.value.video)
        assertTrue(h.videos.prepared.all { it.released == 1 })
    }

    @Test
    fun aFailedPreparationShowsNoInlineVideo() = runTest {
        h.videos.failure = IllegalStateException("offline")
        val (_, screen) = open()
        settle()
        assertEquals(1, h.videos.requests.size)
        assertNull(screen.value.video)
    }

    @Test
    fun clearingTheViewModelReleasesThePlayingVideo() = runTest {
        val (vm, _) = open()
        settle()
        clear(vm)
        assertEquals(1, h.videos.prepared.single().released)
    }

    @Test
    fun playStillOpensTheFullScreenPlayerWhileTheVideoPlaysInline() = runTest {
        val (vm, screen) = open()
        settle()
        vm.onAction(ViewerAction.OpenPlayer("m1"))
        assertEquals(ViewerEvent.Open(Route.Play(ENTRY, "m1")), screen.events.last())
    }

    private companion object {
        const val SETTLE_MS = 800L
    }
}
