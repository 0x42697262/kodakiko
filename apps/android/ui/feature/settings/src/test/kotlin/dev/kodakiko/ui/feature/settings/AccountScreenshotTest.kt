package dev.kodakiko.ui.feature.settings

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import dev.kodakiko.contract.UserStatus
import dev.kodakiko.data.db.AppTheme
import dev.kodakiko.data.secure.AccountMode
import dev.kodakiko.ui.core.TestTags
import dev.kodakiko.ui.core.TestTags.listItem
import dev.kodakiko.ui.core.navigation.Route
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = Screenshots.QUALIFIERS)
class AccountScreenshotTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val h = SettingsHarness()

    private fun await(tag: String) {
        compose.waitUntil(WAIT_MS) { compose.exists(tag) }
    }

    private fun tap(tag: String) {
        await(tag)
        compose.onNodeWithTag(tag).performScrollTo().performClick()
        compose.waitForIdle()
    }

    private fun type(tag: String, value: String) {
        await(tag)
        compose.onNodeWithTag(tag).performTextInput(value)
        compose.waitForIdle()
    }

    private fun host(route: Route, appTheme: AppTheme = AppTheme.LIGHT) {
        val host = SettingsTestHost(h, listOf(Route.Settings, route))
        compose.show(appTheme) { host.Content() }
    }

    @Test
    fun accountServer() {
        host(Route.SettingsAccount)
        await(TestTags.Settings.STORAGE_USE)
        compose.onRoot().captureScreenshot("account/server")
    }

    @Test
    fun accountServerDark() {
        host(Route.SettingsAccount, AppTheme.DARK)
        await(TestTags.Settings.STORAGE_USE)
        compose.onRoot().captureScreenshot("account/server_dark")
    }

    @Test
    fun accountLocal() {
        h.account.modeState.value = AccountMode.LOCAL
        host(Route.SettingsAccount)
        await(TestTags.Settings.ROW_CONNECT)
        compose.onRoot().captureScreenshot("account/local")
    }

    @Test
    fun accountChangeServerSheet() {
        h.sync.statusState.value = idleStatus(queued = 2)
        host(Route.SettingsAccount)
        tap(TestTags.Settings.CHANGE_SERVER)
        await(TestTags.Settings.CHANGE_SERVER_CONFIRM)
        captureScreenScreenshot("account/change_server_sheet")
    }

    @Test
    @Config(qualifiers = SHEET_QUALIFIERS)
    fun accountRecoveryWrongPassword() {
        host(Route.SettingsAccount)
        tap(TestTags.Settings.ROW_RECOVERY)
        type(TestTags.Settings.RECOVERY_PASSWORD, "not it")
        tap(TestTags.Settings.RECOVERY_SHOW)
        await(TestTags.Settings.ERROR)
        captureScreenScreenshot("account/recovery_wrong_password")
    }

    @Test
    @Config(qualifiers = SHEET_QUALIFIERS)
    fun accountRecoveryWords() {
        host(Route.SettingsAccount)
        tap(TestTags.Settings.ROW_RECOVERY)
        type(TestTags.Settings.RECOVERY_PASSWORD, GOOD_PASSWORD)
        tap(TestTags.Settings.RECOVERY_SHOW)
        await(TestTags.Settings.RECOVERY_WORDS)
        captureScreenScreenshot("account/recovery_words")
    }

    @Test
    fun passwordHintsAndMismatch() {
        host(Route.SettingsPassword)
        type(TestTags.Settings.CURRENT_PASSWORD, GOOD_PASSWORD)
        type(TestTags.Settings.NEW_PASSWORD, "ada")
        type(TestTags.Settings.CONFIRM_PASSWORD, "adb")
        await(TestTags.Settings.PASSWORD_MISMATCH)
        compose.onRoot().captureScreenshot("password/hints_mismatch")
    }

    @Test
    fun passwordChanged() {
        host(Route.SettingsPassword)
        type(TestTags.Settings.CURRENT_PASSWORD, GOOD_PASSWORD)
        type(TestTags.Settings.NEW_PASSWORD, NEW_PASSWORD)
        type(TestTags.Settings.CONFIRM_PASSWORD, NEW_PASSWORD)
        tap(TestTags.Settings.PASSWORD_SUBMIT)
        await(TestTags.Settings.PASSWORD_RESULT)
        compose.onRoot().captureScreenshot("password/changed")
    }

    @Test
    fun deleteServer() {
        host(Route.SettingsDeleteAccount)
        type(TestTags.Settings.DELETE_USERNAME, USERNAME)
        compose.onRoot().captureScreenshot("delete/server")
    }

    @Test
    fun eraseLocalWrongPassword() {
        h.account.modeState.value = AccountMode.LOCAL
        host(Route.SettingsDeleteAccount)
        type(TestTags.Settings.DELETE_USERNAME, USERNAME)
        type(TestTags.Settings.DELETE_PASSWORD, "not it")
        tap(TestTags.Settings.DELETE_CONFIRM)
        await(TestTags.Settings.ERROR)
        compose.onRoot().captureScreenshot("delete/local_wrong_password")
    }

    @Test
    fun serverKeysServer() {
        host(Route.SettingsServer)
        compose.waitUntil(WAIT_MS) { compose.showsText(USER_ID) }
        compose.onRoot().captureScreenshot("server_keys/server")
    }

    @Test
    fun serverKeysLocal() {
        h.account.modeState.value = AccountMode.LOCAL
        h.profile.userState.value = null
        h.store.set(defaults.copy(encryptThumbnails = true))
        host(Route.SettingsServer)
        compose.waitUntil(WAIT_MS) { compose.showsText(USER_ID) }
        compose.onRoot().captureScreenshot("server_keys/local")
    }

    @Test
    fun advancedDefault() {
        host(Route.SettingsAdvanced)
        await(TestTags.Advanced.DIAGNOSTICS)
        compose.onRoot().captureScreenshot("advanced/default")
    }

    @Test
    fun advancedDebugInfo() {
        host(Route.SettingsAdvanced)
        tap(TestTags.Advanced.DEBUG)
        await(TestTags.Advanced.DEBUG_TEXT)
        compose.onRoot().captureScreenshot("advanced/debug_info")
    }

    @Test
    fun advancedResetSheet() {
        host(Route.SettingsAdvanced)
        tap(TestTags.Advanced.RESET)
        await(TestTags.Advanced.RESET_CONFIRM)
        captureScreenScreenshot("advanced/reset_sheet")
    }

    @Test
    fun diagnosticsResult() {
        host(Route.SettingsDiagnostics)
        tap(TestTags.Diagnostics.RUN_VECTORS)
        await(listItem(TestTags.Diagnostics.LINE, 3))
        compose.onRoot().captureScreenshot("diagnostics/result")
    }

    @Test
    fun aboutLicenses() {
        host(Route.SettingsAbout)
        await(listItem(TestTags.Settings.LICENSE, 2))
        compose.onRoot().captureScreenshot("about/licenses")
    }

    @Test
    fun aboutEmpty() {
        h.licenses.licenses = emptyList()
        host(Route.SettingsAbout)
        await(TestTags.Settings.LICENSES_EMPTY)
        compose.onRoot().captureScreenshot("about/empty")
    }

    @Test
    fun adminPeople() {
        h.account.admin = true
        h.admin.users += adminUser("U3", "carol", usedBytes = 0, status = UserStatus.PENDING_DELETION)
        host(Route.SettingsAdmin)
        await(listItem(TestTags.Admin.USER_ROW, 2))
        compose.onRoot().captureScreenshot("admin/people")
    }

    @Test
    @Config(qualifiers = SHEET_QUALIFIERS)
    fun adminUserSheet() {
        h.account.admin = true
        host(Route.SettingsAdmin)
        tap(listItem(TestTags.Admin.USER_ROW, 0))
        await(TestTags.Admin.QUOTA)
        captureScreenScreenshot("admin/user_sheet")
    }

    @Test
    fun adminInvites() {
        h.account.admin = true
        h.admin.invites += invite("I1", expiresAt = "2026-11-25T00:00:00.000Z")
        h.admin.invites += invite("I2", redeemedAt = "2026-11-17T00:00:00.000Z", redeemedBy = "eve")
        h.admin.invites += invite("I3", revokedAt = "2026-11-02T00:00:00.000Z")
        host(Route.SettingsAdmin)
        tap(TestTags.Admin.SEGMENT + ".invites")
        await(listItem(TestTags.Admin.INVITE_ROW, 2))
        compose.onRoot().captureScreenshot("admin/invites")
    }

    @Test
    @Config(qualifiers = SHEET_QUALIFIERS)
    fun adminInviteUrl() {
        h.account.admin = true
        host(Route.SettingsAdmin)
        tap(TestTags.Admin.SEGMENT + ".invites")
        tap(TestTags.Admin.NEW_INVITE)
        tap(TestTags.Admin.CREATE_INVITE)
        await(TestTags.Admin.INVITE_URL)
        captureScreenScreenshot("admin/invite_url")
    }

    @Test
    fun adminServer() {
        h.account.admin = true
        host(Route.SettingsAdmin)
        tap(TestTags.Admin.SEGMENT + ".server")
        await(listItem(TestTags.Admin.STAT, 10))
        compose.onRoot().captureScreenshot("admin/server")
    }

    @Test
    fun adminNotAdmin() {
        host(Route.SettingsAdmin)
        await(TestTags.Admin.NOT_ADMIN)
        compose.onRoot().captureScreenshot("admin/not_admin")
    }

    private companion object {
        const val WAIT_MS = 5_000L
        const val SHEET_QUALIFIERS = "w320dp-h900dp-xhdpi"
    }
}
