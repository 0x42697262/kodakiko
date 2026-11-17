@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.kodakiko.ui.feature.settings

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import dev.kodakiko.contract.AdminInviteCreateRequest
import dev.kodakiko.contract.AdminUserPatchRequest
import dev.kodakiko.contract.ErrorCode
import dev.kodakiko.contract.OptionalValue
import dev.kodakiko.contract.UserRole
import dev.kodakiko.contract.UserStatus
import dev.kodakiko.data.net.ApiError
import dev.kodakiko.data.net.NetworkError
import dev.kodakiko.ui.core.R
import dev.kodakiko.ui.core.TestTags
import dev.kodakiko.ui.core.TestTags.listItem
import dev.kodakiko.ui.core.format.formatBytes
import dev.kodakiko.ui.core.navigation.Route
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private val INVITE_TOKEN = Regex("#(kdk_i_[A-Za-z0-9_-]+)")

private fun refusal(reason: String): ApiError =
    ApiError(status = 409, code = ErrorCode.FORBIDDEN, details = JsonObject(mapOf("reason" to JsonPrimitive(reason))), requestId = null)

class InviteStateTest {
    @Test
    fun revokedWinsThenRedeemedThenExpiredElseOpen() {
        val now = NOW_MS
        assertEquals(InviteState.REVOKED, inviteState(invite("I1", revokedAt = "2026-11-18T00:00:00.000Z", redeemedAt = "2026-11-17T00:00:00.000Z"), now))
        assertEquals(InviteState.REDEEMED, inviteState(invite("I2", redeemedAt = "2026-11-17T00:00:00.000Z", redeemedBy = "eve", expiresAt = "2020-01-01T00:00:00.000Z"), now))
        assertEquals(InviteState.EXPIRED, inviteState(invite("I3", expiresAt = "2026-11-18T11:06:40.000Z"), now))
        assertEquals(InviteState.OPEN, inviteState(invite("I4", expiresAt = "2026-11-18T11:06:40.001Z"), now))
    }
}

