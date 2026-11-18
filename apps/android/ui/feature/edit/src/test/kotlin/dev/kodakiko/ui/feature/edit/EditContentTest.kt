package dev.kodakiko.ui.feature.edit

import androidx.activity.ComponentActivity
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import dev.kodakiko.contract.MediaKind
import dev.kodakiko.markdown.MarkdownAction
import dev.kodakiko.markdown.TextSelection
import dev.kodakiko.ui.core.TestTags
import dev.kodakiko.ui.core.TestTags.listItem
import dev.kodakiko.ui.core.navigation.CaptureMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h1280dp-mdpi")
class EditContentTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val actions = mutableListOf<EditAction>()

    private fun show(state: EditState) {
        compose.show { EditContent(state = state, onAction = { actions += it }) }
    }

    private fun showSwitchable(initial: EditState): MutableState<EditState> {
        val holder = mutableStateOf(initial)
        compose.show { EditContent(state = holder.value, onAction = { actions += it }) }
        return holder
    }

    private fun MutableState<EditState>.switchTo(next: EditState) {
        value = next
        compose.waitForIdle()
    }

    private fun taps(): List<EditAction> = actions.filterNot { it is EditAction.PageChanged || it is EditAction.ScreenVisible }

    private fun node(tag: String): SemanticsNodeInteraction = compose.onNodeWithTag(tag)

    private fun click(tag: String) {
        node(tag).performScrollTo().performClick()
        compose.waitForIdle()
    }

    private fun tap(tag: String) {
        node(tag).performClick()
        compose.waitForIdle()
    }

    private fun shows(text: String): Boolean = compose.onAllNodes(hasText(text), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun editable(text: String) = SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(text))

    private fun toggle(state: ToggleableState) = SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, state)

    private fun clickWord(word: String) {
        val hasLayout = SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult)
        val nodes = compose.onAllNodes(hasText(word, substring = true) and hasLayout, useUnmergedTree = true)
        val results = mutableListOf<TextLayoutResult>()
        nodes.fetchSemanticsNodes().first().config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        val layout = results.single()
        val box = layout.getBoundingBox(layout.layoutInput.text.text.indexOf(word) + word.length / 2)
        nodes[0].performTouchInput { click(Offset(box.center.x, box.center.y)) }
        compose.waitForIdle()
    }

    @Test
    fun anExistingEntryShowsEditEntryWithBackDoneAndTheMoreMenu() {
        show(EditStates.full)
        assertTrue(compose.exists(TestTags.Edit.SCREEN))
        assertFalse(compose.exists(TestTags.Compose.SCREEN))
        assertTrue(shows("Edit Entry"))
        node(TestTags.Compose.SAVE).assert(hasContentDescription("Done"))
        tap(TestTags.Compose.BACK)
        tap(TestTags.Compose.SAVE)
        tap(Ids.MORE)
        node(TestTags.Edit.VIEW).assertTextEquals("View Entry")
        tap(TestTags.Edit.VIEW)
        tap(Ids.MORE)
        node(TestTags.Edit.TRASH).assertTextEquals("Move to Trash")
        tap(TestTags.Edit.TRASH)
        assertEquals(listOf(EditAction.Back, EditAction.Done, EditAction.ViewEntry, EditAction.Trash), taps())
    }

    @Test
    fun aNewEntryShowsNewEntryOnTheComposeScreen() {
        show(EditStates.newEntry)
        assertTrue(compose.exists(TestTags.Compose.SCREEN))
        assertFalse(compose.exists(TestTags.Edit.SCREEN))
        assertTrue(shows("New Entry"))
        assertFalse(shows("Edit Entry"))
        tap(TestTags.Compose.SAVE)
        assertEquals(listOf<EditAction>(EditAction.Done), taps())
    }

    @Test
    fun whileLoadingOnlyTheSpinnerShows() {
        show(EditStates.state(body = null))
        assertTrue(compose.exists(TestTags.Edit.SCREEN))
        assertTrue(compose.exists(TestTags.Edit.LOADING))
        assertFalse(compose.exists(TestTags.Edit.PAGER))
        assertFalse(compose.exists(TestTags.Edit.TAKE_PHOTO))
        assertFalse(compose.exists(TestTags.Compose.WHEN_VALUE))
    }

    @Test
    fun theSectionsAreLabeledAndTheFailureBannerShowsOnlyAfterAFailedWrite() {
        show(EditStates.full)
        assertTrue(shows("When"))
        assertTrue(shows("Where"))
        assertTrue(shows("Tags"))
        assertTrue(shows("Note"))
        assertFalse(compose.exists(TestTags.Edit.FAILED))
        assertFalse(compose.exists(TestTags.Edit.LOADING))
    }

    @Test
    fun aFailedWriteShowsCouldNotSave() {
        show(EditStates.full.copy(failed = true))
        node(TestTags.Edit.FAILED).performScrollTo().assertTextContains("Couldn't save. Try again.")
    }

    @Test
    fun thePagerShowsPhotosAndVideosWithCaptionsAndPageDots() {
        show(EditStates.full)
        assertTrue(compose.exists(TestTags.Edit.PAGER))
        assertTrue(compose.exists(listItem(TestTags.Edit.PAGE, 0)))
        node(listItem(TestTags.Edit.CAPTION_TEXT, 0)).assertTextEquals("Heron at dawn")
        assertTrue(compose.exists(TestTags.Edit.DOTS))
        assertEquals(3, (0..3).count { compose.exists(listItem(TestTags.Edit.DOT, it)) })
        node(listItem(TestTags.Edit.DOT, 0)).assert(hasContentDescription("Page 1 of 3")).assertIsSelected()
        node(listItem(TestTags.Edit.DOT, 2)).assert(hasContentDescription("Page 3 of 3")).assertIsNotSelected()
        click(listItem(TestTags.Edit.DOT, 2))
        assertTrue(EditAction.PageChanged(2) in actions)
    }

    @Test
    fun theDotOfTheCurrentPageIsSelected() {
        show(EditStates.full.copy(page = 2))
        node(listItem(TestTags.Edit.DOT, 2)).assertIsSelected()
        node(listItem(TestTags.Edit.DOT, 0)).assertIsNotSelected()
        assertTrue(compose.exists(listItem(TestTags.Edit.PAGE, 2)))
    }

    @Test
    fun oneItemHasNoPageDots() {
        show(EditStates.newEntry)
        assertTrue(compose.exists(listItem(TestTags.Edit.PAGE, 0)))
        assertFalse(compose.exists(TestTags.Edit.DOTS))
        assertFalse(compose.exists(listItem(TestTags.Edit.DOT, 0)))
    }

    @Test
    fun aPageWithoutCaptionShowsNoCaptionBar() {
        show(EditStates.state())
        assertFalse(compose.exists(listItem(TestTags.Edit.CAPTION_TEXT, 0)))
    }

    @Test
    fun swipingThePagerReportsTheNewPage() {
        show(EditStates.full)
        node(TestTags.Edit.PAGER).performTouchInput { swipeLeft() }
        compose.waitForIdle()
        assertEquals(EditAction.PageChanged(1), actions.filterIsInstance<EditAction.PageChanged>().last())
    }

    @Test
    fun tappingAVideoPageOpensThePlayer() {
        show(EditStates.full.copy(page = 1))
        click(listItem(TestTags.Edit.PLAY, 1))
        assertEquals(listOf<EditAction>(EditAction.OpenPlayer(VID)), taps())
    }

    @Test
    fun eachPageHasItsOwnOptionsButton() {
        show(EditStates.full)
        node(listItem(TestTags.Edit.MEDIA_MENU, 0)).assert(hasContentDescription("Photo or video options"))
        click(listItem(TestTags.Edit.MEDIA_MENU, 0))
        assertEquals(listOf<EditAction>(EditAction.OpenMediaMenu(P1)), taps())
    }

    @Test
    fun theItemMenuOffersEveryActionForALaterPhoto() {
        show(EditStates.full.copy(page = 2, sheet = EditSheet.MediaMenu(P2, MediaKind.PHOTO, canSetCover = true)))
        assertTrue(compose.exists(TestTags.Edit.MEDIA_MENU))
        node(TestTags.Edit.CAPTION).assertTextContains("Caption")
        node(TestTags.Edit.SET_COVER).assertTextContains("Set as Cover")
        node(TestTags.Edit.SHARE_ITEM).assertTextContains("Share")
        node(TestTags.Edit.SAVE_TO_DEVICE).assertTextContains("Save to Device")
        node(TestTags.Edit.CROP).assertTextContains("Crop")
        node(TestTags.Compose.REMOVE_ITEM).assertTextContains("Remove")
        for (tag in listOf(TestTags.Edit.CAPTION, TestTags.Edit.SET_COVER, TestTags.Edit.SHARE_ITEM, TestTags.Edit.SAVE_TO_DEVICE, TestTags.Edit.CROP, TestTags.Compose.REMOVE_ITEM)) {
            node(tag).performClick()
            compose.waitForIdle()
        }
        assertEquals(
            listOf(
                EditAction.EditCaption(P2),
                EditAction.SetCover(P2),
                EditAction.ShareItem(P2),
                EditAction.SaveToDevice(P2),
                EditAction.Crop(P2),
                EditAction.AskRemove(P2),
            ),
            taps(),
        )
    }

    @Test
    fun theFirstItemCannotBeSetAsCoverAndVideosCannotBeCropped() {
        val screen = showSwitchable(EditStates.full.copy(sheet = EditSheet.MediaMenu(P1, MediaKind.PHOTO, canSetCover = false)))
        assertFalse(compose.exists(TestTags.Edit.SET_COVER))
        assertTrue(compose.exists(TestTags.Edit.CROP))
        screen.switchTo(EditStates.full.copy(page = 1, sheet = EditSheet.MediaMenu(VID, MediaKind.VIDEO, canSetCover = true)))
        assertTrue(compose.exists(TestTags.Edit.SET_COVER))
        assertFalse(compose.exists(TestTags.Edit.CROP))
        assertTrue(compose.exists(TestTags.Edit.SAVE_TO_DEVICE))
    }

    @Test
    fun theCaptionSheetEditsOneLineOfUpTo64Characters() {
        show(EditStates.full.copy(sheet = EditSheet.Caption(P1, "Heron at dawn")))
        assertTrue(compose.exists(TestTags.Edit.CAPTION_SHEET))
        node(TestTags.Edit.CAPTION_FIELD).assert(editable("Heron at dawn"))
        node(TestTags.Edit.CAPTION_FIELD).performTextReplacement("Heron at dusk ")
        node(TestTags.Edit.CAPTION_SAVE).assertTextContains("Save Caption").performClick()
        compose.waitForIdle()
        val saved = taps().filterIsInstance<EditAction.SaveCaption>().single()
        assertEquals(P1, saved.mediaId)
        assertEquals("Heron at dusk", saved.caption.trim())
        node(TestTags.Edit.CAPTION_FIELD).performTextReplacement("x".repeat(70))
        node(TestTags.Edit.CAPTION_FIELD).assert(editable("x".repeat(64)))
    }

    @Test
    fun theKeyboardDoneKeySavesTheCaption() {
        show(EditStates.full.copy(sheet = EditSheet.Caption(P2, "")))
        node(TestTags.Edit.CAPTION_FIELD).assert(editable(""))
        node(TestTags.Edit.CAPTION_FIELD).performTextInput("Sunset")
        node(TestTags.Edit.CAPTION_FIELD).performImeAction()
        compose.waitForIdle()
        assertEquals(listOf<EditAction>(EditAction.SaveCaption(P2, "Sunset")), taps().filterIsInstance<EditAction.SaveCaption>())
    }

    @Test
    fun removeAsksWithTheKindOfItem() {
        show(EditStates.full.copy(sheet = EditSheet.Remove(P2, MediaKind.PHOTO, last = false)))
        assertTrue(compose.exists(TestTags.Edit.REMOVE_SHEET))
        assertTrue(shows("Remove this photo from the entry?"))
        node(TestTags.Edit.REMOVE_CONFIRM).assertTextContains("Remove").performClick()
        compose.waitForIdle()
        assertEquals(listOf<EditAction>(EditAction.Remove(P2)), taps())
    }

    @Test
    fun removingAVideoNamesIt() {
        show(EditStates.full.copy(sheet = EditSheet.Remove(VID, MediaKind.VIDEO, last = false)))
        assertTrue(shows("Remove this video from the entry?"))
    }

    @Test
    fun theLastItemOnlyExplainsWhyItStays() {
        show(EditStates.newEntry.copy(sheet = EditSheet.Remove(P1, MediaKind.PHOTO, last = true)))
        assertTrue(compose.exists(TestTags.Edit.REMOVE_SHEET))
        assertTrue(shows("An entry needs at least one item."))
        assertFalse(compose.exists(TestTags.Edit.REMOVE_CONFIRM))
    }

    @Test
    fun theActionBarOpensCaptureFavoritesRecordsAndShares() {
        show(EditStates.state())
        node(TestTags.Edit.TAKE_PHOTO).assert(hasContentDescription("Take a photo"))
        node(TestTags.Edit.RECORD_VIDEO).assert(hasContentDescription("Record a video"))
        node(TestTags.Edit.ADD_FROM_DEVICE).assert(hasContentDescription("Add from device"))
        node(TestTags.Edit.FAVORITE).assert(hasContentDescription("Favorite"))
        node(TestTags.Edit.RECORD_VOICE).assert(hasContentDescription("Record a voice note"))
        node(TestTags.Edit.SHARE).assert(hasContentDescription("Share"))
        assertFalse(compose.exists(TestTags.Edit.PASTE))
        for (tag in listOf(TestTags.Edit.TAKE_PHOTO, TestTags.Edit.RECORD_VIDEO, TestTags.Edit.ADD_FROM_DEVICE, TestTags.Edit.FAVORITE, TestTags.Edit.RECORD_VOICE, TestTags.Edit.SHARE)) click(tag)
        assertEquals(
            listOf(
                EditAction.Capture(CaptureMode.PHOTO),
                EditAction.Capture(CaptureMode.VIDEO),
                EditAction.AddFromDevice,
                EditAction.ToggleFavorite,
                EditAction.ToggleRecording,
                EditAction.Share,
            ),
            taps(),
        )
    }

    @Test
    fun aFavoriteOffersToRemoveItAndPasteShowsWithAnImageOnTheClipboard() {
        show(EditStates.full.copy(canPaste = true))
        node(TestTags.Edit.FAVORITE).assert(hasContentDescription("Remove from favorites"))
        node(TestTags.Edit.PASTE).assert(hasContentDescription("Paste a picture"))
        click(TestTags.Edit.PASTE)
        assertEquals(listOf<EditAction>(EditAction.Paste), taps())
    }

    @Test
    fun whileRecordingATileShowsTheTimerAndAStopButton() {
        show(EditStates.state(recording = RecordingUi(2_050)))
        assertTrue(compose.exists(TestTags.Voice.RECORDING_TILE))
        node(TestTags.Voice.ELAPSED).assertTextEquals("Recording 00:02")
        node(TestTags.Voice.STOP).assert(hasContentDescription("Stop recording"))
        node(TestTags.Edit.RECORD_VOICE).assert(hasContentDescription("Stop recording"))
        click(TestTags.Voice.STOP)
        assertEquals(listOf<EditAction>(EditAction.StopRecording), taps())
    }

    @Test
    fun withoutARecordingThereIsNoTile() {
        show(EditStates.state())
        assertFalse(compose.exists(TestTags.Voice.RECORDING_TILE))
        assertFalse(compose.exists(TestTags.Voice.FAILED))
    }

    @Test
    fun aMicrophoneProblemShowsTheVoiceBanner() {
        show(EditStates.state(voiceFailed = true))
        node(TestTags.Voice.FAILED).performScrollTo().assertTextContains("Couldn't record. Check the microphone permission.")
    }

    @Test
    fun voiceNotesShowTheirLengthPlayAndRemove() {
        show(EditStates.full)
        assertTrue(compose.exists(TestTags.Voice.LIST))
        node(listItem(TestTags.Voice.TILE, 0)).assert(hasContentDescription("Voice note 1"))
        node(listItem(TestTags.Voice.DURATION, 0)).assertTextEquals("00:20")
        node(listItem(TestTags.Voice.DURATION, 1)).assertTextEquals("01:05")
        node(listItem(TestTags.Voice.PLAY, 0)).assert(hasContentDescription("Play the voice note"))
        node(listItem(TestTags.Voice.REMOVE, 0)).assert(hasContentDescription("Remove the voice note"))
        click(listItem(TestTags.Voice.PLAY, 1))
        click(listItem(TestTags.Voice.REMOVE, 0))
        assertEquals(listOf(EditAction.ToggleVoice(VOX2), EditAction.RemoveVoice(VOX1)), taps())
    }

    @Test
    fun aPlayingVoiceNoteOffersPause() {
        show(EditStates.full.copy(voicePlaying = VOX1))
        node(listItem(TestTags.Voice.PLAY, 0)).assert(hasContentDescription("Pause the voice note"))
        node(listItem(TestTags.Voice.PLAY, 1)).assert(hasContentDescription("Play the voice note"))
    }

    @Test
    fun theOnlyItemsVoiceNoteHasNoRemoveButton() {
        show(EditStates.state(body = EditStates.body(pages = emptyList(), voiceNotes = listOf(EditVoiceNote(VOX1, 20_000)))))
        assertTrue(compose.exists(listItem(TestTags.Voice.TILE, 0)))
        assertFalse(compose.exists(listItem(TestTags.Voice.REMOVE, 0)))
        assertFalse(compose.exists(TestTags.Edit.DOTS))
    }

    @Test
    fun withoutVoiceNotesThereIsNoList() {
        show(EditStates.state())
        assertFalse(compose.exists(TestTags.Voice.LIST))
    }

    @Test
    fun whenShowsTheEntryTimeAndThePencilOpensTheEditor() {
        show(EditStates.state())
        node(TestTags.Compose.WHEN_VALUE).assertTextEquals("Today, 09:00")
        node(TestTags.Compose.CHANGE).assert(hasContentDescription("Change time"))
        click(TestTags.Compose.CHANGE)
        assertEquals(listOf<EditAction>(EditAction.ToggleTimeEditor), taps())
        assertFalse(compose.exists(TestTags.TimeEditor.DATE))
    }

    @Test
    fun olderTimesShowTheWeekdayOrTheYear() {
        val screen = showSwitchable(EditStates.state(body = EditStates.body(time = EditTime("2026-11-09T17:00:00.000Z", 60, NOW_UTC, DEVICE_OFFSET))))
        node(TestTags.Compose.WHEN_VALUE).assertTextEquals("Mon, Nov 9, 18:00")
        screen.switchTo(EditStates.state(body = EditStates.body(time = EditTime("2024-11-09T17:00:00.000Z", 60, NOW_UTC, DEVICE_OFFSET))))
        node(TestTags.Compose.WHEN_VALUE).assertTextEquals("Nov 9, 2024, 18:00")
    }

    @Test
    fun theTimeEditorHasDateAndTimeFieldsDoneAndUseOriginalTime() {
        show(EditStates.state(timeEditor = TimeEditorUi("2026-11-21", "09:00", invalid = false)))
        node(TestTags.TimeEditor.DATE).assert(editable("2026-11-21"))
        node(TestTags.TimeEditor.TIME).assert(editable("09:00"))
        assertFalse(compose.exists(TestTags.TimeEditor.INVALID))
        node(TestTags.TimeEditor.DATE).performTextReplacement("2026-11-20")
        node(TestTags.TimeEditor.TIME).performTextReplacement("18:30")
        node(TestTags.TimeEditor.DONE).performScrollTo().assertTextContains("Done").performClick()
        compose.waitForIdle()
        node(TestTags.TimeEditor.RESET).performScrollTo().assertTextContains("Use original time").performClick()
        compose.waitForIdle()
        assertEquals(listOf(EditAction.ApplyTime("2026-11-20", "18:30"), EditAction.ResetTime), taps())
    }

    @Test
    fun anInvalidTimeShowsTheFormatHint() {
        show(EditStates.state(timeEditor = TimeEditorUi("2026-02-30", "10:00", invalid = true)))
        node(TestTags.TimeEditor.DATE).assert(editable("2026-02-30"))
        node(TestTags.TimeEditor.INVALID).performScrollTo().assertTextContains("Use the format 2026-11-11 and 14:05.")
    }

    @Test
    fun whereShowsTheCoordinatesTheirSourceTheMapAndRemove() {
        show(EditStates.full)
        node(TestTags.Location.COORDINATES).assertTextEquals("48.85840, 2.29450")
        node(TestTags.Location.SOURCE).assertTextEquals("from photo")
        node(TestTags.Location.OPEN).assert(hasContentDescription("Open in Maps"))
        node(TestTags.Location.REMOVE).assert(hasContentDescription("Remove Location"))
        click(TestTags.Location.OPEN)
        click(TestTags.Location.REMOVE)
        assertEquals(listOf(EditAction.OpenMap, EditAction.RemoveLocation), taps())
    }

    @Test
    fun aDevicePlaceSaysSoAndNoPlaceHidesWhere() {
        val screen = showSwitchable(EditStates.state(body = EditStates.body(location = NEAR_HOME)))
        node(TestTags.Location.SOURCE).assertTextEquals("from device")
        node(TestTags.Location.COORDINATES).assertTextEquals("52.52000, 13.40500")
        screen.switchTo(EditStates.state())
        assertFalse(compose.exists(TestTags.Location.COORDINATES))
        assertFalse(compose.exists(TestTags.Location.REMOVE))
        assertFalse(shows("Where"))
    }

    @Test
    fun tagChipsCanBeRemovedAndChooseTagsOpensThePicker() {
        show(EditStates.full)
        node(listItem(TestTags.Tags.CHIP, 0)).assertTextEquals("beach")
        node(listItem(TestTags.Tags.CHIP, 1)).assertTextEquals("family")
        node(listItem(TestTags.Tags.REMOVE, 1)).assert(hasContentDescription("Remove family"))
        click(listItem(TestTags.Tags.REMOVE, 1))
        node(TestTags.Tags.CHOOSE).performScrollTo().assertTextContains("Choose tags")
        click(TestTags.Tags.CHOOSE)
        assertEquals(listOf(EditAction.RemoveTag("family"), EditAction.ChooseTags), taps())
    }

    @Test
    fun thePickerChecksTheEntrysTagsAndTogglesOthers() {
        show(EditStates.full.copy(sheet = EditSheet.ChooseTags(listOf("beach", "city", "family", "zoo"))))
        assertTrue(compose.exists(TestTags.Tags.PICKER))
        node("${TestTags.Tags.OPTION}.beach").assert(toggle(ToggleableState.On))
        node("${TestTags.Tags.OPTION}.city").assert(toggle(ToggleableState.Off)).assert(hasText("city"))
        node("${TestTags.Tags.OPTION}.family").assert(toggle(ToggleableState.On))
        node("${TestTags.Tags.OPTION}.city").performClick()
        compose.waitForIdle()
        node(TestTags.Tags.EDIT_TAGS).assertTextContains("Edit tags").performClick()
        compose.waitForIdle()
        node(TestTags.Tags.DONE).assertTextContains("Done").performClick()
        compose.waitForIdle()
        assertEquals(listOf(EditAction.ToggleTag("city"), EditAction.EditTags, EditAction.DismissSheet), taps())
    }

    @Test
    fun withFiftyTagsOnlyCheckedOnesCanChange() {
        val fifty = (0 until 50).map { "t$it" }
        show(EditStates.state(body = EditStates.body(tags = fifty), sheet = EditSheet.ChooseTags(fifty + "zoo")))
        node("${TestTags.Tags.OPTION}.zoo").assertIsNotEnabled()
        node("${TestTags.Tags.OPTION}.t0").assertIsEnabled()
    }

    @Test
    fun anEmptyTagListPointsToSettings() {
        show(EditStates.state(sheet = EditSheet.ChooseTags(emptyList())))
        assertTrue(compose.exists(TestTags.Tags.PICKER))
        assertTrue(shows("No tags yet. Create them in Settings › Tags."))
    }

    @Test
    fun aBlankNoteOffersAddANote() {
        show(EditStates.state())
        node(TestTags.Edit.ADD_NOTE).performScrollTo().assertTextContains("Add a note")
        assertFalse(compose.exists(TestTags.Compose.NOTE))
        assertFalse(compose.exists(TestTags.Edit.EDIT_NOTE))
        click(TestTags.Edit.ADD_NOTE)
        assertEquals(listOf<EditAction>(EditAction.EditNote), taps())
    }

    @Test
    fun aNoteIsShownRenderedWithAPencilAndNoTextField() {
        show(EditStates.full)
        assertTrue(compose.exists(TestTags.Markdown.VIEW))
        assertTrue(compose.onAllNodes(hasText("swim at the lake", substring = true), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        assertFalse(shows("**swim** at the lake"))
        assertFalse(compose.exists(TestTags.Compose.NOTE))
        assertFalse(compose.exists(TestTags.Edit.ADD_NOTE))
        node(TestTags.Edit.EDIT_NOTE).assert(hasContentDescription("Edit note"))
        click(TestTags.Edit.EDIT_NOTE)
        assertEquals(listOf<EditAction>(EditAction.EditNote), taps())
    }

    @Test
    fun touchingTheRenderedNoteNeverStartsEditing() {
        show(EditStates.full)
        node(TestTags.Markdown.VIEW).performScrollTo()
        clickWord("lake")
        assertEquals(emptyList<EditAction>(), taps())
    }

    @Test
    fun linksInTheRenderedNoteStayTappableAndAskFirst() {
        show(EditStates.state(body = EditStates.body(note = "see [site](https://example.com)")))
        node(TestTags.Markdown.VIEW).performScrollTo()
        clickWord("site")
        assertTrue(compose.exists(TestTags.Markdown.LINK_SHEET))
        node(TestTags.Markdown.OPEN_LINK).performClick()
        compose.waitForIdle()
        assertEquals(listOf<EditAction>(EditAction.OpenLink("https://example.com")), taps())
    }

    @Test
    fun theEditorShowsTheToolbarTheFieldAndDone() {
        show(EditStates.editing("Lake day "))
        assertFalse(compose.exists(TestTags.Markdown.VIEW))
        assertFalse(compose.exists(TestTags.Edit.EDIT_NOTE))
        node(TestTags.Compose.NOTE).assert(editable("Lake day "))
        val labels = listOf("Bold", "Italic", "Strikethrough", "Heading", "Bullet list", "Checklist", "Quote", "Link", "Insert the time")
        MarkdownAction.entries.forEachIndexed { position, action ->
            node(Ids.toolbar(action)).performScrollTo().assert(hasContentDescription(labels[position])).performClick()
            compose.waitForIdle()
        }
        node(TestTags.Edit.NOTE_DONE).performScrollTo().assert(hasContentDescription("Done editing")).performClick()
        compose.waitForIdle()
        assertEquals(MarkdownAction.entries.map { EditAction.Toolbar(it) } + EditAction.NoteDone, taps())
    }

    @Test
    fun typingReportsTheTextAndTheCursor() {
        show(EditStates.editing("Lake day "))
        node(TestTags.Compose.NOTE).performTextInput("swim")
        compose.waitForIdle()
        assertEquals(EditAction.NoteChanged("Lake day swim", TextSelection(13, 13)), taps().filterIsInstance<EditAction.NoteChanged>().last())
    }

    @Test
    fun theFieldShowsTheSelectionTheToolbarLeft() {
        show(EditStates.editing("a **big** day", TextSelection(4, 7)))
        node(TestTags.Compose.NOTE).assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange(4, 7)))
    }

    private object Ids {
        const val MORE = "edit.more"

        fun toolbar(action: MarkdownAction): String = "toolbar." + action.wire
    }
}
