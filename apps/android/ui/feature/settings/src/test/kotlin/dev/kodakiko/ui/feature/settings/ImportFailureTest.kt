@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.kodakiko.ui.feature.settings

import android.net.Uri
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ImportFailureTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val h = SettingsHarness()

    private fun chosen(): List<Uri> = listOf(PickedUris.PART_ONE)

    @Test
    fun aBackupTooLargeToReadIntoMemoryShowsTheErrorInsteadOfCrashingTheApp() = runTest {
        h.backup.readFailure = OutOfMemoryError("Failed to allocate a 116841960 byte allocation")
        val vm = ImportViewModel(h.deps)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        vm.onAction(ImportAction.FilesChosen(chosen()))
        runCurrent()
        assertEquals(ImportError.DAMAGED, vm.state.value.error)
        assertFalse(vm.state.value.busy)
        assertNull(vm.state.value.progress)
        assertEquals(emptyList<Any>(), h.backup.restoreService.runs)
    }

    @Test
    fun anyUnexpectedFailureInTheRestoreRunBecomesTheGenericError() = runTest {
        h.backup.restoreService.failure = StackOverflowError()
        val vm = ImportViewModel(h.deps)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        vm.onAction(ImportAction.FilesChosen(chosen()))
        runCurrent()
        assertEquals(ImportError.DAMAGED, vm.state.value.error)
        assertFalse(vm.state.value.busy)
        h.backup.restoreService.failure = null
        vm.onAction(ImportAction.FilesChosen(chosen()))
        runCurrent()
        assertNull(vm.state.value.error)
        assertEquals(h.backup.restoreService.summary, vm.state.value.summary)
    }
}
