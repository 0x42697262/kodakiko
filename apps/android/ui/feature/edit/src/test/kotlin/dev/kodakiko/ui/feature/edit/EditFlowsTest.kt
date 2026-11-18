package dev.kodakiko.ui.feature.edit

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.AnnotatedString
import dev.kodakiko.contract.MediaKind
import dev.kodakiko.service.entries.MediaInput
import dev.kodakiko.service.entries.MediaOrigin
import dev.kodakiko.ui.core.TestTags
import dev.kodakiko.ui.core.TestTags.listItem
import dev.kodakiko.ui.core.navigation.Route
import dev.kodakiko.ui.core.notice.GalleryNotice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h1280dp-mdpi")
class EditFlowsTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val h = EditHarness()
    private val events = mutableListOf<EditEvent>()
    private val cropEvents = mutableListOf<CropEvent>()
    private val screen = mutableStateOf<@Composable () -> Unit>({})

    private fun start() {
        compose.show { screen.value() }
    }

    private fun openEdit(isNew: Boolean = false): EditViewModel {
        val vm = EditViewModel(h.deps, ENTRY, isNew)
        screen.value = {
            val state by vm.state.collectAsState()
            LaunchedEffect(vm) { vm.events.collect { events += it } }
            EditContent(state = state, onAction = vm::onAction)
        }
        compose.waitForIdle()
        return vm
    }

    private fun openCrop(deps: CropDeps, mediaId: String): CropViewModel {
        val vm = CropViewModel(deps, ENTRY, mediaId)
        screen.value = {
            val state by vm.state.collectAsState()
            LaunchedEffect(vm) { vm.events.collect { cropEvents += it } }
            CropContent(state = state, onAction = vm::onAction)
        }
        compose.waitForIdle()
        return vm
    }

    private fun node(tag: String): SemanticsNodeInteraction = compose.onNodeWithTag(tag)

    private fun click(tag: String) {
        node(tag).performScrollTo().performClick()
        compose.waitForIdle()
    }

    private fun tap(tag: String) {
        node(tag).performClick()
        compose.waitForIdle()
    }

    private fun clickInSheet(tag: String) {
        node(tag).performClick()
        compose.waitForIdle()
    }

    private fun editable(text: String) = SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(text))

    @Test
    fun u34TheNoteIsSavedWithoutASaveButtonAndTheToolbarWritesMarkdown() {
        h.store(metadata(media = listOf(photo(P1)), note = "First draft"))
        start()
        openEdit()
        assertTrue(compose.exists(TestTags.Edit.SCREEN))
        assertFalse(compose.exists(TestTags.Compose.NOTE))
        click(TestTags.Edit.EDIT_NOTE)
        node(TestTags.Compose.NOTE).performTextReplacement("Lake day ")
        compose.waitForIdle()
        click(Ids.BOLD)
        node(TestTags.Compose.NOTE).performTextInput("swim")
        compose.waitForIdle()
        node(TestTags.Compose.NOTE).assert(editable("Lake day **swim**"))
        tap(TestTags.Compose.BACK)
        assertEquals(listOf<EditEvent>(EditEvent.Back), events)
        assertEquals("Lake day **swim**", h.entries.meta().note)
        openEdit()
        assertTrue(compose.exists(TestTags.Markdown.VIEW))
        assertTrue(compose.onAllNodes(hasText("Lake day swim", substring = true), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun u35ACaptionShowsOnItsPhotoAndIsStored() {
        h.store(metadata(media = listOf(photo(P1))))
        start()
        openEdit()
        click(listItem(TestTags.Edit.MEDIA_MENU, 0))
        clickInSheet(TestTags.Edit.CAPTION)
        node(TestTags.Edit.CAPTION_FIELD).performTextReplacement("Heron at dawn")
        clickInSheet(TestTags.Edit.CAPTION_SAVE)
        node(listItem(TestTags.Edit.CAPTION_TEXT, 0)).assertTextEquals("Heron at dawn")
        assertFalse(compose.exists(TestTags.Edit.CAPTION_SHEET))
        tap(TestTags.Compose.BACK)
        assertEquals("Heron at dawn", h.entries.meta().media.single().caption)
    }

    @Test
    fun u36SetAsCoverMovesAnItemFirstAndRemovingAsksFirstAndKeepsTheLastItem() {
        h.store(metadata(media = listOf(photo(P1), video(VID))))
        start()
        openEdit()
        assertTrue(compose.exists(TestTags.Edit.DOTS))
        click(listItem(TestTags.Edit.DOT, 1))
        click(listItem(TestTags.Edit.MEDIA_MENU, 1))
        clickInSheet(TestTags.Edit.SET_COVER)
        tap(TestTags.Compose.BACK)
        assertEquals(listOf(MediaKind.VIDEO, MediaKind.PHOTO), h.entries.meta().media.map { it.kind })

        openEdit()
        click(listItem(TestTags.Edit.DOT, 1))
        click(listItem(TestTags.Edit.MEDIA_MENU, 1))
        clickInSheet(TestTags.Compose.REMOVE_ITEM)
        clickInSheet(TestTags.Edit.REMOVE_CONFIRM)
        assertFalse(compose.exists(TestTags.Edit.DOTS))
        click(listItem(TestTags.Edit.MEDIA_MENU, 0))
        clickInSheet(TestTags.Compose.REMOVE_ITEM)
        assertTrue(compose.exists(TestTags.Edit.REMOVE_SHEET))
        assertFalse(compose.exists(TestTags.Edit.REMOVE_CONFIRM))
        assertEquals(listOf(VID), h.entries.meta().media.map { it.mediaId })
    }

    @Test
    fun u37CroppingReplacesThePhotoWithTheCroppedCopy() {
        h.store(metadata(media = listOf(photo(P1, caption = "crop me"))))
        start()
        openEdit()
        click(listItem(TestTags.Edit.MEDIA_MENU, 0))
        clickInSheet(TestTags.Edit.CROP)
        assertEquals(listOf<EditEvent>(EditEvent.Open(Route.EntryCrop(ENTRY, P1))), events)

        val crop = CropHarness()
        crop.entries.stored.putAll(h.entries.stored)
        openCrop(crop.deps, P1)
        val handle = node(Ids.BOTTOM_RIGHT)
        handle.performTouchInput { down(center) }
        compose.waitForIdle()
        handle.performTouchInput {
            for (step in 1..DRAG_STEPS) moveBy(Offset(-DRAG_X / DRAG_STEPS, -DRAG_Y / DRAG_STEPS))
            up()
        }
        compose.waitForIdle()
        clickInSheet(TestTags.Crop.APPLY)
        assertEquals(listOf<CropEvent>(CropEvent.Done), cropEvents)
        val rect = requireNotNull(crop.originals.original).crops.single()
        assertEquals(0.0, rect.x, 0.0)
        assertTrue(rect.width < 1.0 && rect.height < 1.0)
        val after = crop.entries.meta().media
        assertEquals(1, after.size)
        assertNotEquals(P1, after.single().mediaId)
        assertEquals("crop me", after.single().caption)

        h.entries.stored.putAll(crop.entries.stored)
        openEdit()
        assertTrue(compose.exists(listItem(TestTags.Edit.PAGE, 0)))
        node(listItem(TestTags.Edit.CAPTION_TEXT, 0)).assertTextEquals("crop me")
    }

    @Test
    fun u39PastingAPictureAddsAPageAndADot() {
        h.store(metadata(media = listOf(photo(P1), photo(P2))))
        h.clipboard.image = MediaInput(MediaOrigin.PASTE, File("/tmp/kodakiko-edit-test/screenshot.png"), null, null)
        start()
        openEdit()
        assertTrue(compose.exists(listItem(TestTags.Edit.DOT, 1)))
        assertFalse(compose.exists(listItem(TestTags.Edit.DOT, 2)))
        click(TestTags.Edit.PASTE)
        assertTrue(compose.exists(listItem(TestTags.Edit.DOT, 2)))
        click(listItem(TestTags.Edit.DOT, 2))
        assertTrue(compose.exists(listItem(TestTags.Edit.PAGE, 2)))
        assertEquals(listOf(P1, P2, PASTED), h.entries.meta().media.map { it.mediaId })
    }

    @Test
    fun u40AVoiceNoteRecordedOnTheEditPageIsSavedAndListed() {
        h.store(metadata(media = listOf(photo(P1)), note = "talk"))
        start()
        openEdit()
        click(TestTags.Edit.RECORD_VOICE)
        assertTrue(compose.exists(TestTags.Voice.RECORDING_TILE))
        click(TestTags.Voice.STOP)
        assertFalse(compose.exists(TestTags.Voice.RECORDING_TILE))
        assertTrue(compose.exists(listItem(TestTags.Voice.TILE, 0)))
        node(listItem(TestTags.Voice.DURATION, 0)).assertTextEquals("00:02")
        assertFalse(compose.exists(TestTags.Edit.DOTS))
        tap(TestTags.Compose.BACK)
        assertEquals(listOf(MediaKind.PHOTO, MediaKind.AUDIO), h.entries.meta().media.map { it.kind })
    }

    @Test
    fun aNewEntryLeavesForHomeWithTheSavedNotice() {
        h.store(metadata(media = listOf(photo(P1))))
        start()
        openEdit(isNew = true)
        assertTrue(compose.exists(TestTags.Compose.SCREEN))
        tap(TestTags.Compose.SAVE)
        assertEquals(listOf<EditEvent>(EditEvent.Home), events)
        assertTrue(h.notices.value is GalleryNotice.Saved)
    }

    private companion object {
        const val DRAG_X = 150f
        const val DRAG_Y = 100f
        const val DRAG_STEPS = 25
    }

    private object Ids {
        const val BOLD = "toolbar.bold"
        const val BOTTOM_RIGHT = "crop.bottomRight"
    }
}
