@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.kodakiko.ui.feature.links

import dev.kodakiko.data.net.NetworkError
import dev.kodakiko.service.links.OwnerLink
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

@RunWith(RobolectricTestRunner::class)
class LinksListViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val h = LinksHarness()

    private class Opened(val vm: LinksListViewModel, val events: MutableList<LinksListEvent>) {
        val state: LinksListState get() = vm.state.value
    }

    private fun TestScope.open(): Opened {
        val vm = LinksListViewModel(h.deps)
        val events = mutableListOf<LinksListEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events += it } }
        runCurrent()
        return Opened(vm, events)
    }

    private val picnic = ownerLink("picnic00000000000000001", "Picnic", expiresAt = isoAt(NOW_MS + 3 * DAY_MS), viewCount = 2)
    private val party = ownerLink("party000000000000000002", "Party", maxViews = 1)

    @Test
    fun theListLoadsTheOwnersLinksWithTheTimeItWasLoaded() = runTest {
        h.links.links += listOf(picnic, party)
        val o = open()
        assertEquals(listOf(picnic, party), o.state.links)
        assertEquals(NOW_MS, o.state.nowMs)
        assertNull(o.state.revoking)
        assertFalse(o.state.error)
        assertEquals(listOf(FakeLinksService.LIST), h.links.calls)
    }

    @Test
    fun noLinksIsAnEmptyList() = runTest {
        assertEquals(emptyList<OwnerLink>(), open().state.links)
    }

    @Test
    fun aFailedLoadShowsTheErrorAndNoList() = runTest {
        h.links.listFailure = NetworkError()
        val o = open()
        assertTrue(o.state.error)
        assertNull(o.state.links)
    }

    @Test
    fun copyAndShareHandTheLinksUrlOver() = runTest {
        h.links.links += listOf(picnic, party)
        val o = open()
        o.vm.onAction(LinksListAction.Copy(party))
        o.vm.onAction(LinksListAction.Share(picnic))
        assertEquals(listOf(party.url), h.sharing.copied)
        assertEquals(listOf(picnic.url), h.sharing.shared)
    }

    @Test
    fun b085RevokeAsksFirstThenRevokesAndReloadsWithoutTheLink() = runTest {
        h.links.links += listOf(picnic, party)
        h.now = NOW_MS + HOUR_MS
        val o = open()
        o.vm.onAction(LinksListAction.Revoke(picnic))
        assertEquals(picnic, o.state.revoking)
        assertEquals(emptyList<String>(), h.links.revoked)
        o.vm.onAction(LinksListAction.ConfirmRevoke)
        runCurrent()
        assertNull(o.state.revoking)
        assertEquals(listOf(picnic.linkId), h.links.revoked)
        assertEquals(listOf(FakeLinksService.LIST, FakeLinksService.REVOKE, FakeLinksService.LIST), h.links.calls)
        assertEquals(listOf(party), o.state.links)
        assertFalse(o.state.error)
    }

    @Test
    fun dismissingTheQuestionRevokesNothing() = runTest {
        h.links.links += picnic
        val o = open()
        o.vm.onAction(LinksListAction.Revoke(picnic))
        o.vm.onAction(LinksListAction.DismissRevoke)
        runCurrent()
        assertNull(o.state.revoking)
        assertEquals(emptyList<String>(), h.links.revoked)
        assertEquals(listOf(picnic), o.state.links)
    }

    @Test
    fun aFailedRevokeClosesTheQuestionShowsTheErrorAndKeepsTheLink() = runTest {
        h.links.links += picnic
        h.links.revokeFailure = NetworkError()
        val o = open()
        o.vm.onAction(LinksListAction.Revoke(picnic))
        o.vm.onAction(LinksListAction.ConfirmRevoke)
        runCurrent()
        assertNull(o.state.revoking)
        assertTrue(o.state.error)
        assertEquals(listOf(picnic), o.state.links)
    }

    @Test
    fun backLeavesTheScreen() = runTest {
        val o = open()
        o.vm.onAction(LinksListAction.Back)
        assertEquals(listOf<LinksListEvent>(LinksListEvent.Back), o.events)
    }
}
