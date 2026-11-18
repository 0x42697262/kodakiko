@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.kodakiko.ui.feature.sharing

import dev.kodakiko.contract.ShareDto
import dev.kodakiko.service.sharing.SharingErrorReason
import dev.kodakiko.service.sharing.SharingException
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

@RunWith(RobolectricTestRunner::class)
class ShareWithViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val h = SharingHarness()

    private class Opened(val vm: ShareWithViewModel, val events: MutableList<ShareWithEvent>) {
        val state: ShareWithState get() = vm.state.value
    }

    private fun TestScope.open(): Opened {
        h.journalRow(PICNICS, "Picnics")
        val vm = ShareWithViewModel(h.deps, PICNICS)
        val events = mutableListOf<ShareWithEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events += it } }
        runCurrent()
        return Opened(vm, events)
    }

    private fun Opened.find(username: String) {
        vm.onAction(ShareWithAction.UsernameChanged(username))
        vm.onAction(ShareWithAction.Find)
    }

    private val bob = recipient(BOB_ID, "bob", "9f86 d081 884c 7d65")

    @Test
    fun theScreenOpensEmptyAndListsThePeopleItIsSharedWith() = runTest {
        h.sharing.shares[PICNICS] = mutableListOf(shareDto(CAROL_ID, "carol"))
        val o = open()
        assertEquals(ShareWithState(username = "", found = null, recipients = listOf(shareDto(CAROL_ID, "carol")), stopping = null, busy = false, error = null), o.state)
        assertEquals(listOf(SharingCall(FakeSharingService.LIST, PICNICS)), h.sharing.calls)
    }

    @Test
    fun aFailedListShowsItsMessage() = runTest {
        h.sharing.failure = SharingException(SharingErrorReason.OFFLINE)
        val o = open()
        assertEquals(SharingMessage.Offline, o.state.error)
        assertEquals(emptyList<ShareDto>(), o.state.recipients)
    }

    @Test
    fun findLooksUpTheUsernameAndShowsTheirSecurityCodeUnchanged() = runTest {
        h.sharing.users["bob"] = bob
        val o = open()
        val gate = CompletableDeferred<Unit>()
        h.sharing.gate = gate
        o.find("bob")
        runCurrent()
        assertTrue(o.state.busy)
        gate.complete(Unit)
        runCurrent()
        assertFalse(o.state.busy)
        assertEquals(FoundRecipient(username = "bob", fingerprint = "9f86 d081 884c 7d65"), o.state.found)
        assertEquals(SharingCall(FakeSharingService.LOOKUP, null, listOf("bob")), h.sharing.calls.last())
        assertNull(o.state.error)
    }

    @Test
    fun theUsernameIsPassedAsTypedAndABlankOneIsNotLookedUp() = runTest {
        h.sharing.users[" Bob "] = bob
        val o = open()
        o.find("   ")
        runCurrent()
        assertEquals(listOf(FakeSharingService.LIST), h.sharing.kinds())
        o.find(" Bob ")
        runCurrent()
        assertEquals(SharingCall(FakeSharingService.LOOKUP, null, listOf(" Bob ")), h.sharing.calls.last())
        assertEquals("bob", o.state.found!!.username)
    }

    @Test
    fun anUnknownUsernameSaysSo() = runTest {
        val o = open()
        o.find("nobody")
        runCurrent()
        assertNull(o.state.found)
        assertEquals(SharingMessage.UserNotFound, o.state.error)
        assertFalse(o.state.busy)
    }

    @Test
    fun editingTheUsernameDropsTheFoundCode() = runTest {
        h.sharing.users["bob"] = bob
        val o = open()
        o.find("bob")
        runCurrent()
        o.vm.onAction(ShareWithAction.UsernameChanged("bo"))
        assertNull(o.state.found)
        assertEquals("bo", o.state.username)
    }

    @Test
    fun shareSharesWithTheFoundPersonThenClearsTheFormAndReloadsThePeople() = runTest {
        h.sharing.users["bob"] = bob
        val o = open()
        o.find("bob")
        runCurrent()
        o.vm.onAction(ShareWithAction.ConfirmShare)
        runCurrent()
        assertEquals(
            listOf(FakeSharingService.LIST, FakeSharingService.LOOKUP, FakeSharingService.SHARE, FakeSharingService.LIST),
            h.sharing.kinds(),
        )
        assertEquals(SharingCall(FakeSharingService.SHARE, PICNICS, listOf(BOB_ID)), h.sharing.calls[2])
        assertNull(o.state.found)
        assertEquals("", o.state.username)
        assertEquals(listOf("bob"), o.state.recipients.map { it.recipientUsername })
        assertNull(o.state.error)
    }

    @Test
    fun sharingTwiceSaysItIsAlreadySharedWithThatPerson() = runTest {
        h.sharing.users["bob"] = bob
        val o = open()
        o.find("bob")
        runCurrent()
        h.sharing.failure = SharingException(SharingErrorReason.ALREADY_SHARED)
        h.sharing.failOn = FakeSharingService.SHARE
        o.vm.onAction(ShareWithAction.ConfirmShare)
        runCurrent()
        assertEquals(SharingMessage.AlreadyShared("bob"), o.state.error)
        assertEquals(FoundRecipient("bob", "9f86 d081 884c 7d65"), o.state.found)
    }

    @Test
    fun cancelHidesTheCodeWithoutSharing() = runTest {
        h.sharing.users["bob"] = bob
        val o = open()
        o.find("bob")
        runCurrent()
        o.vm.onAction(ShareWithAction.CancelShare)
        assertNull(o.state.found)
        assertEquals("bob", o.state.username)
        assertFalse(FakeSharingService.SHARE in h.sharing.kinds())
    }

    @Test
    fun removeAsksFirstThenStopsSharingAndReloads() = runTest {
        h.sharing.shares[PICNICS] = mutableListOf(shareDto(BOB_ID, "bob"), shareDto(CAROL_ID, "carol"))
        val o = open()
        o.vm.onAction(ShareWithAction.Remove(shareDto(CAROL_ID, "carol")))
        assertEquals(shareDto(CAROL_ID, "carol"), o.state.stopping)
        assertFalse(FakeSharingService.UNSHARE in h.sharing.kinds())
        o.vm.onAction(ShareWithAction.ConfirmStop)
        runCurrent()
        assertEquals(SharingCall(FakeSharingService.UNSHARE, PICNICS, listOf(CAROL_ID)), h.sharing.calls[1])
        assertEquals(FakeSharingService.LIST, h.sharing.kinds().last())
        assertNull(o.state.stopping)
        assertEquals(listOf("bob"), o.state.recipients.map { it.recipientUsername })
    }

    @Test
    fun aFailedStopKeepsTheConfirmationWithItsMessage() = runTest {
        h.sharing.shares[PICNICS] = mutableListOf(shareDto(BOB_ID, "bob"))
        val o = open()
        h.sharing.failure = SharingException(SharingErrorReason.OFFLINE)
        o.vm.onAction(ShareWithAction.Remove(shareDto(BOB_ID, "bob")))
        o.vm.onAction(ShareWithAction.ConfirmStop)
        runCurrent()
        assertEquals(shareDto(BOB_ID, "bob"), o.state.stopping)
        assertEquals(SharingMessage.Offline, o.state.error)
    }

    @Test
    fun dismissingTheConfirmationStopsNothing() = runTest {
        h.sharing.shares[PICNICS] = mutableListOf(shareDto(BOB_ID, "bob"))
        val o = open()
        o.vm.onAction(ShareWithAction.Remove(shareDto(BOB_ID, "bob")))
        o.vm.onAction(ShareWithAction.DismissStop)
        assertNull(o.state.stopping)
        assertEquals(listOf(FakeSharingService.LIST), h.sharing.kinds())
    }

    @Test
    fun aNewAttemptClearsTheEarlierError() = runTest {
        h.sharing.users["bob"] = bob
        val o = open()
        o.find("nobody")
        runCurrent()
        assertEquals(SharingMessage.UserNotFound, o.state.error)
        o.find("bob")
        runCurrent()
        assertNull(o.state.error)
    }

    @Test
    fun backLeavesTheScreen() = runTest {
        val o = open()
        o.vm.onAction(ShareWithAction.Back)
        assertEquals(listOf<ShareWithEvent>(ShareWithEvent.Back), o.events)
    }
}
