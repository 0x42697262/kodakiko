package dev.kodakiko.app.navigation

import androidx.activity.ComponentDialog
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import dev.kodakiko.app.FakeKodakikoServer
import dev.kodakiko.app.ServerAdminInviteV031App
import dev.kodakiko.app.ServerAdminV031App
import dev.kodakiko.app.ServerFixture
import dev.kodakiko.app.ServerV031App
import dev.kodakiko.app.UpdatedV031App
import dev.kodakiko.app.V031Fixture
import dev.kodakiko.contract.Routes
import dev.kodakiko.contract.UserRole
import dev.kodakiko.ui.core.R
import dev.kodakiko.ui.core.TestTags
import dev.kodakiko.ui.core.TestTags.listItem
import dev.kodakiko.ui.core.format.formatBytes
import dev.kodakiko.ui.core.navigation.Route
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
class AdvancedAboutAdminWiringTest : AppWiringHarness() {
    private fun state(value: ToggleableState): SemanticsMatcher {
        val matcher = SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, value)
        return matcher or hasAnyDescendant(matcher)
    }

    private fun textOf(tag: String): String =
        compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().config[SemanticsProperties.Text].joinToString("") { it.text }

    private fun openAdvanced() {
        unlockToGallery()
        push(Route.Settings)
        click(TestTags.Settings.ROW_ADVANCED)
        awaitTag(TestTags.Advanced.SCREEN)
    }

    @Test
    @Config(application = UpdatedV031App::class)
    fun rebuildIndexRebuildsTheRealIndexAndReportsItsEntries() {
        openAdvanced()
        click(TestTags.Advanced.REBUILD)
        awaitTag(TestTags.Advanced.MESSAGE, UNLOCK_WAIT_MS)
        assertTrue(textOf(TestTags.Advanced.MESSAGE).matches(Regex("Rebuilt: 1 entries in \\d+ ms")))
        back()
        awaitTag(TestTags.Settings.SCREEN)
        back()
        awaitTag(listItem(TestTags.Gallery.TILE, 0))
    }

    @Test
    @Config(application = UpdatedV031App::class)
    fun technicalDetailsAndResetUseTheStoredDeviceSettings() {
        openAdvanced()
        compose.onNodeWithTag(TestTags.Settings.TECHNICAL_DETAILS).assert(state(ToggleableState.Off))
        click(TestTags.Settings.TECHNICAL_DETAILS)
        compose.onNodeWithTag(TestTags.Settings.TECHNICAL_DETAILS).assert(state(ToggleableState.On))
        back()
        click(TestTags.Settings.ROW_ADVANCED)
        awaitTag(TestTags.Settings.TECHNICAL_DETAILS)
        compose.onNodeWithTag(TestTags.Settings.TECHNICAL_DETAILS).assert(state(ToggleableState.On))
        click(TestTags.Advanced.RESET)
        tap(TestTags.Advanced.RESET_CONFIRM)
        awaitTag(TestTags.Advanced.MESSAGE)
        compose.onNodeWithTag(TestTags.Advanced.MESSAGE, useUnmergedTree = true).assertTextEquals(app.getString(R.string.advanced_reset_done))
        compose.onNodeWithTag(TestTags.Settings.TECHNICAL_DETAILS).assert(state(ToggleableState.Off))
    }

    @Test
    @Config(application = UpdatedV031App::class)
    fun debugInfoShowsTheV031JsonOfThisDevice() {
        openAdvanced()
        click(TestTags.Advanced.DEBUG)
        awaitTag(TestTags.Advanced.DEBUG_TEXT)
        val json = textOf(TestTags.Advanced.DEBUG_TEXT)
        for (part in listOf("\"platform\": \"android\"", "\"mode\": \"local\"", "\"server\": null", "\"settings\": {")) assertTrue(part, json.contains(part))
    }

    @Test
    @Config(application = UpdatedV031App::class)
    fun diagnosticsRunsEveryBundledVector() {
        unlockToGallery()
        push(Route.SettingsDiagnostics)
        click(TestTags.Diagnostics.RUN_VECTORS)
        compose.waitUntil(UNLOCK_WAIT_MS) {
            exists(listItem(TestTags.Diagnostics.LINE, 0)) && textOf(listItem(TestTags.Diagnostics.LINE, 0)) != app.getString(R.string.diagnostics_running)
        }
        assertEquals(app.getString(R.string.diagnostics_vector_summary, VECTORS, VECTORS), textOf(listItem(TestTags.Diagnostics.LINE, 0)))
        assertTrue(exists(listItem(TestTags.Diagnostics.LINE, VECTORS)))
    }

    @Test
    @Config(application = ServerV031App::class)
    fun aboutShowsThisBuildsVersionTheServerVersionAndCreditsMihon() {
        unlockToGallery()
        tap(TestTags.Tabs.MORE)
        click(TestTags.More.ABOUT)
        awaitTag(TestTags.Settings.ABOUT_SCREEN)
        val version = app.packageManager.getPackageInfo(app.packageName, 0).versionName
        compose.onNodeWithTag(TestTags.Settings.APP_VERSION, useUnmergedTree = true).assertTextEquals(version!!)
        compose.onNodeWithTag(TestTags.Settings.SERVER_VERSION, useUnmergedTree = true).assertTextEquals(FakeKodakikoServer.config(true).serverVersion)
        awaitTag(TestTags.Settings.MIHON_ATTRIBUTION)
        compose.onNodeWithTag(TestTags.Settings.MIHON_ATTRIBUTION).performScrollTo().assert(contains("https://github.com/mihonapp/mihon"))
    }

    @Test
    @Config(application = ServerAdminV031App::class)
    fun anAdminGetsTheAdminRowAndPeopleComeFromTheServer() {
        unlockToGallery()
        push(Route.Settings)
        awaitTag(TestTags.Settings.ROW_ADMIN, SYNC_WAIT_MS)
        click(TestTags.Settings.ROW_ADMIN)
        awaitTag(TestTags.Admin.SCREEN)
        awaitTag(listItem(TestTags.Admin.USER_ROW, 1), SYNC_WAIT_MS)
        compose.onNodeWithTag(listItem(TestTags.Admin.USER_ROW, 0))
            .assert(contains(V031Fixture.USERNAME))
            .assert(contains(app.getString(R.string.admin_user_line, "admin", formatBytes(FakeKodakikoServer.ME_USED_BYTES), formatBytes(10_737_418_240L), "2026-11-17")))
        compose.onNodeWithTag(listItem(TestTags.Admin.USER_ROW, 1)).assert(contains(FakeKodakikoServer.NEWCOMER))
    }

    @Test
    @Config(application = ServerAdminV031App::class)
    fun theU19QuotaStepSetsOneGigabyteOnTheServer() {
        unlockToGallery()
        push(Route.SettingsAdmin)
        awaitTag(listItem(TestTags.Admin.USER_ROW, 1), SYNC_WAIT_MS)
        click(listItem(TestTags.Admin.USER_ROW, 1))
        awaitTag(TestTags.Admin.USER_SHEET)
        tap(TestTags.Admin.USE_DEFAULT)
        awaitTag(TestTags.Admin.QUOTA)
        compose.onNodeWithTag(TestTags.Admin.QUOTA).performTextInput("1")
        tap(TestTags.Admin.SAVE)
        awaitGone(TestTags.Admin.USER_SHEET)
        compose.waitUntil(SYNC_WAIT_MS) {
            compose.onAllNodes(hasTestTag(listItem(TestTags.Admin.USER_ROW, 1)) and contains(FakeKodakikoServer.NEWCOMER) and contains("of " + formatBytes(GIB)))
                .fetchSemanticsNodes().isNotEmpty()
        }
        val patch = ServerFixture.requireServer().bodies.single { it.first == "PATCH " + Routes.adminUser(FakeKodakikoServer.NEWCOMER_ID) }.second
        assertTrue(patch, patch.contains("\"quotaBytes\":$GIB"))
    }

    @Test
    @Config(application = ServerAdminV031App::class)
    fun theU19InviteStepShowsTheServersInviteUrl() {
        unlockToGallery()
        push(Route.SettingsAdmin)
        click(TestTags.Admin.SEGMENT + ".invites")
        click(TestTags.Admin.NEW_INVITE)
        tap(TestTags.Admin.CREATE_INVITE)
        awaitTag(TestTags.Admin.INVITE_URL, SYNC_WAIT_MS)
        compose.onNodeWithTag(TestTags.Admin.INVITE_URL, useUnmergedTree = true).assertTextEquals(FakeKodakikoServer.INVITE_URL)
        assertTrue(Regex("#(kdk_i_[A-Za-z0-9_-]+)").containsMatchIn(textOf(TestTags.Admin.INVITE_URL)))
        val created = ServerFixture.requireServer().bodies.single { it.first == "POST " + Routes.adminInvites() }.second
        assertTrue(created, created.contains("\"role\":\"member\"") && created.contains("\"expiresInDays\":7"))
    }

    @Test
    @Config(application = ServerAdminInviteV031App::class)
    fun theU19PathFromTheMoreTabInvitesANewcomerThenSetsTheirQuota() {
        unlockToGallery()
        tap(TestTags.Tabs.MORE)
        click(TestTags.More.SETTINGS)
        awaitTag(TestTags.Settings.ROW_ADMIN, SYNC_WAIT_MS)
        click(TestTags.Settings.ROW_ADMIN)
        awaitTag(TestTags.Admin.SCREEN)
        click(TestTags.Admin.SEGMENT + ".invites")
        click(TestTags.Admin.NEW_INVITE)
        tap(TestTags.Admin.CREATE_INVITE)
        awaitTag(TestTags.Admin.INVITE_URL, SYNC_WAIT_MS)
        assertTrue(Regex("#(kdk_i_[A-Za-z0-9_-]+)").containsMatchIn(textOf(TestTags.Admin.INVITE_URL)))
        ServerFixture.requireServer().users += FakeKodakikoServer.adminUser(FakeKodakikoServer.NEWCOMER_ID, FakeKodakikoServer.NEWCOMER, UserRole.MEMBER, 0, null)
        closeSheet()
        awaitGone(TestTags.Admin.INVITE_SHEET)
        click(TestTags.Admin.SEGMENT + ".server")
        click(TestTags.Admin.SEGMENT + ".people")
        compose.waitUntil(SYNC_WAIT_MS) { newcomerRow() != null }
        val row = newcomerRow()!!
        click(row)
        awaitTag(TestTags.Admin.USER_SHEET)
        tap(TestTags.Admin.USE_DEFAULT)
        awaitTag(TestTags.Admin.QUOTA)
        compose.onNodeWithTag(TestTags.Admin.QUOTA).performTextInput("1")
        tap(TestTags.Admin.SAVE)
        awaitGone(TestTags.Admin.USER_SHEET)
        compose.waitUntil(SYNC_WAIT_MS) {
            compose.onAllNodes(hasTestTag(row) and contains(FakeKodakikoServer.NEWCOMER) and contains("of " + formatBytes(GIB))).fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(GIB, ServerFixture.requireServer().users.single { it.user.id == FakeKodakikoServer.NEWCOMER_ID }.user.quotaBytes)
    }

    private fun closeSheet() {
        scenario!!.onActivity { activity ->
            val dialog = ShadowDialog.getLatestDialog()
            if (dialog is ComponentDialog && dialog.isShowing) dialog.onBackPressedDispatcher.onBackPressed() else activity.onBackPressedDispatcher.onBackPressed()
        }
        compose.waitForIdle()
    }

    private fun newcomerRow(): String? = (0 until MAX_ROWS).map { listItem(TestTags.Admin.USER_ROW, it) }.firstOrNull { tag ->
        compose.onAllNodes(hasTestTag(tag) and contains(FakeKodakikoServer.NEWCOMER)).fetchSemanticsNodes().isNotEmpty()
    }

    @Test
    @Config(application = ServerAdminV031App::class)
    fun theServerSegmentShowsTheServersCounts() {
        unlockToGallery()
        push(Route.SettingsAdmin)
        click(TestTags.Admin.SEGMENT + ".server")
        awaitTag(listItem(TestTags.Admin.STAT, 10), SYNC_WAIT_MS)
        compose.onNodeWithTag(listItem(TestTags.Admin.STAT, 0))
            .assert(contains(app.getString(R.string.admin_stat_users)))
            .assert(contains(FakeKodakikoServer.STATS.users.toString()))
        compose.onNodeWithTag(listItem(TestTags.Admin.STAT, 8)).assert(contains(FakeKodakikoServer.config(true).serverVersion))
    }

    private companion object {
        const val VECTORS = 38
        const val MAX_ROWS = 10
        const val GIB = 1_073_741_824L
    }
}
