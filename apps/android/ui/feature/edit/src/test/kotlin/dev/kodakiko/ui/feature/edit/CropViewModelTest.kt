@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.kodakiko.ui.feature.edit

import dev.kodakiko.domain.EntryEdit
import dev.kodakiko.service.entries.CropRect
import dev.kodakiko.service.entries.FULL_CROP
import dev.kodakiko.service.entries.ImportRefusal
import dev.kodakiko.service.entries.ImportSlots
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CropViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val h = CropHarness()

    private val rect = CropRect(0.1, 0.2, 0.5, 0.6)

    private fun TestScope.open(mediaId: String = P1): Pair<CropViewModel, Observed<CropState, CropEvent>> {
        val vm = CropViewModel(h.deps, ENTRY, mediaId)
        clearAtEnd(vm)
        return vm to observe(vm.state, vm.events, h.log)
    }

    @Test
    fun opensTheOriginalAtFullSizeWithTheWholeImageSelected() = runTest {
        h.store(metadata(media = listOf(photo(P1, 4000, 3000), photo(P2))))
        val (_, screen) = open()
        val image = requireNotNull(screen.value.image)
        assertSame(requireNotNull(h.originals.original).bitmap, image.bitmap)
        assertEquals(4000L, image.width)
        assertEquals(3000L, image.height)
        assertEquals(FULL_CROP, screen.value.rect)
        assertFalse(screen.value.busy)
        assertFalse(screen.value.failed)
        assertEquals(listOf(ENTRY to P1), h.originals.opened)
        assertEquals(emptyList<CropEvent>(), screen.events)
    }

    @Test
    fun aMissingOriginalLeavesTheCropScreen() = runTest {
        h.store(metadata())
        h.originals.original = null
        val (_, screen) = open()
        assertNull(screen.value.image)
        assertEquals(listOf<CropEvent>(CropEvent.Back), screen.events)
    }

    @Test
    fun theRectangleFollowsTheGestures() = runTest {
        h.store(metadata())
        val (vm, screen) = open()
        vm.onAction(CropAction.RectChanged(rect))
        assertEquals(rect, screen.value.rect)
    }

    @Test
    fun applyReplacesThePhotoWithTheCroppedCopyAtTheSamePositionWithTheSameCaption() = runTest {
        h.store(metadata(media = listOf(photo(P1), photo(P2, caption = "Heron at dawn"), video(VID))))
        val (vm, screen) = open(P2)
        vm.onAction(CropAction.RectChanged(rect))
        vm.onAction(CropAction.Apply)
        runCurrent()
        val original = requireNotNull(h.originals.original)
        assertEquals(listOf(rect), original.crops)
        assertEquals(ImportSlots(used = 2, limit = 20), h.imports.calls.single().slots)
        assertEquals(inputFile("cropped.jpg"), h.imports.calls.single().input)
        assertEquals(listOf(P1, CROPPED, VID), h.entries.meta().media.map { it.mediaId })
        assertEquals("Heron at dawn", h.entries.meta().media[1].caption)
        assertEquals(listOf(EntryEdit(mediaOrder = listOf(P1, CROPPED, VID), captions = mapOf(CROPPED to "Heron at dawn"))), h.entries.edits)
        assertEquals(listOf(P2), h.entries.removed)
        assertEquals(1, original.released)
        assertEquals(listOf<CropEvent>(CropEvent.Done), screen.events)
        assertTrue(h.log.indexOf("release") < h.log.indexOfFirst { it.startsWith("event:") })
    }

    @Test
    fun croppingTheOnlyPhotoLeavesOneItemWithANewId() = runTest {
        h.store(metadata(media = listOf(photo(P1))))
        val (vm, screen) = open()
        vm.onAction(CropAction.RectChanged(rect))
        vm.onAction(CropAction.Apply)
        runCurrent()
        assertEquals(listOf(CROPPED), h.entries.meta().media.map { it.mediaId })
        assertEquals(ImportSlots(used = 0, limit = 20), h.imports.calls.single().slots)
        assertEquals(listOf<CropEvent>(CropEvent.Done), screen.events)
    }

    @Test
    fun applyIsBusyUntilTheReplaceIsDone() = runTest {
        h.store(metadata())
        val (vm, screen) = open()
        val gate = CompletableDeferred<Unit>()
        requireNotNull(h.originals.original).gate = gate
        vm.onAction(CropAction.Apply)
        runCurrent()
        assertTrue(screen.value.busy)
        vm.onAction(CropAction.Apply)
        runCurrent()
        gate.complete(Unit)
        runCurrent()
        assertEquals(1, requireNotNull(h.originals.original).crops.size)
        assertEquals(1, h.imports.calls.size)
        assertEquals(listOf<CropEvent>(CropEvent.Done), screen.events)
    }

    @Test
    fun aRefusedImportShowsTheCropErrorAndChangesNothing() = runTest {
        h.store(metadata())
        h.imports.refuse = ImportRefusal.UNDECODABLE
        val (vm, screen) = open()
        vm.onAction(CropAction.Apply)
        runCurrent()
        assertTrue(screen.value.failed)
        assertFalse(screen.value.busy)
        assertEquals(listOf(P1, P2), h.entries.meta().media.map { it.mediaId })
        assertEquals(0, requireNotNull(h.originals.original).released)
        assertEquals(emptyList<CropEvent>(), screen.events)
    }

    @Test
    fun aFailedCropCanBeAppliedAgain() = runTest {
        h.store(metadata())
        val (vm, screen) = open()
        val original = requireNotNull(h.originals.original)
        original.failure = IllegalStateException("decode failed")
        vm.onAction(CropAction.Apply)
        runCurrent()
        assertTrue(screen.value.failed)
        original.failure = null
        vm.onAction(CropAction.Apply)
        runCurrent()
        assertFalse(screen.value.failed)
        assertEquals(listOf(CROPPED, P2), h.entries.meta().media.map { it.mediaId })
        assertEquals(listOf<CropEvent>(CropEvent.Done), screen.events)
    }

    @Test
    fun theImportLimitComesFromTheServerConfig() = runTest {
        h.store(metadata())
        h.config.config = publicConfig(maxMedia = 7)
        val (vm, _) = open()
        vm.onAction(CropAction.Apply)
        runCurrent()
        assertEquals(ImportSlots(used = 1, limit = 7), h.imports.calls.single().slots)
    }

    @Test
    fun cancelReleasesTheOriginalAndGoesBack() = runTest {
        h.store(metadata())
        val (vm, screen) = open()
        vm.onAction(CropAction.Cancel)
        runCurrent()
        assertEquals(1, requireNotNull(h.originals.original).released)
        assertEquals(listOf<CropEvent>(CropEvent.Back), screen.events)
        assertEquals(emptyList<ImportSlots>(), h.imports.calls.map { it.slots })
    }

    @Test
    fun leavingTheScreenReleasesTheOriginalOnce() = runTest {
        h.store(metadata())
        val (vm, _) = open()
        clear(vm)
        assertEquals(1, requireNotNull(h.originals.original).released)
        val (applied, _) = open()
        applied.onAction(CropAction.Apply)
        runCurrent()
        clear(applied)
        assertEquals(2, requireNotNull(h.originals.original).released)
    }
}
