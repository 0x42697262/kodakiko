@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.kodakiko.ui.feature.settings

import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import dev.kodakiko.service.backup.ExportPhase
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
import java.io.IOException

private const val SHORT_PASSPHRASE = "eleven char"

@RunWith(RobolectricTestRunner::class)
class ExportViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val h = SettingsHarness()

    private class Opened(val vm: ExportViewModel, val events: MutableList<SettingsEvent>) {
        val state: ExportState get() = vm.state.value
    }

    private fun TestScope.open(): Opened {
        val vm = ExportViewModel(h.deps)
        val events = mutableListOf<SettingsEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events += it } }
        runCurrent()
        return Opened(vm, events)
    }

    private fun Opened.encryptWith(passphrase: String, confirm: String = passphrase) {
        vm.onAction(ExportAction.SetEncrypt(true))
        vm.onAction(ExportAction.SetPassphrase(passphrase))
        vm.onAction(ExportAction.SetConfirm(confirm))
    }

    @Test
    fun anIdleExportIsUnencryptedAndHasNothingToReport() = runTest {
        val o = open()
        assertFalse(o.state.encrypt)
        assertEquals("", o.state.passphrase)
        assertEquals("", o.state.confirm)
        assertNull(o.state.problem)
        assertNull(o.state.progress)
        assertNull(o.state.summary)
        assertNull(o.state.message)
        assertFalse(o.state.running)
    }

    @Test
    fun startAsksForAFolderAndExportsNothingYet() = runTest {
        val o = open()
        o.vm.onAction(ExportAction.Start)
        runCurrent()
        assertEquals(listOf<SettingsEvent>(SettingsEvent.PickFolder), o.events)
        assertEquals(emptyList<Any>(), h.backup.openedFolders)
        assertEquals(emptyList<Any>(), h.backup.exportService.destinations)
    }

    @Test
    fun aCancelledFolderPickerExportsNothing() = runTest {
        val o = open()
        o.vm.onAction(ExportAction.Start)
        o.vm.onAction(ExportAction.FolderChosen(null))
        runCurrent()
        assertEquals(emptyList<Any>(), h.backup.openedFolders)
        assertEquals(emptyList<Any>(), h.backup.exportService.destinations)
        assertFalse(o.state.running)
        assertNull(o.state.progress)
    }

    @Test
    fun theChosenFolderIsOpenedAndExportedToAndTheSummaryShows() = runTest {
        val o = open()
        o.vm.onAction(ExportAction.Start)
        o.vm.onAction(ExportAction.FolderChosen(PickedUris.FOLDER))
        runCurrent()
        assertEquals(listOf(PickedUris.FOLDER), h.backup.openedFolders)
        assertEquals(listOf(PickedUris.FOLDER.toString()), h.backup.exportService.destinations.map { (it as FakeDestination).label })
        assertEquals(emptyList<Any>(), h.backup.encrypted)
        assertEquals(h.backup.exportService.summary, o.state.summary)
        assertFalse(o.state.running)
        assertNull(o.state.message)
    }

    @Test
    fun aRunningExportStartsInPlanningAndFollowsTheServiceProgress() = runTest {
        val gate = CompletableDeferred<Unit>()
        h.backup.exportService.progressSteps = listOf(
            progress(ExportPhase.PLANNING, 0, 2, 0, 0, 0),
            progress(ExportPhase.WRITING, 1, 2, MB, 1, 2),
        )
        h.backup.exportService.progressGate = gate
        val o = open()
        o.vm.onAction(ExportAction.Start)
        o.vm.onAction(ExportAction.FolderChosen(PickedUris.FOLDER))
        runCurrent()
        assertTrue(o.state.running)
        assertEquals(progress(ExportPhase.WRITING, 1, 2, MB, 1, 2), o.state.progress)
        assertNull(o.state.summary)
        gate.complete(Unit)
        runCurrent()
        assertFalse(o.state.running)
        assertEquals(h.backup.exportService.summary, o.state.summary)
    }

    @Test
    fun beforeTheServiceReportsTheProgressIsPlanningAtZero() = runTest {
        h.backup.exportService.gate = CompletableDeferred()
        val o = open()
        o.vm.onAction(ExportAction.Start)
        o.vm.onAction(ExportAction.FolderChosen(PickedUris.FOLDER))
        runCurrent()
        assertTrue(o.state.running)
        assertEquals(progress(ExportPhase.PLANNING, 0, 0, 0, 0, 0), o.state.progress)
        o.vm.onAction(ExportAction.Cancel)
        runCurrent()
    }

    @Test
    fun cancelStopsTheServiceAndSaysFinishedFilesWereKept() = runTest {
        h.backup.exportService.gate = CompletableDeferred()
        val o = open()
        o.vm.onAction(ExportAction.Start)
        o.vm.onAction(ExportAction.FolderChosen(PickedUris.FOLDER))
        runCurrent()
        o.vm.onAction(ExportAction.Cancel)
        runCurrent()
        assertEquals(1, h.backup.exportService.cancels)
        assertEquals(ExportMessage.CANCELLED, o.state.message)
        assertNull(o.state.progress)
        assertNull(o.state.summary)
        assertFalse(o.state.running)
    }

    @Test
    fun aFailedExportSaysSoAndAllowsANewStart() = runTest {
        h.backup.exportService.failure = IOException()
        val o = open()
        o.vm.onAction(ExportAction.Start)
        o.vm.onAction(ExportAction.FolderChosen(PickedUris.FOLDER))
        runCurrent()
        assertEquals(ExportMessage.FAILED, o.state.message)
        assertNull(o.state.progress)
        assertFalse(o.state.running)
        h.backup.exportService.failure = null
        o.vm.onAction(ExportAction.Start)
        runCurrent()
        assertNull(o.state.message)
        o.vm.onAction(ExportAction.FolderChosen(PickedUris.FOLDER))
        runCurrent()
        assertEquals(h.backup.exportService.summary, o.state.summary)
        assertNull(o.state.message)
    }

    @Test
    fun aShortOrMismatchedPassphraseBlocksTheStartWithTheV031Problem() = runTest {
        val o = open()
        o.encryptWith(SHORT_PASSPHRASE)
        assertEquals(ExportPassphraseProblem.SHORT, o.state.problem)
        o.vm.onAction(ExportAction.Start)
        runCurrent()
        assertEquals(emptyList<SettingsEvent>(), o.events)
        o.vm.onAction(ExportAction.SetPassphrase(LONG_PASSPHRASE))
        assertEquals(ExportPassphraseProblem.MISMATCH, o.state.problem)
        o.vm.onAction(ExportAction.Start)
        runCurrent()
        assertEquals(emptyList<SettingsEvent>(), o.events)
        o.vm.onAction(ExportAction.SetConfirm(LONG_PASSPHRASE))
        assertNull(o.state.problem)
        o.vm.onAction(ExportAction.Start)
        runCurrent()
        assertEquals(listOf<SettingsEvent>(SettingsEvent.PickFolder), o.events)
    }

    @Test
    fun exactlyTwelveCharactersAreEnough() = runTest {
        val o = open()
        o.encryptWith("twelve chars")
        assertNull(o.state.problem)
    }

    @Test
    fun turningEncryptionOffForgetsTheProblem() = runTest {
        val o = open()
        o.encryptWith(SHORT_PASSPHRASE)
        o.vm.onAction(ExportAction.SetEncrypt(false))
        assertNull(o.state.problem)
        o.vm.onAction(ExportAction.Start)
        runCurrent()
        assertEquals(listOf<SettingsEvent>(SettingsEvent.PickFolder), o.events)
    }

    @Test
    fun anEncryptedExportWritesOnlyKdkxPartsWithThePassphraseLikeU29() = runTest {
        val o = open()
        o.encryptWith(LONG_PASSPHRASE)
        o.vm.onAction(ExportAction.Start)
        o.vm.onAction(ExportAction.FolderChosen(PickedUris.FOLDER))
        runCurrent()
        assertEquals(listOf(PickedUris.FOLDER), h.backup.openedFolders)
        assertEquals(listOf(PickedUris.FOLDER.toString() to LONG_PASSPHRASE), h.backup.encrypted.map { (it.first as FakeDestination).label to it.second })
        assertEquals(listOf(FakeSettingsBackup.KDKX_LABEL), h.backup.exportService.destinations.map { (it as FakeDestination).label })
        assertEquals(h.backup.exportService.summary, o.state.summary)
    }

    @Test
    fun backLeavesTheScreen() = runTest {
        val o = open()
        o.vm.onAction(ExportAction.Back)
        runCurrent()
        assertEquals(listOf<SettingsEvent>(SettingsEvent.Back), o.events)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h1600dp-mdpi")
class ExportScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val h = SettingsHarness()
    private val registry = FakeResultRegistry()
    private val navigator = RecordingNavigator(listOf(Route.Settings, Route.SettingsStorage, Route.SettingsExport))

    private fun open() {
        val vm = ExportViewModel(h.deps)
        compose.showWith(navigator) { WithRegistry(registry) { ExportScreen(viewModel = vm) } }
        await(TestTags.Settings.EXPORT_SCREEN)
    }

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
        compose.onNodeWithTag(tag).performScrollTo().performTextInput(value)
        compose.waitForIdle()
    }

    private fun pickFolder() {
        compose.waitUntil(WAIT_MS) { registry.launches.isNotEmpty() }
        val launch = registry.launches.single()
        assertTrue(launch.contract.javaClass.name, launch.contract is ActivityResultContracts.OpenDocumentTree)
        assertNull(launch.input)
        compose.runOnUiThread { registry.answer(PickedUris.FOLDER) }
        compose.waitForIdle()
    }

    @Test
    fun theIdleScreenShowsTheV031TextsTheEncryptSwitchAndTheStartButton() {
        open()
        assertTrue(compose.showsText(text(R.string.settings_row_export)))
        assertTrue(compose.showsText(text(R.string.settings_export_explain)))
        assertTrue(compose.showsText(text(R.string.settings_export_keep_open)))
        compose.onNodeWithTag(TestTags.Export.ENCRYPT).assert(contains(text(R.string.export_encrypt))).assert(contains(text(R.string.export_encrypt_summary))).assert(unchecked())
        compose.onNodeWithTag(TestTags.Export.START).assert(labeled(text(R.string.settings_export_start)))
        for (tag in listOf(TestTags.Export.PASSPHRASE, TestTags.Export.PASSPHRASE_CONFIRM, TestTags.Export.PROGRESS, TestTags.Export.SUMMARY, TestTags.Export.MESSAGE, TestTags.Export.SKIPPED, TestTags.Export.CANCEL)) {
            assertFalse(tag, compose.exists(tag))
        }
    }

    @Test
    fun theSafFolderPickerReplacesTheDownloadFallbackAndTheExportGoesIntoTheChosenFolder() {
        open()
        tap(TestTags.Export.START)
        pickFolder()
        await(TestTags.Export.SUMMARY)
        assertEquals(listOf(PickedUris.FOLDER), h.backup.openedFolders)
        assertEquals(listOf(PickedUris.FOLDER.toString()), h.backup.exportService.destinations.map { (it as FakeDestination).label })
        compose.onNodeWithTag(TestTags.Export.SUMMARY).assert(contains(plural(R.plurals.settings_export_done, 2, 2, formatBytes(3 * MB))))
        assertFalse(compose.exists(TestTags.Export.SKIPPED))
        assertTrue(compose.exists(TestTags.Export.START))
    }

    @Test
    fun aCancelledPickerLeavesTheScreenAsItWas() {
        open()
        tap(TestTags.Export.START)
        compose.waitUntil(WAIT_MS) { registry.launches.isNotEmpty() }
        compose.runOnUiThread { registry.answer<Uri?>(null) }
        compose.waitForIdle()
        assertEquals(emptyList<Any>(), h.backup.openedFolders)
        assertFalse(compose.exists(TestTags.Export.PROGRESS))
        assertTrue(compose.exists(TestTags.Export.START))
    }

    @Test
    fun whileRunningTheProgressAndCancelShowAndCancelKeepsFinishedFiles() {
        h.backup.exportService.progressSteps = listOf(progress(ExportPhase.WRITING, 1, 2, MB, 1, 2))
        h.backup.exportService.gate = CompletableDeferred()
        open()
        tap(TestTags.Export.START)
        pickFolder()
        await(TestTags.Export.PROGRESS)
        compose.onNodeWithTag(TestTags.Export.PROGRESS, useUnmergedTree = true)
            .assertTextEquals(text(R.string.settings_export_progress, text(R.string.settings_phase_writing), 1, 2, 1, 2, formatBytes(MB)))
        assertFalse(compose.exists(TestTags.Export.START))
        assertFalse(compose.exists(TestTags.Export.ENCRYPT))
        compose.onNodeWithTag(TestTags.Export.CANCEL).assert(labeled(text(R.string.settings_export_cancel)))
        tap(TestTags.Export.CANCEL)
        await(TestTags.Export.MESSAGE)
        assertEquals(1, h.backup.exportService.cancels)
        compose.onNodeWithTag(TestTags.Export.MESSAGE).assert(contains(text(R.string.settings_export_cancelled)))
        assertFalse(compose.exists(TestTags.Export.PROGRESS))
        assertTrue(compose.exists(TestTags.Export.START))
    }

    @Test
    fun aFailedExportShowsTheV031Warning() {
        h.backup.exportService.failure = IOException()
        open()
        tap(TestTags.Export.START)
        pickFolder()
        await(TestTags.Export.MESSAGE)
        compose.onNodeWithTag(TestTags.Export.MESSAGE).assert(contains(text(R.string.settings_export_failed)))
        assertFalse(compose.exists(TestTags.Export.SUMMARY))
    }

    @Test
    fun skippedItemsAreCountedUnderTheSummary() {
        h.backup.exportService.summary = h.backup.exportService.summary.copy(skippedUnreadable = 1, skippedMissing = 1, skippedTooLarge = 1)
        open()
        tap(TestTags.Export.START)
        pickFolder()
        await(TestTags.Export.SKIPPED)
        compose.onNodeWithTag(TestTags.Export.SKIPPED, useUnmergedTree = true).assertTextEquals(plural(R.plurals.settings_export_skipped, 3, 3))
    }

    @Test
    fun encryptingAsksForThePassphraseTwiceAndExplainsTheKdkxFiles() {
        open()
        tap(TestTags.Export.ENCRYPT)
        await(TestTags.Export.PASSPHRASE)
        compose.onNodeWithTag(TestTags.Export.ENCRYPT).assert(checked())
        assertTrue(compose.showsText(text(R.string.export_explain_encrypted)))
        assertFalse(compose.showsText(text(R.string.settings_export_explain)))
        compose.onNodeWithTag(TestTags.Export.PASSPHRASE).assert(contains(text(R.string.export_passphrase)))
        compose.onNodeWithTag(TestTags.Export.PASSPHRASE_CONFIRM).assert(contains(text(R.string.export_passphrase_confirm)))
        assertFalse(compose.showsText(text(R.string.export_passphrase_short)))
    }

    @Test
    fun aShortPassphraseShowsItsProblemAndDoesNotOpenThePicker() {
        open()
        tap(TestTags.Export.ENCRYPT)
        type(TestTags.Export.PASSPHRASE, SHORT_PASSPHRASE)
        compose.waitUntil(WAIT_MS) { compose.showsText(text(R.string.export_passphrase_short)) }
        tap(TestTags.Export.START)
        assertEquals(0, registry.launches.size)
        type(TestTags.Export.PASSPHRASE, "!")
        type(TestTags.Export.PASSPHRASE_CONFIRM, "different passphrase")
        compose.waitUntil(WAIT_MS) { compose.showsText(text(R.string.export_passphrase_mismatch)) }
        tap(TestTags.Export.START)
        assertEquals(0, registry.launches.size)
    }

    @Test
    fun anEncryptedExportGoesThroughTheKdkxDestinationLikeU29() {
        open()
        tap(TestTags.Export.ENCRYPT)
        type(TestTags.Export.PASSPHRASE, LONG_PASSPHRASE)
        type(TestTags.Export.PASSPHRASE_CONFIRM, LONG_PASSPHRASE)
        tap(TestTags.Export.START)
        pickFolder()
        await(TestTags.Export.SUMMARY)
        assertEquals(listOf(LONG_PASSPHRASE), h.backup.encrypted.map { it.second })
        assertEquals(listOf(FakeSettingsBackup.KDKX_LABEL), h.backup.exportService.destinations.map { (it as FakeDestination).label })
    }

    @Test
    fun theBackArrowLeavesTheScreen() {
        open()
        tap(TestTags.Settings.BACK)
        assertEquals(1, navigator.pops)
    }

    private companion object {
        const val WAIT_MS = 5_000L
    }
}