@RunWith(RobolectricTestRunner::class)
class AdminViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val h = SettingsHarness().apply { account.admin = true }

    private class Opened(val vm: AdminViewModel, val events: MutableList<SettingsEvent>) {
        val state: AdminState get() = vm.state.value
    }

    private fun TestScope.open(): Opened {
        val vm = AdminViewModel(h.adminDeps)
        val events = mutableListOf<SettingsEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events += it } }
        runCurrent()
        return Opened(vm, events)
    }

    @Test
    fun aMemberSeesNotAdminAndNothingIsLoaded() = runTest {
        h.account.admin = false
        val o = open()
        assertEquals(false, o.state.admin)
        assertEquals(emptyList<String>(), h.admin.calls)
        h.account.adminFailure = NetworkError()
        assertEquals(false, open().state.admin)
    }

    @Test
    fun anAdminStartsOnPeopleWithEveryPageOfUsers() = runTest {
        h.admin.pageSize = 1
        val o = open()
        assertEquals(true, o.state.admin)
        assertEquals(AdminSegment.PEOPLE, o.state.segment)
        assertEquals(listOf(null, "1"), h.admin.cursors)
        assertEquals(listOf(USERNAME, "bob"), o.state.users.map { it.user.username })
        assertEquals(10 * GB, o.state.defaultQuotaBytes)
        assertNull(o.state.peopleError)
        assertNull(o.state.editing)
    }

    @Test
    fun withoutConfigTheDefaultQuotaIsTheV031Fallback() = runTest {
        h.config.config = null
        assertEquals(DEFAULT_QUOTA_FALLBACK, open().state.defaultQuotaBytes)
    }

    @Test
    fun aFailedLoadShowsCouldNotSave() = runTest {
        h.admin.listFailure = NetworkError()
        assertEquals(AdminError.FAILED, open().state.peopleError)
    }

    @Test
    fun openingAUserFillsTheSheetFromTheirQuotaAndRole() = runTest {
        val o = open()
        o.vm.onAction(AdminAction.OpenUser(h.admin.users[1]))
        runCurrent()
        assertEquals(AdminUserEditor(user = h.admin.users[1], useDefault = true, quota = "", role = UserRole.MEMBER, error = null), o.state.editing)
        o.vm.onAction(AdminAction.OpenUser(h.admin.users[0]))
        runCurrent()
        assertEquals(AdminUserEditor(user = h.admin.users[0], useDefault = false, quota = "10", role = UserRole.ADMIN, error = null), o.state.editing)
        o.vm.onAction(AdminAction.CloseUser)
        runCurrent()
        assertNull(o.state.editing)
    }

    @Test
    fun savingSendsTheQuotaInGibibytesAndTheRoleThenReloads() = runTest {
        val o = open()
        val bob = h.admin.users[1]
        o.vm.onAction(AdminAction.OpenUser(bob))
        o.vm.onAction(AdminAction.SetUseDefault(false))
        o.vm.onAction(AdminAction.SetQuota("1.5"))
        o.vm.onAction(AdminAction.SetRole(UserRole.ADMIN))
        o.vm.onAction(AdminAction.SaveUser)
        runCurrent()
        assertEquals(listOf("U2" to AdminUserPatchRequest(quotaBytes = OptionalValue.Present(1_610_612_736L), role = UserRole.ADMIN)), h.admin.patches)
        assertNull(o.state.editing)
        assertEquals(2, h.admin.calls.count { it == FakeAdminService.LIST_USERS })
        assertEquals(1_610_612_736L, o.state.users[1].user.quotaBytes)
    }

    @Test
    fun theServerDefaultSendsAnExplicitNullQuota() = runTest {
        val o = open()
        o.vm.onAction(AdminAction.OpenUser(h.admin.users[0]))
        o.vm.onAction(AdminAction.SetUseDefault(true))
        o.vm.onAction(AdminAction.SaveUser)
        runCurrent()
        assertEquals(listOf("U1" to AdminUserPatchRequest(quotaBytes = OptionalValue.Present(null), role = UserRole.ADMIN)), h.admin.patches)
    }

    @Test
    fun refusalsKeepTheSheetOpenWithTheV031Reason() = runTest {
        val o = open()
        for ((failure, expected) in listOf(
            refusal("selfDemotion") to AdminError.SELF_DEMOTION,
            refusal("lastAdmin") to AdminError.LAST_ADMIN,
            refusal("other") to AdminError.FAILED,
            NetworkError() to AdminError.FAILED,
        )) {
            h.admin.patchFailure = failure
            o.vm.onAction(AdminAction.OpenUser(h.admin.users[0]))
            o.vm.onAction(AdminAction.SetRole(UserRole.MEMBER))
            o.vm.onAction(AdminAction.SaveUser)
            runCurrent()
            assertEquals(expected, o.state.editing?.error)
        }
    }

    @Test
    fun invitesLoadOnTheirSegmentAndANewInviteShowsItsUrlThenShares() = runTest {
        h.admin.invites += invite("I9", expiresAt = "2026-12-01T00:00:00.000Z")
        val o = open()
        o.vm.onAction(AdminAction.SelectSegment(AdminSegment.INVITES))
        runCurrent()
        assertEquals(AdminSegment.INVITES, o.state.segment)
        assertEquals(listOf("I9"), o.state.invites.map { it.id })
        o.vm.onAction(AdminAction.NewInvite)
        runCurrent()
        assertEquals(AdminInviteEditor(role = UserRole.MEMBER, days = "7", url = null), o.state.inviteEditor)
        o.vm.onAction(AdminAction.SetInviteRole(UserRole.ADMIN))
        o.vm.onAction(AdminAction.SetInviteDays("30"))
        o.vm.onAction(AdminAction.CreateInvite)
        runCurrent()
        assertEquals(listOf(AdminInviteCreateRequest(UserRole.ADMIN, 30)), h.admin.created)
        assertEquals(h.admin.nextInviteUrl, o.state.inviteEditor?.url)
        assertEquals(2, h.admin.calls.count { it == FakeAdminService.LIST_INVITES })
        assertEquals(2, o.state.invites.size)
        o.vm.onAction(AdminAction.ShareInvite)
        assertEquals(listOf(h.admin.nextInviteUrl), h.share.shared)
        o.vm.onAction(AdminAction.CloseInvite)
        runCurrent()
        assertNull(o.state.inviteEditor)
        o.vm.onAction(AdminAction.NewInvite)
        runCurrent()
        assertNull(o.state.inviteEditor?.url)
    }

    @Test
    fun revokingReloadsAndFailuresShowCouldNotSave() = runTest {
        h.admin.invites += invite("I1")
        val o = open()
        o.vm.onAction(AdminAction.SelectSegment(AdminSegment.INVITES))
        runCurrent()
        o.vm.onAction(AdminAction.Revoke(o.state.invites[0]))
        runCurrent()
        assertEquals(listOf("I1"), h.admin.revoked)
        assertEquals(InviteState.REVOKED, inviteState(o.state.invites[0], NOW_MS))
        assertNull(o.state.invitesError)
        h.admin.inviteFailure = NetworkError()
        o.vm.onAction(AdminAction.NewInvite)
        o.vm.onAction(AdminAction.CreateInvite)
        runCurrent()
        assertEquals(AdminError.FAILED, o.state.invitesError)
    }

    @Test
    fun theServerSegmentLoadsTheStatsAndAFailureLeavesThemEmpty() = runTest {
        val o = open()
        o.vm.onAction(AdminAction.SelectSegment(AdminSegment.SERVER))
        runCurrent()
        assertEquals(h.admin.stats, o.state.stats)
        assertEquals(publicConfig(), o.state.config)
        h.admin.statsFailure = NetworkError()
        val again = open()
        again.vm.onAction(AdminAction.SelectSegment(AdminSegment.SERVER))
        runCurrent()
        assertNull(again.state.stats)
    }

    @Test
    fun theU19PathInvitesThenSetsTheNewcomersQuota() = runTest {
        val o = open()
        o.vm.onAction(AdminAction.SelectSegment(AdminSegment.INVITES))
        o.vm.onAction(AdminAction.NewInvite)
        o.vm.onAction(AdminAction.CreateInvite)
        runCurrent()
        val url = o.state.inviteEditor?.url.orEmpty()
        assertTrue(INVITE_TOKEN.containsMatchIn(url))
        h.admin.users += adminUser("U3", "newcomer", usedBytes = 0, lastActiveAt = null)
        o.vm.onAction(AdminAction.CloseInvite)
        o.vm.onAction(AdminAction.SelectSegment(AdminSegment.SERVER))
        o.vm.onAction(AdminAction.SelectSegment(AdminSegment.PEOPLE))
        runCurrent()
        val newcomer = o.state.users.single { it.user.username == "newcomer" }
        o.vm.onAction(AdminAction.OpenUser(newcomer))
        o.vm.onAction(AdminAction.SetUseDefault(false))
        o.vm.onAction(AdminAction.SetQuota("1"))
        o.vm.onAction(AdminAction.SaveUser)
        runCurrent()
        assertEquals(GB, o.state.users.single { it.user.username == "newcomer" }.user.quotaBytes)
    }

    @Test
    fun backLeavesTheScreen() = runTest {
        val o = open()
        o.vm.onAction(AdminAction.Back)
        runCurrent()
        assertEquals(listOf<SettingsEvent>(SettingsEvent.Back), o.events)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w320dp-h1600dp-mdpi")
class AdminScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val h = SettingsHarness().apply { account.admin = true }
    private val navigator = RecordingNavigator(listOf(Route.Settings, Route.SettingsAdmin))

    private fun await(tag: String) {
        compose.waitUntil(WAIT_MS) { compose.exists(tag) }
    }

    private fun click(tag: String) {
        await(tag)
        compose.onNodeWithTag(tag).performScrollTo().performClick()
        compose.waitForIdle()
    }

    private fun open() {
        compose.showWith(navigator) { AdminScreen(viewModel = AdminViewModel(h.adminDeps)) }
        await(TestTags.Admin.SCREEN)
    }

    private fun rowWith(text: String): Int = (0 until h.admin.users.size).first { position ->
        compose.onAllNodes(hasTestTag(listItem(TestTags.Admin.USER_ROW, position)) and contains(text)).fetchSemanticsNodes().isNotEmpty()
    }

    @Test
    fun aMemberIsToldTheyAreNotAnAdmin() {
        h.account.admin = false
        open()
        await(TestTags.Admin.NOT_ADMIN)
        compose.onNodeWithTag(TestTags.Admin.NOT_ADMIN, useUnmergedTree = true).assert(contains(text(R.string.admin_not_admin)))
        assertFalse(compose.exists(TestTags.Admin.SEGMENT + ".people"))
    }

    @Test
    fun peopleListsEveryUserWithTheV031LineAndTags() {
        h.admin.users += adminUser("U3", "carol", usedBytes = 0, status = UserStatus.PENDING_DELETION)
        open()
        await(listItem(TestTags.Admin.USER_ROW, 2))
        assertTrue(compose.showsText(text(R.string.admin_title)))
        for ((segment, label) in listOf("people" to R.string.admin_people, "invites" to R.string.admin_invites, "server" to R.string.admin_server)) {
            compose.onNodeWithTag(TestTags.Admin.SEGMENT + "." + segment).assert(labeled(text(label)))
        }
        compose.onNodeWithTag(TestTags.Admin.SEGMENT + ".people").assert(selected())
        compose.onNodeWithTag(listItem(TestTags.Admin.USER_ROW, 0))
            .assert(contains(USERNAME))
            .assert(contains(text(R.string.admin_user_line, "admin", formatBytes(GB), formatBytes(10 * GB), "2026-11-10")))
        compose.onNodeWithTag(listItem(TestTags.Admin.USER_ROW, 1))
            .assert(contains("bob"))
            .assert(contains(text(R.string.admin_user_line, "member", formatBytes(120 * GB), formatBytes(10 * GB), text(R.string.admin_never))))
            .assert(contains(text(R.string.admin_over_quota)))
        compose.onNodeWithTag(listItem(TestTags.Admin.USER_ROW, 2)).assert(contains(text(R.string.admin_pending_deletion)))
        assertFalse(compose.exists(TestTags.Admin.USER_SHEET))
    }

    @Test
    fun theUserSheetEditsQuotaAndRoleLikeU19() {
        h.admin.users += adminUser("U3", "newcomer", usedBytes = 0, lastActiveAt = null)
        open()
        await(listItem(TestTags.Admin.USER_ROW, 2))
        click(TestTags.Admin.SEGMENT + ".server")
        click(TestTags.Admin.SEGMENT + ".people")
        await(listItem(TestTags.Admin.USER_ROW, 2))
        val row = listItem(TestTags.Admin.USER_ROW, rowWith("newcomer"))
        click(row)
        await(TestTags.Admin.USER_SHEET)
        compose.onNodeWithTag(TestTags.Admin.USE_DEFAULT).assert(checked()).assert(contains(text(R.string.admin_use_default)))
        assertFalse(compose.exists(TestTags.Admin.QUOTA))
        compose.onNodeWithTag(TestTags.Admin.ROLE_MEMBER).assert(labeled(text(R.string.admin_role_member))).assert(selected())
        compose.onNodeWithTag(TestTags.Admin.ROLE_ADMIN).assert(labeled(text(R.string.admin_role_admin)))
        click(TestTags.Admin.USE_DEFAULT)
        await(TestTags.Admin.QUOTA)
        compose.onNodeWithTag(TestTags.Admin.QUOTA).assert(labeled(text(R.string.admin_quota_field)))
        compose.onNodeWithTag(TestTags.Admin.QUOTA).performTextInput("1")
        click(TestTags.Admin.SAVE)
        compose.waitUntil(WAIT_MS) { !compose.exists(TestTags.Admin.USER_SHEET) }
        compose.waitUntil(WAIT_MS) {
            compose.onAllNodes(hasTestTag(row) and contains("newcomer") and contains("of " + formatBytes(GB))).fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(listOf("U3" to AdminUserPatchRequest(quotaBytes = OptionalValue.Present(GB), role = UserRole.MEMBER)), h.admin.patches)
    }

    @Test
    fun aRefusalShowsTheV031TextInTheSheet() {
        h.admin.patchFailure = refusal("selfDemotion")
        open()
        click(listItem(TestTags.Admin.USER_ROW, 0))
        await(TestTags.Admin.USER_SHEET)
        click(TestTags.Admin.ROLE_MEMBER)
        click(TestTags.Admin.SAVE)
        await(TestTags.Admin.ERROR)
        compose.onNodeWithTag(TestTags.Admin.ERROR).assert(contains(text(R.string.admin_self_demotion)))
        assertTrue(compose.exists(TestTags.Admin.USER_SHEET))
    }

    @Test
    fun aNewInviteShowsTheUrlTheNoteAndSharesIt() {
        open()
        click(TestTags.Admin.SEGMENT + ".invites")
        await(TestTags.Admin.NEW_INVITE)
        compose.onNodeWithTag(TestTags.Admin.NEW_INVITE).assert(labeled(text(R.string.admin_new_invite)))
        click(TestTags.Admin.NEW_INVITE)
        await(TestTags.Admin.INVITE_SHEET)
        compose.onNodeWithTag(TestTags.Admin.INVITE_DAYS).assert(labeled(text(R.string.admin_expiry_days))).assert(contains("7"))
        compose.onNodeWithTag(TestTags.Admin.ROLE_MEMBER).assert(selected())
        compose.onNodeWithTag(TestTags.Admin.CREATE_INVITE).assert(labeled(text(R.string.admin_create)))
        compose.onNodeWithTag(TestTags.Admin.INVITE_DAYS).performTextClearance()
        compose.onNodeWithTag(TestTags.Admin.INVITE_DAYS).performTextInput("14")
        click(TestTags.Admin.CREATE_INVITE)
        await(TestTags.Admin.INVITE_URL)
        compose.onNodeWithTag(TestTags.Admin.INVITE_URL, useUnmergedTree = true).assertTextEquals(h.admin.nextInviteUrl)
        assertTrue(compose.showsText(text(R.string.admin_invite_note)))
        assertEquals(listOf(AdminInviteCreateRequest(UserRole.MEMBER, 14)), h.admin.created)
        compose.onNodeWithTag(TestTags.Admin.COPY_INVITE).assert(labeled(text(R.string.links_share)))
        click(TestTags.Admin.COPY_INVITE)
        assertEquals(listOf(h.admin.nextInviteUrl), h.share.shared)
    }

    @Test
    fun inviteRowsShowRoleStateAndExpiryAndOnlyOpenOnesCanBeRevoked() {
        h.admin.invites += invite("I1", role = UserRole.ADMIN, expiresAt = "2026-11-25T00:00:00.000Z")
        h.admin.invites += invite("I2", redeemedAt = "2026-11-17T00:00:00.000Z", redeemedBy = "eve")
        h.admin.invites += invite("I3", expiresAt = "2026-11-01T00:00:00.000Z")
        h.admin.invites += invite("I4", revokedAt = "2026-11-02T00:00:00.000Z")
        open()
        click(TestTags.Admin.SEGMENT + ".invites")
        await(listItem(TestTags.Admin.INVITE_ROW, 3))
        compose.onNodeWithTag(listItem(TestTags.Admin.INVITE_ROW, 0)).assert(contains("admin \u00b7 " + text(R.string.admin_invite_open) + " \u00b7 2026-11-25"))
        compose.onNodeWithTag(listItem(TestTags.Admin.INVITE_ROW, 1)).assert(contains("member \u00b7 " + text(R.string.admin_invite_redeemed, "eve") + " \u00b7 2026-11-25"))
        compose.onNodeWithTag(listItem(TestTags.Admin.INVITE_ROW, 2)).assert(contains("member \u00b7 " + text(R.string.admin_invite_expired) + " \u00b7 2026-11-01"))
        compose.onNodeWithTag(listItem(TestTags.Admin.INVITE_ROW, 3)).assert(contains("member \u00b7 " + text(R.string.admin_invite_revoked) + " \u00b7 2026-11-25"))
        assertTrue(compose.exists(listItem(TestTags.Admin.REVOKE, 0)))
        for (position in 1..3) assertFalse(compose.exists(listItem(TestTags.Admin.REVOKE, position)))
        compose.onNodeWithTag(listItem(TestTags.Admin.REVOKE, 0)).assert(labeled(text(R.string.admin_revoke)))
        click(listItem(TestTags.Admin.REVOKE, 0))
        compose.waitUntil(WAIT_MS) { !compose.exists(listItem(TestTags.Admin.REVOKE, 0)) }
        assertEquals(listOf("I1"), h.admin.revoked)
    }

    @Test
    fun theServerSegmentListsTheV031Counts() {
        open()
        click(TestTags.Admin.SEGMENT + ".server")
        await(listItem(TestTags.Admin.STAT, 10))
        val expected = listOf(
            R.string.admin_stat_users to "12",
            R.string.admin_stat_active to "7",
            R.string.admin_stat_pending to "1",
            R.string.admin_stat_collections to "30",
            R.string.admin_stat_entries to "4200",
            R.string.admin_stat_objects to "9001",
            R.string.admin_stat_bytes to formatBytes(5 * GB),
            R.string.admin_stat_quota to formatBytes(100 * GB),
            R.string.admin_version to "0.3.1",
            R.string.admin_flag_sharing to text(R.string.admin_on),
            R.string.admin_flag_links to text(R.string.admin_on),
        )
        for ((position, row) in expected.withIndex()) {
            compose.onNodeWithTag(listItem(TestTags.Admin.STAT, position)).assert(contains(text(row.first))).assert(contains(row.second))
        }
        assertFalse(compose.exists(listItem(TestTags.Admin.STAT, 11)))
    }

    @Test
    fun anUnallocatedQuotaAndUnknownConfigShowDashesAndOff() {
        h.admin.stats = h.admin.stats.copy(quotaBytesAllocated = null)
        h.config.config = null
        open()
        click(TestTags.Admin.SEGMENT + ".server")
        await(listItem(TestTags.Admin.STAT, 10))
        compose.onNodeWithTag(listItem(TestTags.Admin.STAT, 7)).assert(contains("\u2014"))
        compose.onNodeWithTag(listItem(TestTags.Admin.STAT, 8)).assert(contains("\u2014"))
        compose.onNodeWithTag(listItem(TestTags.Admin.STAT, 9)).assert(contains(text(R.string.admin_off)))
        compose.onNodeWithTag(listItem(TestTags.Admin.STAT, 10)).assert(contains(text(R.string.admin_off)))
    }

    @Test
    fun theBackArrowUsesTheV031Id() {
        open()
        click(TestTags.Shared.BACK)
        assertEquals(1, navigator.pops)
    }

    @Test
    fun theSettingsRowOpensAdminForAnAdmin() {
        val host = SettingsTestHost(h, listOf(Route.More, Route.Settings))
        compose.show { host.Content() }
        click(TestTags.Settings.ROW_ADMIN)
        await(TestTags.Admin.SCREEN)
        assertEquals(listOf<Route>(Route.SettingsAdmin), host.pushed)
        await(listItem(TestTags.Admin.USER_ROW, 0))
    }

    private companion object {
        const val WAIT_MS = 5_000L
    }
}
