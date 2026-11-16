package dev.kodakiko.ui.feature.calendar

import dev.kodakiko.data.db.DeviceSettingsDefaults
import dev.kodakiko.data.secure.AccountMode
import dev.kodakiko.domain.pagerMonths
import dev.kodakiko.platform.device.HapticKind
import dev.kodakiko.service.index.IndexStatus
import dev.kodakiko.ui.core.navigation.Route
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

@RunWith(RobolectricTestRunner::class)
class CalendarViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val h = CalendarHarness()

    private val a = entry("a", "2026-11-03T08:00:00.000Z")
    private val b = entry("b", "2026-11-03T18:00:00.000Z")
    private val c = entry("c", "2026-11-17T09:00:00.000Z", trashed = true)
    private val d = entry("d", "2026-10-31T09:00:00.000Z")

    private fun TestScope.open(): Pair<CalendarViewModel, Observed<CalendarState, CalendarEvent>> {
        val vm = CalendarViewModel(h.deps)
        return vm to observe(vm.state, vm.events)
    }

    @Test
    fun opensOnTheCurrentMonthWithTheV031PageList() = runTest {
        h.index.put(entry("old", "2026-02-10T09:00:00.000Z"))
        val (_, screen) = open()
        assertEquals(TODAY, screen.value.today)
        assertEquals("2026-11", screen.value.month)
        assertEquals(pagerMonths("2026-11", setOf("2026-02"), "2026-11"), screen.value.months)
        assertEquals("2025-02", screen.value.months.first())
        assertEquals("2026-11", screen.value.months.last())
        assertFalse(screen.value.canGoNext)
        assertNull(screen.value.jump)
    }

    @Test
    fun countsNonTrashedEntriesPerDayAndPicksTheNewestAsCover() = runTest {
        h.index.put(a, b, c, d)
        val (_, screen) = open()
        assertEquals(CalendarDay(count = 2, coverEntryId = "b", coverMediaId = "b-media"), screen.value.days["2026-11-03"])
        assertEquals(CalendarDay(count = 1, coverEntryId = "d", coverMediaId = "d-media"), screen.value.days["2026-10-31"])
        assertNull(screen.value.days["2026-11-17"])
        assertEquals(setOf("2026-11-03", "2026-10-31"), screen.value.days.keys)
    }

    @Test
    fun aMonthWithoutEntriesHasNoDays() = runTest {
        val (_, screen) = open()
        assertEquals(emptyMap<String, CalendarDay>(), screen.value.days)
        assertEquals(13, screen.value.months.size)
    }

    @Test
    fun onlyEntriesOfTheOwnJournalCount() = runTest {
        h.index.put(a, entry("shared", "2026-11-05T09:00:00.000Z", collectionIds = listOf("other"), isOwn = false))
        val (_, screen) = open()
        assertEquals(setOf("2026-11-03"), screen.value.days.keys)
    }

    @Test
    fun anEntryWithoutCoverMediaStillCountsWithANullCover() = runTest {
        h.index.put(entry("bare", "2026-11-06T09:00:00.000Z", coverMediaId = null))
        val (_, screen) = open()
        assertEquals(CalendarDay(count = 1, coverEntryId = "bare", coverMediaId = null), screen.value.days["2026-11-06"])
    }

    @Test
    fun countsAndCoversFollowIndexChanges() = runTest {
        h.index.put(a)
        val (_, screen) = open()
        assertEquals(1, screen.value.days["2026-11-03"]?.count)
        h.index.put(entry("a", "2026-11-04T08:00:00.000Z"))
        assertNull(screen.value.days["2026-11-03"])
        assertEquals(CalendarDay(1, "a", "a-media"), screen.value.days["2026-11-04"])
        h.index.drop("a")
        assertEquals(emptyMap<String, CalendarDay>(), screen.value.days)
    }

    @Test
    fun whileTheIndexBuildsThereAreNoDaysAndTheyAppearWhenReady() = runTest {
        h.index.put(a)
        h.index.setStatus(IndexStatus.BUILDING)
        val (_, screen) = open()
        assertEquals(emptyMap<String, CalendarDay>(), screen.value.days)
        assertEquals("2026-11", screen.value.month)
        h.index.setStatus(IndexStatus.READY)
        assertEquals(setOf("2026-11-03"), screen.value.days.keys)
    }

    @Test
    fun previousMovesFreelyIntoThePast() = runTest {
        val (vm, screen) = open()
        vm.onAction(CalendarAction.Previous)
        assertEquals("2026-10", screen.value.month)
        vm.onAction(CalendarAction.Previous)
        assertEquals("2026-09", screen.value.month)
        assertTrue(screen.value.canGoNext)
        vm.onAction(CalendarAction.Next)
        assertEquals("2026-10", screen.value.month)
    }

    @Test
    fun nextSkipsEmptyFutureMonthsAndIsDisabledAfterTheLastOne() = runTest {
        h.index.put(a, entry("future", "2027-01-15T09:00:00.000Z"))
        val (vm, screen) = open()
        assertTrue(screen.value.canGoNext)
        assertEquals(listOf("2026-11", "2027-01"), screen.value.months.takeLast(2))
        vm.onAction(CalendarAction.Next)
        assertEquals("2027-01", screen.value.month)
        assertFalse(screen.value.canGoNext)
        vm.onAction(CalendarAction.Next)
        assertEquals("2027-01", screen.value.month)
        vm.onAction(CalendarAction.Previous)
        assertEquals("2026-12", screen.value.month)
    }

    @Test
    fun withoutFutureEntriesNextIsDisabledOnTheCurrentMonth() = runTest {
        h.index.put(a)
        val (vm, screen) = open()
        assertFalse(screen.value.canGoNext)
        vm.onAction(CalendarAction.Next)
        assertEquals("2026-11", screen.value.month)
    }

    @Test
    fun todayReturnsToTheCurrentMonth() = runTest {
        val (vm, screen) = open()
        repeat(5) { vm.onAction(CalendarAction.Previous) }
        assertEquals("2026-06", screen.value.month)
        vm.onAction(CalendarAction.Today)
        assertEquals("2026-11", screen.value.month)
    }

    @Test
    fun aSwipeThatSettlesOnAPageShowsThatMonth() = runTest {
        val (vm, screen) = open()
        vm.onAction(CalendarAction.Settled("2026-08"))
        assertEquals("2026-08", screen.value.month)
        assertTrue(screen.value.canGoNext)
    }

    @Test
    fun jumpOpensPrefilledWithTheShownMonth() = runTest {
        val (vm, screen) = open()
        vm.onAction(CalendarAction.Previous)
        vm.onAction(CalendarAction.OpenJump)
        assertEquals(CalendarJump(text = "2026-10", error = null), screen.value.jump)
        vm.onAction(CalendarAction.JumpText("2025-0"))
        assertEquals(CalendarJump(text = "2025-0", error = null), screen.value.jump)
        vm.onAction(CalendarAction.DismissJump)
        assertNull(screen.value.jump)
        assertEquals("2026-10", screen.value.month)
    }

    @Test
    fun jumpRejectsBadFormatsAndStaysOpen() = runTest {
        val (vm, screen) = open()
        vm.onAction(CalendarAction.OpenJump)
        for (bad in listOf("2026-13", "2026/11", "26-11", "2026-1", "")) {
            vm.onAction(CalendarAction.JumpText(bad))
            vm.onAction(CalendarAction.SubmitJump)
            assertEquals(bad, CalendarJump(text = bad, error = CalendarJumpError.INVALID), screen.value.jump)
            assertEquals("2026-11", screen.value.month)
        }
    }

    @Test
    fun jumpToAnEmptyFutureMonthAnswersNoEntriesYetAndStays() = runTest {
        h.index.put(entry("future", "2027-01-15T09:00:00.000Z"))
        val (vm, screen) = open()
        vm.onAction(CalendarAction.OpenJump)
        vm.onAction(CalendarAction.JumpText("2026-12"))
        vm.onAction(CalendarAction.SubmitJump)
        assertEquals(CalendarJump(text = "2026-12", error = CalendarJumpError.EMPTY), screen.value.jump)
        assertEquals("2026-11", screen.value.month)
        vm.onAction(CalendarAction.JumpText("2027-01"))
        vm.onAction(CalendarAction.SubmitJump)
        assertNull(screen.value.jump)
        assertEquals("2027-01", screen.value.month)
    }

    @Test
    fun jumpToAPastMonthTrimsTheTextShowsItAndExtendsThePages() = runTest {
        val (vm, screen) = open()
        vm.onAction(CalendarAction.OpenJump)
        vm.onAction(CalendarAction.JumpText(" 2020-05 "))
        vm.onAction(CalendarAction.SubmitJump)
        assertNull(screen.value.jump)
        assertEquals("2020-05", screen.value.month)
        assertEquals("2019-05", screen.value.months.first())
        assertTrue(screen.value.canGoNext)
    }

    @Test
    fun reopeningJumpClearsTheErrorAndRefillsTheShownMonth() = runTest {
        val (vm, screen) = open()
        vm.onAction(CalendarAction.OpenJump)
        vm.onAction(CalendarAction.JumpText("nope"))
        vm.onAction(CalendarAction.SubmitJump)
        assertEquals(CalendarJumpError.INVALID, screen.value.jump?.error)
        vm.onAction(CalendarAction.OpenJump)
        assertEquals(CalendarJump(text = "2026-11", error = null), screen.value.jump)
    }

    @Test
    fun openingADayWithEntriesNavigatesToTheDayView() = runTest {
        h.index.put(a, b)
        val (vm, screen) = open()
        vm.onAction(CalendarAction.OpenDay("2026-11-03"))
        assertEquals(listOf<CalendarEvent>(CalendarEvent.Open(Route.Day("2026-11-03"))), screen.events)
    }

    @Test
    fun aDayWithoutEntriesDoesNotOpen() = runTest {
        h.index.put(a, c)
        val (vm, screen) = open()
        vm.onAction(CalendarAction.OpenDay("2026-11-17"))
        vm.onAction(CalendarAction.OpenDay("2026-11-04"))
        assertEquals(emptyList<CalendarEvent>(), screen.events)
    }

    @Test
    fun pullToSyncIsAvailableOnlyForServerAccountsWithSyncRunning() = runTest {
        val (_, screen) = open()
        assertTrue(screen.value.pullToSync)
        h.mode.value = AccountMode.LOCAL
        assertFalse(screen.value.pullToSync)
        h.mode.value = AccountMode.SERVER
        h.settings.value = DeviceSettingsDefaults.ANDROID.copy(syncPaused = true)
        assertFalse(screen.value.pullToSync)
    }

    @Test
    fun pullingTicksSyncsAndSpinsUntilTheSyncSettles() = runTest {
        val (vm, screen) = open()
        val gate = CompletableDeferred<Unit>()
        h.sync.gate = gate
        vm.onAction(CalendarAction.PullToSync)
        assertEquals(listOf(HapticKind.REFRESH), h.haptics)
        assertTrue(screen.value.refreshing)
        assertEquals(1, h.sync.calls)
        gate.complete(Unit)
        assertFalse(screen.value.refreshing)
    }

    @Test
    fun aFailingSyncStillStopsTheSpinner() = runTest {
        val (vm, screen) = open()
        h.sync.failure = IllegalStateException("offline")
        vm.onAction(CalendarAction.PullToSync)
        assertEquals(1, h.sync.calls)
        assertFalse(screen.value.refreshing)
    }

    @Test
    fun pullingDoesNothingWhenSyncIsUnavailable() = runTest {
        h.mode.value = AccountMode.LOCAL
        val (vm, screen) = open()
        vm.onAction(CalendarAction.PullToSync)
        assertEquals(0, h.sync.calls)
        assertEquals(emptyList<HapticKind>(), h.haptics)
        assertFalse(screen.value.refreshing)
    }
}
