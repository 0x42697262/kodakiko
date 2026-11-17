@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.kodakiko.ui.feature.settings

import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import dev.kodakiko.service.backup.RestoreFailure
import dev.kodakiko.service.backup.RestoreFailureReason
import dev.kodakiko.service.backup.RestorePassphraseException
import dev.kodakiko.service.backup.RestoreProgress
import dev.kodakiko.ui.core.R
import dev.kodakiko.ui.core.TestTags
import dev.kodakiko.ui.core.navigation.Route
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
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

private val PARTS = listOf(PickedUris.PART_ONE, PickedUris.PART_TWO)
private val PART_NAMES = listOf("kodakiko-export-1.zip.kdkx", "kodakiko-export-2.zip.kdkx")

@RunWith(RobolectricTestRunner::class)
class ImportViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val h = SettingsHarness()

    private class Opened(val vm: ImportViewModel, val events: MutableList<SettingsEvent>) {
        val state: ImportState get() = vm.state.value
    }

    private fun TestScope.open(): Opened {
        val vm = ImportViewModel(h.deps)
        val events = mutableListOf<SettingsEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events += it } }
        runCurrent()
        return Opened(vm, events)
    }

    @Test
    fun anIdleRestoreHasNoPassphraseAndNothingToReport() = runTest {
        val o = open()
        assertEquals("", o.state.passphrase)
        assertFalse(o.state.busy)
        assertNull(o.state.progress)
        assertNull(o.state.summary)
        assertNull(o.state.error)
    }

    @Test
    fun startAsksForTheFiles() = runTest {
        val o = open()
        o.vm.onAction(ImportAction.Start)
        runCurrent()
        assertEquals(listOf<SettingsEvent>(SettingsEvent.PickFiles), o.events)
        assertEquals(emptyList<Any>(), h.backup.readRequests)
    }

    @Test
    fun noChosenFilesRestoreNothing() = runTest {
        val o = open()
        o.vm.onAction(ImportAction.FilesChosen(emptyList()))
        runCurrent()
        assertEquals(emptyList<Any>(), h.backup.readRequests)
        assertEquals(emptyList<Any>(), h.backup.restoreService.runs)
        assertFalse(o.state.busy)
    }

    @Test
    fun theChosenFilesAreReadAndRestoredWithThePassphraseLikeU29() = runTest {
        val o = open()
        o.vm.onAction(ImportAction.SetPassphrase(LONG_PASSPHRASE))
        o.vm.onAction(ImportAction.FilesChosen(PARTS))
        runCurrent()
        assertEquals(listOf(PARTS), h.backup.readRequests)
        val run = h.backup.restoreService.runs.single()
        assertEquals(PART_NAMES, run.first.map { it.name })
        assertArrayEquals(byteArrayOf(1, 2, 3), run.first.first().bytes)
        assertEquals(LONG_PASSPHRASE, run.second)
        assertEquals(h.backup.restoreService.summary, o.state.summary)
        assertFalse(o.state.busy)
        assertNull(o.state.error)
    }

    @Test
    fun anEmptyPassphraseIsNoPassphrase() = runTest {
        val o = open()
        o.vm.onAction(ImportAction.FilesChosen(PARTS))
        runCurrent()
        assertNull(h.backup.restoreService.runs.single().second)
    }

    @Test
    fun theRestoreIsBusyAndFollowsTheProgressUntilItEnds() = runTest {
        val gate = CompletableDeferred<Unit>()
        h.backup.restoreService.progressSteps = listOf(RestoreProgress(0, 2), RestoreProgress(1, 2))
        h.backup.restoreService.progressGate = gate
        val o = open()
        o.vm.onAction(ImportAction.FilesChosen(PARTS))
        runCurrent()
        assertTrue(o.state.busy)
        assertEquals(RestoreProgress(1, 2), o.state.progress)
        gate.complete(Unit)
        runCurrent()
        assertFalse(o.state.busy)
        assertNull(o.state.progress)
        assertEquals(h.backup.restoreService.summary, o.state.summary)
    }

    @Test
    fun aPassphraseThatDoesNotOpenTheFilesIsReportedAsSuch() = runTest {
        h.backup.restoreService.failure = RestorePassphraseException(PART_NAMES.first())
        val o = open()
        o.vm.onAction(ImportAction.FilesChosen(PARTS))
        runCurrent()
        assertEquals(ImportError.WRONG_PASSPHRASE, o.state.error)
        assertNull(o.state.summary)
        assertFalse(o.state.busy)
        assertNull(o.state.progress)
    }

    @Test
    fun anyOtherFailureMeansNotAnExportOrDamaged() = runTest {
        h.backup.restoreService.failure = IllegalStateException()
        val o = open()
        o.vm.onAction(ImportAction.FilesChosen(PARTS))
        runCurrent()
        assertEquals(ImportError.DAMAGED, o.state.error)
        h.backup.restoreService.failure = null
        h.backup.readFailure = IOException()
        o.vm.onAction(ImportAction.FilesChosen(PARTS))
        runCurrent()
        assertEquals(ImportError.DAMAGED, o.state.error)
        assertEquals(1, h.backup.restoreService.runs.size)
    }

    @Test
    fun aNewRunClearsTheLastErrorAndSummary() = runTest {
        h.backup.restoreService.failure = IllegalStateException()
        val o = open()
        o.vm.onAction(ImportAction.FilesChosen(PARTS))
        runCurrent()
        h.backup.restoreService.failure = null
        o.vm.onAction(ImportAction.Start)
        runCurrent()
        assertNull(o.state.error)
        assertNull(o.state.summary)
    }

    @Test
    fun backLeavesTheScreen() = runTest {
        val o = open()
        o.vm.onAction(ImportAction.Back)
        runCurrent()
        assertEquals(listOf<SettingsEvent>(SettingsEvent.Back), o.events)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h1600dp-mdpi")
class ImportScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val h = SettingsHarness()
    private val registry = FakeResultRegistry()
    private val navigator = RecordingNavigator(listOf(Route.Settings, Route.SettingsStorage, Route.SettingsImport))

    private fun open() {
        val vm = ImportViewModel(h.deps)
        compose.showWith(navigator) { WithRegistry(registry) { ImportScreen(viewModel = vm) } }
        await(TestTags.Import.SCREEN)
    }

    private fun await(tag: String) {
        compose.waitUntil(WAIT_MS) { compose.exists(tag) }
    }

    private fun tap(tag: String) {
        await(tag)
        compose.onNodeWithTag(tag).performScrollTo().performClick()
        compose.waitForIdle()
    }

    private fun pickFiles(files: List<Uri>) {
        compose.waitUntil(WAIT_MS) { registry.launches.isNotEmpty() }
        val launch = registry.launches.last()
        assertTrue(launch.contract.javaClass.name, launch.contract is ActivityResultContracts.OpenMultipleDocuments)
        assertEquals(listOf("*/*"), (launch.input as Array<*>).toList())
        compose.runOnUiThread { registry.answer(files) }
        compose.waitForIdle()
    }

    @Test
    fun theIdleScreenShowsTheV031Texts() {
        open()
        assertTrue(compose.showsText(text(R.string.import_title)))
        assertTrue(compose.showsText(text(R.string.import_explain)))
        compose.onNodeWithTag(TestTags.Import.PASSPHRASE).assert(contains(text(R.string.import_passphrase)))
        compose.onNodeWithTag(TestTags.Import.START).assert(labeled(text(R.string.import_choose))).assertIsEnabled()
        for (tag in listOf(TestTags.Import.PROGRESS, TestTags.Import.SUMMARY, TestTags.Import.FAILED, TestTags.Import.ERROR)) assertFalse(tag, compose.exists(tag))
    }

    @Test
    fun choosingTheEncryptedPartsWithThePassphraseImportsThemLikeU29() {
        open()
        compose.onNodeWithTag(TestTags.Import.PASSPHRASE).performScrollTo().performTextInput(LONG_PASSPHRASE)
        tap(TestTags.Import.START)
        pickFiles(PARTS)
        await(TestTags.Import.SUMMARY)
        compose.onNodeWithTag(TestTags.Import.SUMMARY).assert(contains("Imported 2 entries"))
        compose.onNodeWithTag(TestTags.Import.SUMMARY).assert(contains(text(R.string.import_done, 2, 2, 0)))
        assertEquals(listOf(PART_NAMES to LONG_PASSPHRASE), h.backup.restoreService.runs.map { run -> run.first.map { it.name } to run.second })
        assertFalse(compose.exists(TestTags.Import.FAILED))
        assertFalse(compose.exists(TestTags.Import.ERROR))
    }

    @Test
    fun nothingChosenImportsNothing() {
        open()
        tap(TestTags.Import.START)
        pickFiles(emptyList())
        assertEquals(emptyList<Any>(), h.backup.restoreService.runs)
        assertFalse(compose.exists(TestTags.Import.SUMMARY))
        compose.onNodeWithTag(TestTags.Import.START).assertIsEnabled()
    }

    @Test
    fun whileImportingTheProgressShowsAndTheButtonIsBusy() {
        val gate = CompletableDeferred<Unit>()
        h.backup.restoreService.progressSteps = listOf(RestoreProgress(1, 2))
        h.backup.restoreService.progressGate = gate
        open()
        tap(TestTags.Import.START)
        pickFiles(PARTS)
        await(TestTags.Import.PROGRESS)
        compose.onNodeWithTag(TestTags.Import.PROGRESS, useUnmergedTree = true).assertTextEquals(text(R.string.import_progress, 1, 2))
        compose.onNodeWithTag(TestTags.Import.START).assertIsNotEnabled()
        gate.complete(Unit)
        await(TestTags.Import.SUMMARY)
        assertFalse(compose.exists(TestTags.Import.PROGRESS))
        compose.onNodeWithTag(TestTags.Import.START).assertIsEnabled()
    }

    @Test
    fun entriesThatFailedAreCounted() {
        h.backup.restoreService.summary = h.backup.restoreService.summary.copy(
            entries = 1,
            failed = listOf(RestoreFailure("2026/2026-02-02-0800/entry.md", RestoreFailureReason.MISSING_MEDIA)),
        )
        open()
        tap(TestTags.Import.START)
        pickFiles(PARTS)
        await(TestTags.Import.FAILED)
        compose.onNodeWithTag(TestTags.Import.FAILED, useUnmergedTree = true).assertTextEquals(plural(R.plurals.import_failed, 1, 1))
        compose.onNodeWithTag(TestTags.Import.SUMMARY).assert(contains(text(R.string.import_done, 1, 2, 0)))
    }

    @Test
    fun aWrongPassphraseAndDamagedFilesShowTheirV031Errors() {
        h.backup.restoreService.failure = RestorePassphraseException(PART_NAMES.first())
        open()
        tap(TestTags.Import.START)
        pickFiles(PARTS)
        await(TestTags.Import.ERROR)
        compose.onNodeWithTag(TestTags.Import.ERROR).assert(contains(text(R.string.import_passphrase_wrong)))
        h.backup.restoreService.failure = IllegalStateException()
        tap(TestTags.Import.START)
        pickFiles(PARTS)
        compose.waitUntil(WAIT_MS) { compose.showsText(text(R.string.import_error)) }
        compose.onNodeWithTag(TestTags.Import.ERROR).assert(contains(text(R.string.import_error)))
        assertFalse(compose.exists(TestTags.Import.SUMMARY))
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

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h1600dp-mdpi")
class BackupRoundTripFlowTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val h = SettingsHarness()
    private val registry = FakeResultRegistry()

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

    @Test
    fun u29TwinEncryptedExportThenRestoreWithThePassphraseThroughTheV031Ids() {
        val host = SettingsTestHost(h, listOf(Route.Settings, Route.SettingsStorage))
        compose.show { WithRegistry(registry) { host.Content() } }
        tap(TestTags.Settings.ROW_EXPORT)
        await(TestTags.Settings.EXPORT_SCREEN)
        tap(TestTags.Export.ENCRYPT)
        type(TestTags.Export.PASSPHRASE, LONG_PASSPHRASE)
        type(TestTags.Export.PASSPHRASE_CONFIRM, LONG_PASSPHRASE)
        tap(TestTags.Export.START)
        compose.waitUntil(WAIT_MS) { registry.launches.size == 1 }
        compose.runOnUiThread { registry.answer(PickedUris.FOLDER) }
        await(TestTags.Export.SUMMARY)
        assertEquals(listOf(FakeSettingsBackup.KDKX_LABEL), h.backup.exportService.destinations.map { (it as FakeDestination).label })
        tap(TestTags.Settings.BACK)
        await(TestTags.Settings.STORAGE_SCREEN)
        tap(TestTags.Settings.ROW_IMPORT)
        await(TestTags.Import.SCREEN)
        type(TestTags.Import.PASSPHRASE, LONG_PASSPHRASE)
        tap(TestTags.Import.START)
        compose.waitUntil(WAIT_MS) { registry.launches.size == 2 }
        compose.runOnUiThread { registry.answer(PARTS) }
        await(TestTags.Import.SUMMARY)
        compose.onNodeWithTag(TestTags.Import.SUMMARY).assert(contains("Imported 2 entries"))
        assertEquals(listOf(PART_NAMES to LONG_PASSPHRASE), h.backup.restoreService.runs.map { run -> run.first.map { it.name } to run.second })
        assertEquals(listOf(Route.Settings, Route.SettingsStorage, Route.SettingsImport), host.routes)
    }

    private companion object {
        const val WAIT_MS = 5_000L
    }
}
