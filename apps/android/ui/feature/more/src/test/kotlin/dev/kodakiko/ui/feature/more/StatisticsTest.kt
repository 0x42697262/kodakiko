@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.kodakiko.ui.feature.more

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import dev.kodakiko.data.db.AppTheme
import dev.kodakiko.ui.core.R
import dev.kodakiko.ui.core.TestTags
import dev.kodakiko.ui.core.format.formatBytes
import dev.kodakiko.ui.core.navigation.Route
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
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
import org.robolectric.annotation.GraphicsMode

private const val NONE = "\u2014"
private const val MONTH_LABEL_START = 5
private const val MONTH_LABEL_END = 7

private val FULL_STATE = StatisticsState(
    stats = TS_STATISTICS,
    usage = USAGE,
    user = userDto(quotaBytes = 4 * GB, usedBytes = GB),
    quotaBytes = 4 * GB,
    sync = syncStatus(queued = 2, dead = 1, lastSuccessAt = LAST_SUCCESS_MS),
)

private val EMPTY_STATE = StatisticsState(stats = EMPTY_STATISTICS, usage = null, user = null, quotaBytes = null, sync = syncStatus())

@RunWith(RobolectricTestRunner::class)
class StatisticsViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val h = StatisticsHarness()

    private class Opened(val vm: StatisticsViewModel, val events: MutableList<MoreEvent>) {
        val state: StatisticsState get() = vm.state.value
    }

    private fun TestScope.open(): Opened {
        val vm = StatisticsViewModel(h.deps)
        val events = mutableListOf<MoreEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events += it } }
        runCurrent()
        return Opened(vm, events)
    }

    @Test
    fun theStateCombinesTheJournalTheDeviceTheServerAndSync() = runTest {
        assertEquals(FULL_STATE, open().state)
    }

    @Test
    fun aJournalChangeRecomputesTheStatistics() = runTest {
        val o = open()
        val before = h.journal.computations
        h.journal.stats = EMPTY_STATISTICS
        h.journal.changesState.value = 1
        runCurrent()
        assertEquals(EMPTY_STATISTICS, o.state.stats)
        assertTrue(h.journal.computations > before)
    }

    @Test
    fun theQuotaIsTheUsersOwnElseTheServerDefaultElseUnknown() = runTest {
        h.user.value = userDto(quotaBytes = null, usedBytes = GB)
        assertEquals(10 * GB, open().state.quotaBytes)
        h.config.config = null
        assertNull(open().state.quotaBytes)
        h.user.value = null
        h.config.config = publicConfig()
        val o = open()
        assertNull(o.state.user)
        assertNull(o.state.quotaBytes)
    }

    @Test
    fun aZeroUserQuotaIsKeptInsteadOfTheServerDefault() = runTest {
        h.user.value = userDto(quotaBytes = 0, usedBytes = GB)
        assertEquals(0L, open().state.quotaBytes)
    }

    @Test
    fun theUserAndSyncAreFollowedLive() = runTest {
        val o = open()
        h.user.value = userDto(quotaBytes = 8 * GB, usedBytes = 2 * GB)
        h.sync.value = syncStatus(queued = 5)
        runCurrent()
        assertEquals(8 * GB, o.state.quotaBytes)
        assertEquals(userDto(quotaBytes = 8 * GB, usedBytes = 2 * GB), o.state.user)
        assertEquals(syncStatus(queued = 5), o.state.sync)
    }

    @Test
    fun theDeviceUsageIsUnknownUntilLoadedAndAfterAFailure() = runTest {
        val gate = CompletableDeferred<Unit>()
        h.storage.gate = gate
        val o = open()
        assertNull(o.state.usage)
        gate.complete(Unit)
        runCurrent()
        assertEquals(USAGE, o.state.usage)
        h.storage.gate = null
        h.storage.failure = IllegalStateException("locked")
        assertNull(open().state.usage)
    }

    @Test
    fun backLeaves() = runTest {
        val o = open()
        o.vm.onAction(StatisticsAction.Back)
        runCurrent()
        assertEquals(listOf<MoreEvent>(MoreEvent.Back), o.events)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h3200dp-mdpi")
class StatisticsContentTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var actions = mutableListOf<StatisticsAction>()

    private fun show(state: StatisticsState) {
        compose.showWith(RecordingNavigator(listOf(Route.More, Route.Statistics))) { StatisticsContent(state = state, onAction = { actions += it }) }
        compose.waitUntil(WAIT_MS) { compose.exists(TestTags.Stats.SCREEN) }
    }

    private fun card(tag: String, title: Int, metrics: List<Pair<String, Int>>) {
        val node = compose.onNodeWithTag(tag).performScrollTo()
        node.assert(labeled(text(title)))
        for ((value, label) in metrics) node.assert(labeled(value)).assert(labeled(text(label)))
    }

    private fun assertInOrder(tags: List<String>) {
        for (tag in tags) assertTrue(tag, compose.exists(tag))
        val tops = tags.map { compose.topOf(it) }
        assertEquals(tags.toString(), tops.sorted(), tops)
    }

    @Test
    fun theCardsFollowTheV031Order() {
        show(FULL_STATE)
        assertTrue(compose.showsText(text(R.string.stats_title)))
        assertInOrder(
            listOf(
                TestTags.Stats.OVERVIEW,
                TestTags.Stats.MEDIA_CARD,
                TestTags.Stats.JOURNAL_CARD,
                TestTags.Stats.DEVICE_CARD,
                TestTags.Stats.SERVER_CARD,
                TestTags.Stats.SYNC_CARD,
                TestTags.Stats.PER_MONTH,
                TestTags.Stats.PER_YEAR,
            ),
        )
    }

    @Test
    fun theJournalCardsShowTheStatsTwinNumbers() {
        show(FULL_STATE)
        card(TestTags.Stats.OVERVIEW, R.string.stats_overview, listOf("3" to R.string.stats_entries, "5" to R.string.stats_media, formatBytes(10_000) to R.string.stats_size))
        card(TestTags.Stats.MEDIA_CARD, R.string.stats_media, listOf("4" to R.string.stats_photos, "1" to R.string.stats_videos, "1m" to R.string.stats_video_length))
        card(TestTags.Stats.JOURNAL_CARD, R.string.stats_journal, listOf("1" to R.string.stats_notes, "2" to R.string.stats_tags_count, "1" to R.string.stats_favorites))
    }

    @Test
    fun theStorageServerAndSyncCardsShowTheDeviceTheQuotaAndTheLastSync() {
        show(FULL_STATE)
        card(
            TestTags.Stats.DEVICE_CARD,
            R.string.stats_storage,
            listOf(formatBytes(300 * MB) to R.string.stats_on_device, formatBytes(12 * MB) to R.string.stats_thumbnails, formatBytes(5 * MB) to R.string.stats_waiting),
        )
        card(
            TestTags.Stats.SERVER_CARD,
            R.string.stats_server,
            listOf(formatBytes(GB) to R.string.stats_used, formatBytes(4 * GB) to R.string.stats_quota, formatBytes(3 * GB) to R.string.stats_free),
        )
        compose.onNodeWithTag(TestTags.Stats.SERVER_CARD).assert(progress(0.25f))
        card(TestTags.Stats.SYNC_CARD, R.string.stats_sync, listOf(LAST_SUCCESS_TEXT to R.string.stats_last_sync, "2" to R.string.stats_queued, "1" to R.string.stats_failed))
    }

    @Test
    fun overQuotaTheFreeSpaceIsZeroAndTheBarIsFull() {
        show(FULL_STATE.copy(user = userDto(quotaBytes = GB, usedBytes = 2 * GB), quotaBytes = GB))
        card(TestTags.Stats.SERVER_CARD, R.string.stats_server, listOf(formatBytes(2 * GB) to R.string.stats_used, formatBytes(GB) to R.string.stats_quota, formatBytes(0) to R.string.stats_free))
        compose.onNodeWithTag(TestTags.Stats.SERVER_CARD).assert(progress(1f))
    }

    @Test
    fun anUnknownQuotaShowsDashesAndNoBar() {
        show(FULL_STATE.copy(quotaBytes = null))
        card(TestTags.Stats.SERVER_CARD, R.string.stats_server, listOf(formatBytes(GB) to R.string.stats_used, NONE to R.string.stats_quota, NONE to R.string.stats_free))
        compose.onNodeWithTag(TestTags.Stats.SERVER_CARD).assert(anyProgress().not())
    }

    @Test
    fun aZeroQuotaShowsNoBarAndNoFreeSpace() {
        show(FULL_STATE.copy(user = userDto(quotaBytes = 0, usedBytes = GB), quotaBytes = 0))
        card(TestTags.Stats.SERVER_CARD, R.string.stats_server, listOf(formatBytes(GB) to R.string.stats_used, formatBytes(0) to R.string.stats_quota, formatBytes(0) to R.string.stats_free))
        compose.onNodeWithTag(TestTags.Stats.SERVER_CARD).assert(anyProgress().not())
    }

    @Test
    fun withoutAUserOrUsageTheServerCardIsGoneAndTheValuesAreDashes() {
        show(EMPTY_STATE)
        assertFalse(compose.exists(TestTags.Stats.SERVER_CARD))
        card(TestTags.Stats.DEVICE_CARD, R.string.stats_storage, listOf(NONE to R.string.stats_on_device, NONE to R.string.stats_thumbnails, NONE to R.string.stats_waiting))
        card(TestTags.Stats.SYNC_CARD, R.string.stats_sync, listOf(NONE to R.string.stats_last_sync, "0" to R.string.stats_queued, "0" to R.string.stats_failed))
        card(TestTags.Stats.OVERVIEW, R.string.stats_overview, listOf("0" to R.string.stats_entries, formatBytes(0) to R.string.stats_size))
        card(TestTags.Stats.MEDIA_CARD, R.string.stats_media, listOf("0s" to R.string.stats_video_length))
    }

    @Test
    fun theBarsUseTheV031AccessibleLabels() {
        show(FULL_STATE)
        val month = compose.onNodeWithTag(TestTags.Stats.PER_MONTH).performScrollTo()
        for (item in TS_MONTHS) month.assert(described(item.month.substring(MONTH_LABEL_START, MONTH_LABEL_END) + ": " + item.count))
        val year = compose.onNodeWithTag(TestTags.Stats.PER_YEAR).performScrollTo()
        year.assert(described("2025: 1")).assert(described("2026: 2"))
        assertTrue(compose.showsText(text(R.string.stats_per_month)))
        assertTrue(compose.showsText(text(R.string.stats_per_year)))
    }

    @Test
    fun topTagsAndTheFirstAndLastDayUseTheV031Texts() {
        show(FULL_STATE)
        assertTrue(compose.showsText(text(R.string.stats_top_tags)))
        assertTrue(compose.showsText("#beach"))
        assertTrue(compose.showsText("#family"))
        assertTrue(compose.showsText(text(R.string.stats_first_last)))
        assertTrue(compose.showsText("2025-12-20 \u2013 2026-11-05"))
        assertTrue(compose.topOf(TestTags.Stats.PER_YEAR) < compose.onAllNodes(hasText("#beach"), useUnmergedTree = true).fetchSemanticsNodes().first().boundsInRoot.top)
    }

    @Test
    fun anEmptyJournalShowsDashesForTagsAndDays() {
        show(EMPTY_STATE)
        assertTrue(compose.showsText(text(R.string.stats_top_tags)))
        assertFalse(compose.showsText("#beach"))
        assertTrue(compose.onAllNodes(hasText(NONE), useUnmergedTree = true).fetchSemanticsNodes().size >= 5)
    }

    @Test
    fun theBackArrowLeaves() {
        show(FULL_STATE)
        compose.onNodeWithTag(TestTags.Settings.BACK).performClick()
        compose.waitForIdle()
        assertEquals(listOf<StatisticsAction>(StatisticsAction.Back), actions)
    }

    private fun progress(value: Float): SemanticsMatcher {
        val matcher = SemanticsMatcher("progress $value") { node ->
            node.config.getOrElse(SemanticsProperties.ProgressBarRangeInfo) { ProgressBarRangeInfo.Indeterminate }.current == value
        }
        return matcher or hasAnyDescendant(matcher)
    }

    private fun anyProgress(): SemanticsMatcher {
        val matcher = SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)
        return matcher or hasAnyDescendant(matcher)
    }

    private companion object {
        const val WAIT_MS = 5_000L
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h1600dp-mdpi")
class VideoLengthTextTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun showsVideoLength(videoMs: Long, expected: String) {
        compose.showWith(RecordingNavigator(listOf(Route.Statistics))) {
            StatisticsContent(state = EMPTY_STATE.copy(stats = EMPTY_STATISTICS.copy(videoMs = videoMs)), onAction = {})
        }
        compose.onNodeWithTag(TestTags.Stats.MEDIA_CARD).performScrollTo().assert(labeled(expected))
    }

    @Test
    fun twentySecondsIsSeconds() = showsVideoLength(20_000L, "20s")

    @Test
    fun justUnderHalfAMinuteRoundsToSeconds() = showsVideoLength(29_999L, "30s")

    @Test
    fun halfAMinuteRoundsUpToOneMinute() = showsVideoLength(30_000L, "1m")

    @Test
    fun sixtyFiveSecondsIsOneMinute() = showsVideoLength(65_000L, "1m")

    @Test
    fun sixtyFiveMinutesIsHoursAndMinutes() = showsVideoLength(3_900_000L, "1h 5m")

    @Test
    fun twoHoursKeepsZeroMinutes() = showsVideoLength(7_200_000L, "2h 0m")
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h1800dp-xhdpi")
class StatisticsScreenshotTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun capture(name: String, state: StatisticsState, appTheme: AppTheme = AppTheme.LIGHT) {
        compose.showWith(RecordingNavigator(listOf(Route.More, Route.Statistics)), appTheme = appTheme) { StatisticsContent(state = state, onAction = {}) }
        compose.onRoot().captureScreenshot("statistics/$name")
    }

    @Test
    fun full() {
        capture("full", FULL_STATE)
    }

    @Test
    fun fullDark() {
        capture("full_dark", FULL_STATE, AppTheme.DARK)
    }

    @Test
    fun empty() {
        capture("empty", EMPTY_STATE)
    }
}
