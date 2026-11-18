package dev.kodakiko.ui.feature.settings

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import dev.kodakiko.contract.OutboxKind
import dev.kodakiko.data.db.AppTheme
import dev.kodakiko.data.db.ThemePreset
import dev.kodakiko.data.media.ThumbnailModeSwitch
import dev.kodakiko.data.secure.AccountMode
import dev.kodakiko.service.backup.ExportPhase
import dev.kodakiko.service.backup.RestoreFailure
import dev.kodakiko.service.backup.RestoreFailureReason
import dev.kodakiko.service.backup.RestorePassphraseException
import dev.kodakiko.service.backup.RestoreProgress
import dev.kodakiko.ui.core.R
import dev.kodakiko.ui.core.TestTags
import dev.kodakiko.ui.core.TestTags.listItem
import dev.kodakiko.ui.core.navigation.Route
import kotlinx.coroutines.CompletableDeferred
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = Screenshots.QUALIFIERS)
class SettingsScreenshotTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val h = SettingsHarness()
    private val registry = FakeResultRegistry()

    private val allRows = SettingsRow.entries.toList()
    private val localRows = allRows - SettingsRow.LINKS - SettingsRow.ADMIN - SettingsRow.SIGN_OUT

    private fun await(tag: String) {
        compose.waitUntil(WAIT_MS) { compose.exists(tag) }
    }

    private fun tap(tag: String) {
        await(tag)
        compose.onNodeWithTag(tag).performScrollTo().performClick()
        compose.waitForIdle()
    }

    private fun host(route: Route, appTheme: AppTheme = AppTheme.LIGHT): SettingsTestHost {
        val host = SettingsTestHost(h, listOf(Route.Settings, route))
        compose.show(appTheme) { WithRegistry(registry) { host.Content() } }
        return host
    }

    private fun root(name: String, state: SettingsState, appTheme: AppTheme = AppTheme.LIGHT) {
        compose.show(appTheme) { SettingsContent(state = state, onAction = {}) }
        if (state.confirmingSignOut) {
            await(TestTags.Settings.SIGN_OUT_SHEET)
            captureScreenScreenshot("settings_root/$name")
        } else {
            compose.onRoot().captureScreenshot("settings_root/$name")
        }
    }

    private fun sync(name: String, state: SyncSettingsState) {
        compose.show { SyncSettingsContent(state = state, onAction = {}) }
        compose.onRoot().captureScreenshot("sync/$name")
    }

    @Test
    fun settingsRoot() {
        root("server_admin", SettingsState(rows = allRows, confirmingSignOut = false))
    }

    @Test
    fun settingsRootLocal() {
        root("local", SettingsState(rows = localRows, confirmingSignOut = false))
    }

    @Test
    fun settingsRootDark() {
        root("server_dark", SettingsState(rows = allRows - SettingsRow.ADMIN, confirmingSignOut = false), AppTheme.DARK)
    }

    @Test
    fun settingsRootSignOutQuestion() {
        root("sign_out_confirm", SettingsState(rows = allRows, confirmingSignOut = true))
    }

    @Test
    fun settingsSearchAutoLock() {
        host(Route.SettingsSearch)
        await(TestTags.Settings.SEARCH_FIELD)
        compose.onNodeWithTag(TestTags.Settings.SEARCH_FIELD).performTextInput("auto-lock")
        compose.onNodeWithTag(TestTags.Settings.SEARCH_FIELD).performImeAction()
        await(listItem(TestTags.Settings.RESULT, 0))
        compose.onRoot().captureScreenshot("settings_search/auto_lock")
    }

    @Test
    fun appearance() {
        host(Route.SettingsAppearance)
        await(TestTags.Settings.START_SCREEN)
        compose.onRoot().captureScreenshot("appearance/default")
    }

    @Test
    fun appearanceThemeChoices() {
        host(Route.SettingsAppearance)
        tap(SettingsTestTags.option(TestTags.Settings.THEME, "dark"))
        tap(listItem(TestTags.Settings.THEME_PRESET, ThemePreset.NORD.ordinal))
        await(TestTags.Settings.START_SCREEN)
        compose.onRoot().captureScreenshot("appearance/theme_choices")
    }

    @Test
    fun gallery() {
        host(Route.SettingsGallery)
        await(TestTags.Settings.DISPLAY_MODE)
        compose.onRoot().captureScreenshot("gallery/default")
    }

    @Test
    fun capture() {
        host(Route.SettingsCapture)
        await(TestTags.Settings.HOLD_MAX)
        compose.onRoot().captureScreenshot("capture/default")
    }

    @Test
    fun captureLocationDenied() {
        h.location.result = false
        host(Route.SettingsCapture)
        tap(TestTags.Settings.LOCATION)
        await(TestTags.Settings.LOCATION_DENIED)
        compose.onRoot().captureScreenshot("capture/location_denied")
    }

    @Test
    fun syncServer() {
        sync(
            "server",
            SyncSettingsState(
                local = false,
                status = idleStatus(queued = 3, dead = 2, waitingForWifi = 1, lastSuccessAt = LAST_SUCCESS_MS),
                syncing = false,
                dead = listOf(
                    deadRow(7, OutboxKind.UPDATE_ENTRY_META, ENTRY_A, "VALIDATION_FAILED"),
                    deadRow(9, OutboxKind.ADD_MEDIA, ENTRY_B, "QUOTA_EXCEEDED"),
                ),
            ),
        )
    }

    @Test
    fun syncNeverSyncedAndBusy() {
        sync("syncing", SyncSettingsState(local = false, status = idleStatus(), syncing = true, dead = emptyList()))
    }

    @Test
    fun syncLocal() {
        sync("local", SyncSettingsState(local = true, status = idleStatus(), syncing = false, dead = emptyList()))
    }

    @Test
    fun storage() {
        host(Route.SettingsStorage)
        await(TestTags.Settings.USAGE_ORIGINALS)
        compose.onRoot().captureScreenshot("storage/server")
    }

    @Test
    fun storageLocal() {
        h.account.modeState.value = AccountMode.LOCAL
        h.storage.device = null
        host(Route.SettingsStorage)
        await(TestTags.Settings.USAGE_ORIGINALS)
        compose.onRoot().captureScreenshot("storage/local")
    }

    @Test
    fun storageClearQuestion() {
        host(Route.SettingsStorage)
        await(TestTags.Settings.USAGE_ORIGINALS)
        tap(TestTags.Settings.CLEAR_ORIGINALS)
        await(TestTags.Settings.CLEAR_SHEET)
        captureScreenScreenshot("storage/clear_confirm")
    }

    @Test
    fun security() {
        host(Route.SettingsSecurity)
        await(TestTags.Settings.HIDE_NOTE_PREVIEWS)
        compose.waitUntil(WAIT_MS) { compose.showsText(text(R.string.settings_biometric_on)) }
        compose.onRoot().captureScreenshot("security/default")
    }

    @Test
    fun securityAutoLockDialog() {
        host(Route.SettingsSecurity)
        tap(TestTags.Settings.AUTO_LOCK)
        await(SettingsTestTags.dialog(TestTags.Settings.AUTO_LOCK))
        captureScreenScreenshot("security/auto_lock_dialog")
    }

    @Test
    fun securityThumbnailQuestion() {
        host(Route.SettingsSecurity)
        tap(TestTags.Settings.ENCRYPT_THUMBNAILS)
        await(TestTags.Settings.THUMBNAIL_SHEET)
        captureScreenScreenshot("security/thumbnail_confirm")
    }

    @Test
    fun securityThumbnailProgress() {
        h.biometric = false
        h.store.set(defaults.copy(encryptThumbnails = true))
        h.thumbnails.modeSwitchState.value = ThumbnailModeSwitch(running = true, done = 3, total = 10)
        host(Route.SettingsSecurity)
        await(TestTags.Settings.THUMBNAIL_PROGRESS)
        compose.waitUntil(WAIT_MS) { compose.showsText(text(R.string.settings_biometric_off)) }
        compose.onRoot().captureScreenshot("security/thumbnail_progress")
    }

    @Test
    fun exportIdle() {
        host(Route.SettingsExport)
        await(TestTags.Export.START)
        compose.onRoot().captureScreenshot("export/idle")
    }

    @Test
    fun exportEncrypted() {
        host(Route.SettingsExport)
        tap(TestTags.Export.ENCRYPT)
        await(TestTags.Export.PASSPHRASE_CONFIRM)
        compose.onRoot().captureScreenshot("export/encrypted")
    }

    @Test
    fun exportRunning() {
        h.backup.exportService.progressSteps = listOf(progress(ExportPhase.WRITING, 1, 2, MB, 1, 2))
        h.backup.exportService.gate = CompletableDeferred()
        host(Route.SettingsExport)
        tap(TestTags.Export.START)
        compose.waitUntil(WAIT_MS) { registry.launches.isNotEmpty() }
        compose.runOnUiThread { registry.answer(PickedUris.FOLDER) }
        await(TestTags.Export.CANCEL)
        compose.onRoot().captureScreenshot("export/running")
        tap(TestTags.Export.CANCEL)
    }

    @Test
    fun exportDoneWithSkippedItems() {
        h.backup.exportService.summary = h.backup.exportService.summary.copy(skippedMissing = 1)
        host(Route.SettingsExport)
        tap(TestTags.Export.START)
        compose.waitUntil(WAIT_MS) { registry.launches.isNotEmpty() }
        compose.runOnUiThread { registry.answer(PickedUris.FOLDER) }
        await(TestTags.Export.SKIPPED)
        compose.onRoot().captureScreenshot("export/done_skipped")
    }

    @Test
    fun exportCancelled() {
        h.backup.exportService.gate = CompletableDeferred()
        host(Route.SettingsExport)
        tap(TestTags.Export.START)
        compose.waitUntil(WAIT_MS) { registry.launches.isNotEmpty() }
        compose.runOnUiThread { registry.answer(PickedUris.FOLDER) }
        tap(TestTags.Export.CANCEL)
        await(TestTags.Export.MESSAGE)
        compose.onRoot().captureScreenshot("export/cancelled")
    }

    @Test
    fun importIdle() {
        host(Route.SettingsImport)
        await(TestTags.Import.START)
        compose.onRoot().captureScreenshot("import/idle")
    }

    @Test
    fun importRunning() {
        h.backup.restoreService.progressSteps = listOf(RestoreProgress(1, 2))
        h.backup.restoreService.progressGate = CompletableDeferred()
        host(Route.SettingsImport)
        tap(TestTags.Import.START)
        compose.waitUntil(WAIT_MS) { registry.launches.isNotEmpty() }
        compose.runOnUiThread { registry.answer(listOf(PickedUris.PART_ONE, PickedUris.PART_TWO)) }
        await(TestTags.Import.PROGRESS)
        compose.onRoot().captureScreenshot("import/running")
        h.backup.restoreService.progressGate?.complete(Unit)
    }

    @Test
    fun importSummaryWithFailures() {
        h.backup.restoreService.summary = h.backup.restoreService.summary.copy(
            duplicates = 1,
            failed = listOf(RestoreFailure("2026/2026-02-02-0800/entry.md", RestoreFailureReason.MISSING_MEDIA)),
        )
        host(Route.SettingsImport)
        tap(TestTags.Import.START)
        compose.waitUntil(WAIT_MS) { registry.launches.isNotEmpty() }
        compose.runOnUiThread { registry.answer(listOf(PickedUris.PART_ONE, PickedUris.PART_TWO)) }
        await(TestTags.Import.FAILED)
        compose.onRoot().captureScreenshot("import/summary_failed")
    }

    @Test
    fun importWrongPassphrase() {
        h.backup.restoreService.failure = RestorePassphraseException("kodakiko-export-1.zip.kdkx")
        host(Route.SettingsImport)
        tap(TestTags.Import.START)
        compose.waitUntil(WAIT_MS) { registry.launches.isNotEmpty() }
        compose.runOnUiThread { registry.answer(listOf(PickedUris.PART_ONE)) }
        await(TestTags.Import.ERROR)
        compose.onRoot().captureScreenshot("import/wrong_passphrase")
    }

    private companion object {
        const val WAIT_MS = 5_000L
    }
}
