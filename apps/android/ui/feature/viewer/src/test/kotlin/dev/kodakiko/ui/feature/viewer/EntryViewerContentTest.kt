package dev.kodakiko.ui.feature.viewer

import androidx.activity.ComponentActivity
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import dev.kodakiko.contract.MediaKind
import dev.kodakiko.ui.core.TestTags
import dev.kodakiko.ui.core.TestTags.listItem
import kotlinx.coroutines.CompletableDeferred
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
class EntryViewerContentTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val actions = mutableListOf<ViewerAction>()

    private fun show(state: ViewerState, images: ViewerImages = FakeViewerImages(), surfaces: VideoSurfaces = FakeVideoSurfaces()) {
        compose.show(images = images, surfaces = surfaces) { EntryViewerContent(state = state, onAction = { actions += it }) }
    }

    private fun showStateful(initial: ViewerState, images: ViewerImages = FakeViewerImages()): MutableState<ViewerState> {
        val holder = mutableStateOf(initial)
        compose.show(images = images) {
            EntryViewerContent(
                state = holder.value,
                onAction = { action ->
                    actions += action
                    holder.value = ViewerStates.reduce(holder.value, action)
                },
            )
        }
        return holder
    }

    private fun taps(): List<ViewerAction> = actions.filterNot { it is ViewerAction.ZoomChanged || it is ViewerAction.PageChanged }

    private fun node(tag: String): SemanticsNodeInteraction = compose.onNodeWithTag(tag)

    private fun click(tag: String) {
        node(tag).performScrollTo().performClick()
        compose.waitForIdle()
    }

    private fun zoomable(position: Int) = node(listItem(TestTags.Viewer.ZOOMABLE, position))

    private fun indicator(text: String) = node(TestTags.Viewer.PAGE_INDICATOR).assertTextEquals(text)

    private fun label(position: Int, zoomed: Boolean) {
        val plain = "Photo ${position + 1}"
        val zoomedLabel = "$plain, zoomed"
        zoomable(position).assert(hasContentDescription(if (zoomed) zoomedLabel else plain))
        zoomable(position).assert(!hasContentDescription(if (zoomed) plain else zoomedLabel))
    }

    @Test
    fun theBarShowsBackEditAndMoreForTheOwner() {
        show(ViewerStates.state())
        assertTrue(compose.exists(TestTags.Viewer.SCREEN))
        click(TestTags.Viewer.BACK)
        click(TestTags.Viewer.EDIT)
        click(TestTags.Viewer.MORE)
        assertEquals(listOf(ViewerAction.Back, ViewerAction.Edit, ViewerAction.OpenMore), taps())
        assertFalse(compose.exists(TestTags.Viewer.SHARED_BY))
        assertFalse(compose.exists(TestTags.Viewer.IN_TRASH))
        assertFalse(compose.exists(TestTags.Viewer.UNREADABLE))
    }

    @Test
    fun anEntrySharedWithYouNamesItsOwnerAndCannotBeEdited() {
        show(ViewerStates.state(entry = ViewerStates.entry(owner = false, sharedBy = "bob")))
        node(TestTags.Viewer.SHARED_BY).assertTextEquals("Shared by bob")
        assertFalse(compose.exists(TestTags.Viewer.EDIT))
        assertTrue(compose.exists(TestTags.Viewer.MORE))
        assertFalse(compose.exists(TestTags.Viewer.FAVORITE))
        assertTrue(compose.exists(TestTags.Viewer.SHARE_ACTION))
    }

    @Test
    fun anEntryInTheTrashOffersRestoreAndDeleteButNoEditOrMenu() {
        show(ViewerStates.state(entry = ViewerStates.entry(trashed = true)))
        node(TestTags.Viewer.IN_TRASH).assertTextContains("This entry is in the trash.")
        assertFalse(compose.exists(TestTags.Viewer.EDIT))
        assertFalse(compose.exists(TestTags.Viewer.MORE))
        assertFalse(compose.exists(TestTags.Viewer.FAVORITE))
        assertFalse(compose.exists(TestTags.Viewer.SHARE_ACTION))
        assertTrue(compose.exists(TestTags.Viewer.DETAILS_ACTION))
        node(TestTags.Viewer.DELETE_PERMANENTLY).assertTextContains("Delete Permanently…")
        click(TestTags.Viewer.RESTORE)
        click(TestTags.Viewer.DELETE_PERMANENTLY)
        assertEquals(listOf(ViewerAction.Restore, ViewerAction.AskDeletePermanently), taps())
    }

    @Test
    fun anUnreadableEntrySaysSoAndLetsTheOwnerDeleteIt() {
        show(ViewerStates.state(entry = ViewerStates.entry(body = null, unreadable = true)))
        node(TestTags.Viewer.UNREADABLE).assertTextContains("This entry can't be opened. It may be damaged.")
        assertFalse(compose.exists(TestTags.Viewer.EDIT))
        assertFalse(compose.exists(TestTags.Viewer.MORE))
        assertFalse(compose.exists(TestTags.Viewer.PAGE_INDICATOR))
        assertFalse(compose.exists(listItem(TestTags.Viewer.PAGE, 0)))
        click(TestTags.Viewer.DELETE_PERMANENTLY)
        assertEquals(listOf<ViewerAction>(ViewerAction.AskDeletePermanently), taps())
    }

    @Test
    fun anUnreadableSharedEntryOffersNoDelete() {
        show(ViewerStates.state(entry = ViewerStates.entry(body = null, unreadable = true, owner = false)))
        assertTrue(compose.exists(TestTags.Viewer.UNREADABLE))
        assertFalse(compose.exists(TestTags.Viewer.DELETE_PERMANENTLY))
    }

    @Test
    fun beforeTheEntryLoadsOnlyTheScreenIsThere() {
        show(ViewerStates.state(entry = null))
        assertTrue(compose.exists(TestTags.Viewer.SCREEN))
        assertFalse(compose.exists(TestTags.Viewer.PAGE_INDICATOR))
        assertFalse(compose.exists(TestTags.Viewer.EDIT))
    }

    @Test
    fun whileTheMetadataLoadsTheBarIsThereButNoPager() {
        show(ViewerStates.state(entry = ViewerStates.entry(body = null)))
        assertTrue(compose.exists(TestTags.Viewer.BACK))
        assertFalse(compose.exists(TestTags.Viewer.PAGE_INDICATOR))
        assertFalse(compose.exists(TestTags.Viewer.TIME))
    }

    @Test
    fun theIndicatorAndTheCaptionFollowTheCurrentPage() {
        val holder = showStateful(ViewerStates.full)
        indicator("1 / 4")
        node(TestTags.Viewer.CAPTION).assertTextEquals("Heron at dawn")
        holder.value = holder.value.copy(page = 1)
        compose.waitForIdle()
        indicator("2 / 4")
        assertFalse(compose.exists(TestTags.Viewer.CAPTION))
    }

    @Test
    fun everyPhotoPageIsLabeledForScreenReaders() {
        show(ViewerStates.state())
        assertTrue(compose.exists(listItem(TestTags.Viewer.PAGE, 0)))
        label(0, zoomed = false)
    }

    @Test
    fun doubleTapZoomsSwipingPansInsteadOfPagingAndAnotherDoubleTapUnzooms() {
        showStateful(ViewerStates.state())
        indicator("1 / 2")
        zoomable(0).performTouchInput { doubleClick() }
        compose.waitForIdle()
        label(0, zoomed = true)
        assertTrue(ViewerAction.ZoomChanged(true) in actions)
        zoomable(0).performTouchInput { swipeLeft() }
        compose.waitForIdle()
        indicator("1 / 2")
        zoomable(0).performTouchInput { doubleClick() }
        compose.waitForIdle()
        label(0, zoomed = false)
        zoomable(0).performTouchInput { swipeLeft() }
        compose.waitForIdle()
        indicator("2 / 2")
        assertTrue(ViewerAction.PageChanged(1) in actions)
    }

    @Test
    fun aPageChangeResetsTheZoom() {
        val holder = showStateful(ViewerStates.state())
        zoomable(0).performTouchInput { doubleClick() }
        compose.waitForIdle()
        label(0, zoomed = true)
        holder.value = ViewerStates.reduce(holder.value, ViewerAction.PageChanged(1))
        compose.waitForIdle()
        holder.value = ViewerStates.reduce(holder.value, ViewerAction.PageChanged(0))
        compose.waitForIdle()
        label(0, zoomed = false)
    }

    @Test
    fun originalsOpenForTheVisiblePageAndItsNeighborsOnly() {
        val pages = (1..4).map { ViewerPage(mediaId = "p$it", kind = MediaKind.PHOTO, width = 4000, height = 3000, caption = null) }
        val images = FakeViewerImages()
        val holder = mutableStateOf(ViewerStates.state(entry = ViewerStates.entry(body = ViewerStates.body(pages = pages))))
        compose.show(images = images) { EntryViewerContent(state = holder.value, onAction = { actions += it }) }
        assertEquals(setOf("p1", "p2"), images.originalRequests.toSet())
        holder.value = holder.value.copy(page = 2)
        compose.waitForIdle()
        assertTrue(images.originalRequests.containsAll(listOf("p3", "p4")))
    }

    @Test
    fun theThumbnailShowsWhileTheOriginalLoads() {
        val images = FakeViewerImages(gates = mapOf("p1" to CompletableDeferred()))
        show(ViewerStates.state(), images)
        assertTrue(images.thumbnails.contains("p1"))
        assertTrue(images.originalRequests.contains("p1"))
    }

    @Test
    fun videoPagesDoNotOpenOriginalImages() {
        val images = FakeViewerImages()
        show(ViewerStates.state(entry = ViewerStates.entry(body = ViewerStates.body(pages = ViewerStates.mixed.take(2)))), images)
        assertEquals(listOf("p1"), images.originalRequests.distinct())
        assertTrue(images.thumbnails.contains("m2"))
    }

    @Test
    fun aMissingOriginalShowsThePreviewLabel() {
        show(ViewerStates.state(), FakeViewerImages(originals = mapOf("p1" to OriginalImage.Missing)))
        node(TestTags.Viewer.SHOWING_PREVIEW).assertTextEquals("Showing preview")
    }

    @Test
    fun aDownloadingOriginalShowsItsProgress() {
        val gate = CompletableDeferred<OriginalImage>()
        show(ViewerStates.state(), FakeViewerImages(gates = mapOf("p1" to gate), progress = mapOf("p1" to DownloadProgress(received = 409, total = 1000))))
        node(TestTags.Viewer.DOWNLOADING).assertTextEquals("Downloading… 40%")
        gate.complete(OriginalImage.Loaded(solid(colorOf("p1"))))
        compose.waitForIdle()
        assertFalse(compose.exists(TestTags.Viewer.DOWNLOADING))
    }

    @Test
    fun aDownloadOfUnknownSizeShowsNoPercent() {
        show(ViewerStates.state(), FakeViewerImages(gates = mapOf("p1" to CompletableDeferred()), progress = mapOf("p1" to DownloadProgress(received = 10, total = 0))))
        node(TestTags.Viewer.DOWNLOADING).assertTextEquals("Downloading…")
    }

    @Test
    fun aFailedDownloadShowsThePreviewWithRetry() {
        val images = FakeViewerImages(originals = mapOf("p1" to OriginalImage.Failed))
        show(ViewerStates.state(), images)
        node(TestTags.Viewer.DOWNLOAD_FAILED).assertTextEquals("Couldn't download the original. Showing preview.")
        val before = images.originalRequests.count { it == "p1" }
        compose.onNodeWithTag(TestTags.Viewer.RETRY).performClick()
        compose.waitForIdle()
        assertEquals(before + 1, images.originalRequests.count { it == "p1" })
    }

    @Test
    fun theTimeIsTheEntryTimeRelativeToToday() {
        show(ViewerStates.state())
        node(TestTags.Viewer.TIME).assertTextEquals("Today, 09:00")
    }

    @Test
    fun thePlaceShowsCoordinatesSourceAndTheMapAction() {
        show(ViewerStates.full)
        node(TestTags.Location.COORDINATES).assertTextEquals("52.52000, 13.40500")
        node(TestTags.Location.SOURCE).assertTextEquals("from photo")
        click(TestTags.Location.OPEN)
        assertEquals(listOf<ViewerAction>(ViewerAction.OpenMap), taps())
    }

    @Test
    fun withoutAPlaceThereIsNoMapAction() {
        show(ViewerStates.state())
        assertFalse(compose.exists(TestTags.Location.COORDINATES))
        assertFalse(compose.exists(TestTags.Location.OPEN))
    }

    @Test
    fun theActionRowFavoritesOpensDetailsAndShares() {
        show(ViewerStates.state())
        node(TestTags.Viewer.FAVORITE).assertTextContains("Favorite")
        click(TestTags.Viewer.FAVORITE)
        click(TestTags.Viewer.DETAILS_ACTION)
        click(TestTags.Viewer.SHARE_ACTION)
        assertEquals(listOf(ViewerAction.ToggleFavorite, ViewerAction.OpenDetails, ViewerAction.Share), taps())
    }

    @Test
    fun aFavoriteReadsFavorited() {
        show(ViewerStates.state(entry = ViewerStates.entry(body = ViewerStates.body(favorite = true))))
        node(TestTags.Viewer.FAVORITE).assertTextContains("Favorited")
    }

    @Test
    fun tagChipsOpenTheFilteredGallery() {
        show(ViewerStates.full)
        node(listItem(TestTags.Viewer.TAG_CHIP, 0)).assertTextContains("beach")
        node(listItem(TestTags.Viewer.TAG_CHIP, 1)).assertTextContains("family")
        click(listItem(TestTags.Viewer.TAG_CHIP, 1))
        assertEquals(listOf<ViewerAction>(ViewerAction.ShowTag("family")), taps())
    }

    @Test
    fun withoutTagsThereAreNoChips() {
        show(ViewerStates.state())
        assertFalse(compose.exists(listItem(TestTags.Viewer.TAG_CHIP, 0)))
    }

    @Test
    fun theNoteIsRenderedAndALinkAsksBeforeOpening() {
        show(ViewerStates.full)
        assertTrue(compose.exists(TestTags.Markdown.VIEW))
        MarkdownTesting.clickText(compose, "lake")
        compose.onNodeWithTag(TestTags.Markdown.LINK_URL, useUnmergedTree = true).assertTextEquals("https://example.com/lake")
        compose.onNodeWithTag(TestTags.Markdown.OPEN_LINK).performClick()
        compose.waitForIdle()
        assertEquals(listOf<ViewerAction>(ViewerAction.OpenLink("https://example.com/lake")), taps())
    }

    @Test
    fun anEmptyNoteRendersNothing() {
        show(ViewerStates.state())
        assertFalse(compose.exists(TestTags.Markdown.VIEW))
    }

    @Test
    fun voiceNotesShowTheirLengthAndPlay() {
        show(ViewerStates.full)
        assertTrue(compose.exists(TestTags.Voice.LIST))
        node(listItem(TestTags.Voice.TILE, 0)).assert(hasContentDescription("Voice note 1"))
        node(listItem(TestTags.Voice.DURATION, 0)).assertTextEquals("00:20")
        node(listItem(TestTags.Voice.DURATION, 1)).assertTextEquals("01:05")
        node(listItem(TestTags.Voice.PLAY, 0)).assert(hasContentDescription("Play the voice note"))
        click(listItem(TestTags.Voice.PLAY, 1))
        assertEquals(listOf<ViewerAction>(ViewerAction.ToggleVoice("v2")), taps())
        assertFalse(compose.exists(listItem(TestTags.Voice.REMOVE, 0)))
    }

    @Test
    fun aPlayingVoiceNoteOffersPause() {
        show(ViewerStates.full.copy(voicePlaying = "v1"))
        node(listItem(TestTags.Voice.PLAY, 0)).assert(hasContentDescription("Pause the voice note"))
        node(listItem(TestTags.Voice.PLAY, 1)).assert(hasContentDescription("Play the voice note"))
    }

    @Test
    fun withoutVoiceNotesThereIsNoList() {
        show(ViewerStates.state())
        assertFalse(compose.exists(TestTags.Voice.LIST))
    }

    @Test
    fun theMoreSheetForTheOwnerOnAServer() {
        show(ViewerStates.state(sheet = ViewerSheet.MORE))
        assertTrue(compose.exists(TestTags.Viewer.MORE_SHEET))
        node(KEEP_OFFLINE).assertTextContains("Keep Offline")
        assertFalse(compose.exists(REMOVE_OFFLINE))
        node(TestTags.Viewer.ADD_MEDIA).assertTextContains("Add Photos or Videos")
        node(TestTags.Viewer.TRASH).assertTextContains("Move to Trash")
        node(TestTags.Viewer.SHARE).assertTextContains("Share…").assertIsEnabled()
        assertFalse(compose.exists(TestTags.Viewer.SHARING_OFF))
        node(KEEP_OFFLINE).performClick()
        node(TestTags.Viewer.ADD_MEDIA).performClick()
        node(TestTags.Viewer.TRASH).performClick()
        node(TestTags.Viewer.SHARE).performClick()
        assertEquals(listOf(ViewerAction.ToggleKeepOffline, ViewerAction.AddMedia, ViewerAction.Trash, ViewerAction.Share), taps())
    }

    @Test
    fun aKeptEntryOffersToStopKeepingItOffline() {
        show(ViewerStates.state(sheet = ViewerSheet.MORE, pinned = true))
        node(REMOVE_OFFLINE).assertTextContains("Remove from Offline")
        assertFalse(compose.exists(KEEP_OFFLINE))
    }

    @Test
    fun withSharingOffTheShareRowIsDisabledAndExplained() {
        show(ViewerStates.state(sheet = ViewerSheet.MORE, sharingAvailable = false))
        node(TestTags.Viewer.SHARE).assertIsNotEnabled()
        compose.onNodeWithTag(TestTags.Viewer.SHARING_OFF, useUnmergedTree = true).assertTextContains("Sharing is turned off on this server.")
    }

    @Test
    fun aLocalAccountHasNoShareRow() {
        show(ViewerStates.state(sheet = ViewerSheet.MORE, local = true, sharingAvailable = false))
        assertFalse(compose.exists(TestTags.Viewer.SHARE))
        assertFalse(compose.exists(TestTags.Viewer.SHARING_OFF))
        assertTrue(compose.exists(TestTags.Viewer.TRASH))
    }

    @Test
    fun aRecipientCanOnlyKeepTheEntryOffline() {
        show(ViewerStates.state(entry = ViewerStates.entry(owner = false, sharedBy = "bob"), sheet = ViewerSheet.MORE, sharingAvailable = false))
        assertTrue(compose.exists(KEEP_OFFLINE))
        for (tag in listOf(TestTags.Viewer.ADD_MEDIA, TestTags.Viewer.TRASH, TestTags.Viewer.SHARE, TestTags.Viewer.SHARING_OFF)) {
            assertFalse(tag, compose.exists(tag))
        }
    }

    @Test
    fun noSheetWithoutASheetState() {
        show(ViewerStates.state())
        assertFalse(compose.exists(TestTags.Viewer.MORE_SHEET))
        assertFalse(compose.exists(TestTags.Viewer.CONFIRM_SHEET))
    }

    @Test
    fun theDeleteConfirmationReadsAsV031() {
        show(ViewerStates.state(entry = ViewerStates.entry(trashed = true), sheet = ViewerSheet.CONFIRM_DELETE))
        assertTrue(compose.exists(TestTags.Viewer.CONFIRM_SHEET))
        val title = hasText("Delete 1 entry permanently? This can't be undone.")
        compose.onNodeWithTag(TestTags.Viewer.CONFIRM_SHEET, useUnmergedTree = true).assert(title or hasAnyDescendant(title))
        compose.onNodeWithTag(TestTags.Bulk.CONFIRM_DELETE).assertTextContains("Delete").performClick()
        compose.onNodeWithTag(TestTags.Bulk.CANCEL).assertTextContains("Cancel").performClick()
        assertEquals(listOf(ViewerAction.ConfirmDeletePermanently, ViewerAction.DismissSheet), taps())
    }

    @Test
    fun aVideoPageShowsPlayAndNoInlineVideoWhenNothingPlays() {
        show(ViewerStates.full.copy(page = 1))
        assertFalse(compose.exists(listItem(TestTags.Viewer.INLINE_VIDEO, 1)))
        node(TestTags.Viewer.PLAY).assertTextContains("Play")
        node(TestTags.Viewer.PLAY).performClick()
        assertEquals(listOf<ViewerAction>(ViewerAction.OpenPlayer("m2")), taps())
    }

    @Test
    fun anInlineVideoPlaysMutedWithASpeakerButton() {
        val surfaces = FakeVideoSurfaces()
        val video = FakePreparedVideo("m2")
        show(ViewerStates.full.copy(page = 1, video = InlineVideoUi(position = 1, mediaId = "m2", video = video, muted = true)), surfaces = surfaces)
        assertTrue(compose.exists(listItem(TestTags.Viewer.INLINE_VIDEO, 1)))
        assertEquals(InlineCall("m2", muted = true), surfaces.inline.last())
        val mute = compose.onNodeWithTag(muteTag(1))
        mute.assert(hasContentDescription("Turn the sound on"))
        mute.performClick()
        assertEquals(listOf<ViewerAction>(ViewerAction.ToggleMute), taps())
        assertTrue(compose.exists(TestTags.Viewer.PLAY))
    }

    @Test
    fun anUnmutedInlineVideoOffersToTurnTheSoundOff() {
        val surfaces = FakeVideoSurfaces()
        show(ViewerStates.full.copy(page = 1, video = InlineVideoUi(1, "m2", FakePreparedVideo("m2"), muted = false)), surfaces = surfaces)
        compose.onNodeWithTag(muteTag(1)).assert(hasContentDescription("Turn the sound off"))
        assertEquals(InlineCall("m2", muted = false), surfaces.inline.last())
    }

    @Test
    fun tappingTheInlineVideoOpensTheFullScreenPlayer() {
        show(ViewerStates.full.copy(page = 1, video = InlineVideoUi(1, "m2", FakePreparedVideo("m2"), muted = true)))
        compose.onNodeWithTag(listItem(TestTags.Viewer.INLINE_VIDEO, 1)).performTouchInput { click(center) }
        compose.waitForIdle()
        assertEquals(ViewerAction.OpenPlayer("m2"), taps().single())
    }

    private fun muteTag(position: Int): String = listItem(TestTags.Viewer.INLINE_VIDEO, position) + ".mute"

    private companion object {
        const val KEEP_OFFLINE = "viewer.keepOffline"
        const val REMOVE_OFFLINE = "viewer.removeOffline"
    }
}
