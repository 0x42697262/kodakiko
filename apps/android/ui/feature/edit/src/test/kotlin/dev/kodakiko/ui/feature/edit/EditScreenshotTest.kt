package dev.kodakiko.ui.feature.edit

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import dev.kodakiko.contract.MediaKind
import dev.kodakiko.data.db.AppTheme
import dev.kodakiko.markdown.TextSelection
import dev.kodakiko.service.entries.CropRect
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = Screenshots.QUALIFIERS)
class EditScreenshotTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun edit(name: String, state: EditState, appTheme: AppTheme = AppTheme.LIGHT) {
        compose.show(appTheme) { EditContent(state = state, onAction = {}) }
        compose.onRoot().captureScreenshot("edit/$name")
    }

    private fun editScreen(name: String, state: EditState) {
        compose.show { EditContent(state = state, onAction = {}) }
        captureScreenScreenshot("edit/$name")
    }

    private fun crop(name: String, state: CropState, appTheme: AppTheme = AppTheme.LIGHT) {
        compose.show(appTheme) { CropContent(state = state, onAction = {}) }
        compose.onRoot().captureScreenshot("crop/$name")
    }

    private fun entryNew(name: String, state: EntryNewState) {
        compose.show { EntryNewContent(state = state) }
        compose.onRoot().captureScreenshot("entry_new/$name")
    }

    @Test
    @Config(qualifiers = Screenshots.TALL_QUALIFIERS)
    fun fullEntry() = edit("full_entry", EditStates.full)

    @Test
    @Config(qualifiers = Screenshots.TALL_QUALIFIERS)
    fun fullEntryDark() = edit("full_entry_dark", EditStates.full, AppTheme.DARK)

    @Test
    fun newEntry() = edit("new_entry", EditStates.newEntry)

    @Test
    fun loading() = edit("loading", EditStates.state(body = null))

    @Test
    fun pasteAndFavorite() = edit("paste_favorite", EditStates.full.copy(canPaste = true))

    @Test
    fun videoPage() = edit("video_page", EditStates.full.copy(page = 1))

    @Test
    @Config(qualifiers = Screenshots.TALL_QUALIFIERS)
    fun noteEditor() = edit("note_editor", EditStates.editing("Lake day **swim**\n\n- [ ] towels", TextSelection(11, 15)))

    @Test
    @Config(qualifiers = Screenshots.TALL_QUALIFIERS)
    fun recording() = edit("recording", EditStates.full.copy(recording = RecordingUi(83_000)))

    @Test
    @Config(qualifiers = Screenshots.TALL_QUALIFIERS)
    fun failures() = edit("failures", EditStates.full.copy(failed = true, voiceFailed = true))

    @Test
    @Config(qualifiers = Screenshots.TALL_QUALIFIERS)
    fun timeEditorInvalid() = edit("time_editor_invalid", EditStates.full.copy(timeEditor = TimeEditorUi("2026-02-30", "10:00", invalid = true)))

    @Test
    fun mediaMenu() = editScreen("media_menu", EditStates.full.copy(page = 2, sheet = EditSheet.MediaMenu(P2, MediaKind.PHOTO, canSetCover = true)))

    @Test
    fun captionSheet() = editScreen("caption_sheet", EditStates.full.copy(sheet = EditSheet.Caption(P1, "Heron at dawn")))

    @Test
    fun removeConfirm() = editScreen("remove_confirm", EditStates.full.copy(sheet = EditSheet.Remove(VID, MediaKind.VIDEO, last = false)))

    @Test
    fun removeLastItem() = editScreen("remove_last_item", EditStates.newEntry.copy(sheet = EditSheet.Remove(P1, MediaKind.PHOTO, last = true)))

    @Test
    fun chooseTags() = editScreen("choose_tags", EditStates.full.copy(sheet = EditSheet.ChooseTags(listOf("beach", "city", "family", "zoo"))))

    @Test
    fun cropWholeImage() = crop("whole_image", EditStates.crop())

    @Test
    fun cropPartial() = crop("partial", EditStates.crop(rect = CropRect(0.15, 0.2, 0.55, 0.5)))

    @Test
    fun cropPartialDark() = crop("partial_dark", EditStates.crop(rect = CropRect(0.15, 0.2, 0.55, 0.5)), AppTheme.DARK)

    @Test
    fun cropBusy() = crop("busy", EditStates.crop(rect = CropRect(0.15, 0.2, 0.55, 0.5), busy = true))

    @Test
    fun cropFailed() = crop("failed", EditStates.crop(failed = true))

    @Test
    fun entryNewCreating() = entryNew("creating", EntryNewState(failed = false))

    @Test
    fun entryNewFailed() = entryNew("failed", EntryNewState(failed = true))
}
