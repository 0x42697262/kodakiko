@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.kodakiko.ui.feature.settings

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import dev.kodakiko.contract.OutboxKind
import dev.kodakiko.data.db.OutboxRow
import dev.kodakiko.data.db.OutboxState
import dev.kodakiko.data.net.NetworkError
import dev.kodakiko.data.secure.AccountMode
import dev.kodakiko.service.sync.SyncStatus
import dev.kodakiko.ui.core.R
import dev.kodakiko.ui.core.TestTags
import dev.kodakiko.ui.core.TestTags.listItem
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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class DeadItemTextTest {
    private val dash = " \u2014 "

    private fun expected(kind: Int, entryId: String, error: Int): String = text(kind) + dash + entryId + dash + text(error)

    @Test
    fun everyOutboxKindHasItsV031Label() {
        val kinds = mapOf(
            OutboxKind.CREATE_ENTRY to R.string.sync_kind_create_entry,
            OutboxKind.UPDATE_ENTRY_META to R.string.sync_kind_update_entry_meta,
            OutboxKind.ADD_MEDIA to R.string.sync_kind_add_media,
            OutboxKind.REMOVE_MEDIA to R.string.sync_kind_remove_media,
            OutboxKind.TRASH_ENTRY to R.string.sync_kind_trash_entry,
            OutboxKind.RESTORE_ENTRY to R.string.sync_kind_restore_entry,
            OutboxKind.DELETE_ENTRY to R.string.sync_kind_delete_entry,
        )
        for ((kind, label) in kinds) {
            assertEquals(kind.wire, expected(label, ENTRY_A, R.string.sync_error_server), deadItemText(resources, deadRow(1, kind, ENTRY_A)))
        }
    }

    @Test
    fun theKnownErrorCodesHaveTheirOwnTextsAndEverythingElseIsAServerError() {
        val errors = mapOf(
            "QUOTA_EXCEEDED" to R.string.sync_error_quota,
            "FEATURE_DISABLED" to R.string.sync_error_disabled,
            "VALIDATION_FAILED" to R.string.sync_error_rejected,
            "NETWORK" to R.string.sync_error_network,
            "UNKNOWN" to R.string.sync_error_server,
            "MISSING_FILE" to R.string.sync_error_server,
            "NOT_FOUND" to R.string.sync_error_server,
        )
        for ((code, label) in errors) {
            assertEquals(code, expected(R.string.sync_kind_add_media, ENTRY_B, label), deadItemText(resources, deadRow(2, OutboxKind.ADD_MEDIA, ENTRY_B, code)))
        }
    }

    @Test
    fun anUnknownKindShowsItsWireNameAndAMissingEntryIdIsLeftEmpty() {
        assertEquals("shareEntry" + dash + ENTRY_A + dash + text(R.string.sync_error_server), deadItemText(resources, outboxRow(3, "shareEntry", ENTRY_A)))
        assertEquals(text(R.string.sync_kind_update_entry_meta) + dash + dash + text(R.string.sync_error_network), deadItemText(resources, outboxRow(4, OutboxKind.UPDATE_ENTRY_META.wire, null, error = "NETWORK")))
    }
}

@RunWith(RobolectricTestRunner::class)
class SyncSettingsViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val h = SettingsHarness()

    private class Opened(val vm: SyncSettingsViewModel, val events: MutableList<SettingsEvent>) {
        val state: SyncSettingsState get() = vm.state.value
    }

    private fun TestScope.open(): Opened {
        val vm = SyncSettingsViewModel(h.deps)
        val events = mutableListOf<SettingsEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events += it } }
        runCurrent()
        return Opened(vm, events)
    }

    private val deadEdit = deadRow(7, OutboxKind.UPDATE_ENTRY_META, ENTRY_A, "VALIDATION_FAILED")
    private val deadAdd = deadRow(9, OutboxKind.ADD_MEDIA, ENTRY_B, "QUOTA_EXCEEDED")
    private val queued = outboxRow(8, OutboxKind.CREATE_ENTRY.wire, ENTRY_B, OutboxState.QUEUED)

    @Test
    fun theScreenShowsTheSyncStatusAndOnlyTheDeadOutboxRowsInOrder() = runTest {
        h.sync.statusState.value = idleStatus(queued = 1, dead = 2, waitingForWifi = 3, lastSuccessAt = LAST_SUCCESS_MS)
        h.sync.rows += listOf(deadEdit, queued, deadAdd)
        val o = open()
        assertEquals(idleStatus(queued = 1, dead = 2, waitingForWifi = 3, lastSuccessAt = LAST_SUCCESS_MS), o.state.status)
        assertEquals(listOf(deadEdit, deadAdd), o.state.dead)
        assertFalse(o.state.local)
        assertFalse(o.state.syncing)
    }

    @Test
    fun theDeadListReloadsWhenTheDeadOrQueuedCountChanges() = runTest {
        val o = open()
        assertEquals(emptyList<Any>(), o.state.dead)
        h.sync.rows += deadEdit
        h.sync.statusState.value = idleStatus(dead = 1)
        runCurrent()
        assertEquals(listOf(deadEdit), o.state.dead)
        h.sync.rows += deadAdd
        h.sync.statusState.value = idleStatus(queued = 1, dead = 1)
        runCurrent()
        assertEquals(listOf(deadEdit, deadAdd), o.state.dead)
    }

    @Test
    fun syncNowRunsOnePassAndIsBusyUntilItEnds() = runTest {
        val gate = CompletableDeferred<Unit>()
        h.sync.syncGate = gate
        val o = open()
        o.vm.onAction(SyncSettingsAction.SyncNow)
        runCurrent()
        assertTrue(o.state.syncing)
        assertEquals(1, h.sync.calls.count { it == FakeSettingsSync.SYNC_NOW })
        gate.complete(Unit)
        runCurrent()
        assertFalse(o.state.syncing)
    }

    @Test
    fun aFailedSyncNowJustEndsTheBusyState() = runTest {
        h.sync.syncFailure = NetworkError()
        val o = open()
        o.vm.onAction(SyncSettingsAction.SyncNow)
        runCurrent()
        assertFalse(o.state.syncing)
        assertEquals(listOf<SettingsEvent>(), o.events)
    }

    @Test
    fun retryFailedRequeuesTheDeadRowsAndReloadsTheList() = runTest {
        h.sync.statusState.value = idleStatus(dead = 2)
        h.sync.rows += listOf(deadEdit, deadAdd)
        val o = open()
        o.vm.onAction(SyncSettingsAction.RetryFailed)
        runCurrent()
        assertTrue(FakeSettingsSync.RETRY_DEAD in h.sync.calls)
        assertEquals(FakeSettingsSync.OUTBOX, h.sync.calls.last())
        assertEquals(emptyList<Any>(), o.state.dead)
    }

    @Test
    fun aLocalJournalOffersToConnectAServer() = runTest {
        h.account.modeState.value = AccountMode.LOCAL
        val o = open()
        assertTrue(o.state.local)
        o.vm.onAction(SyncSettingsAction.Connect)
        o.vm.onAction(SyncSettingsAction.Back)
        runCurrent()
        assertEquals(listOf(SettingsEvent.Open(Route.SettingsConnect), SettingsEvent.Back), o.events)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h1200dp-mdpi")
class SyncSettingsContentTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val actions = mutableListOf<SyncSettingsAction>()

    private val deadEdit = deadRow(7, OutboxKind.UPDATE_ENTRY_META, ENTRY_A, "VALIDATION_FAILED")
    private val deadAdd = deadRow(9, OutboxKind.ADD_MEDIA, ENTRY_B, "QUOTA_EXCEEDED")

    private fun show(state: SyncSettingsState) {
        compose.show { SyncSettingsContent(state = state, onAction = { actions += it }) }
    }

    private fun server(status: SyncStatus = idleStatus(), syncing: Boolean = false, dead: List<OutboxRow> = emptyList()) =
        SyncSettingsState(local = false, status = status, syncing = syncing, dead = dead)

    @Test
    fun theCountsAndTheLastSyncTimeUseTheV031Texts() {
        show(server(idleStatus(queued = 3, dead = 2, waitingForWifi = 1, lastSuccessAt = LAST_SUCCESS_MS)))
        assertTrue(compose.exists(TestTags.Settings.SYNC_SCREEN))
        assertTrue(compose.showsText(text(R.string.settings_row_sync)))
        compose.onNodeWithTag(TestTags.Settings.LAST_SYNCED, useUnmergedTree = true).assertTextEquals(text(R.string.settings_last_synced, "2026-11-18 11:06"))
        compose.onNodeWithTag(TestTags.Settings.WAITING, useUnmergedTree = true).assertTextEquals(text(R.string.settings_waiting, 3))
        compose.onNodeWithTag(TestTags.Settings.FAILED_COUNT, useUnmergedTree = true).assertTextEquals(text(R.string.settings_failed, 2))
        compose.onNodeWithTag(TestTags.Settings.WAITING_WIFI, useUnmergedTree = true).assertTextEquals(text(R.string.settings_waiting_wifi, 1))
    }

    @Test
    fun neverSyncedSaysSo() {
        show(server())
        compose.onNodeWithTag(TestTags.Settings.LAST_SYNCED, useUnmergedTree = true).assertTextEquals(text(R.string.settings_not_synced))
        compose.onNodeWithTag(TestTags.Settings.WAITING, useUnmergedTree = true).assertTextEquals(text(R.string.settings_waiting, 0))
    }

    @Test
    fun syncNowIsAvailableAndBusyWhileAPassRuns() {
        show(server())
        compose.onNodeWithTag(TestTags.Settings.SYNC_NOW).assert(labeled(text(R.string.settings_sync_now))).assertIsEnabled().performClick()
        compose.waitForIdle()
        assertEquals(listOf<SyncSettingsAction>(SyncSettingsAction.SyncNow), actions)
    }

    @Test
    fun syncNowCannotBeTappedAgainWhileItRuns() {
        show(server(syncing = true))
        compose.onNodeWithTag(TestTags.Settings.SYNC_NOW).assertIsNotEnabled()
    }

    @Test
    fun retryFailedAndTheDeadListShowOnlyWithDeadItems() {
        show(server(idleStatus(dead = 2), dead = listOf(deadEdit, deadAdd)))
        compose.onNodeWithTag(TestTags.Settings.RETRY_FAILED).performScrollTo().assert(labeled(text(R.string.settings_retry_failed))).performClick()
        compose.waitForIdle()
        assertEquals(listOf<SyncSettingsAction>(SyncSettingsAction.RetryFailed), actions)
        compose.onNodeWithTag(listItem(SettingsTestTags.DEAD_ITEM, 0), useUnmergedTree = true).performScrollTo().assertTextEquals(deadItemText(resources, deadEdit))
        compose.onNodeWithTag(listItem(SettingsTestTags.DEAD_ITEM, 1), useUnmergedTree = true).performScrollTo().assertTextEquals(deadItemText(resources, deadAdd))
        assertFalse(compose.exists(listItem(SettingsTestTags.DEAD_ITEM, 2)))
    }

    @Test
    fun withoutDeadItemsThereIsNoRetryButton() {
        show(server(idleStatus(queued = 2)))
        assertFalse(compose.exists(TestTags.Settings.RETRY_FAILED))
        assertFalse(compose.exists(listItem(SettingsTestTags.DEAD_ITEM, 0)))
    }

    @Test
    fun aLocalJournalShowsOnlyTheLocalNoteAndTheConnectButton() {
        show(SyncSettingsState(local = true, status = idleStatus(), syncing = false, dead = emptyList()))
        compose.onNodeWithTag(TestTags.Settings.SYNC_LOCAL_ONLY, useUnmergedTree = true).assertTextEquals(text(R.string.sync_local_only))
        assertFalse(compose.exists(TestTags.Settings.LAST_SYNCED))
        assertFalse(compose.exists(TestTags.Settings.SYNC_NOW))
        assertFalse(compose.exists(TestTags.Settings.WAITING))
        compose.onNodeWithTag(TestTags.Settings.SYNC_CONNECT).assert(labeled(text(R.string.settings_connect))).performClick()
        compose.onNodeWithTag(TestTags.Settings.BACK).performClick()
        compose.waitForIdle()
        assertEquals(listOf(SyncSettingsAction.Connect, SyncSettingsAction.Back), actions)
    }
}
