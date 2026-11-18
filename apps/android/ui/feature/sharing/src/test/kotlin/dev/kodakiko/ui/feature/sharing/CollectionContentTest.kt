package dev.kodakiko.ui.feature.sharing

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import dev.kodakiko.data.db.CollectionRole
import dev.kodakiko.ui.core.TestTags
import dev.kodakiko.ui.core.TestTags.listItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w320dp-h800dp-xhdpi")
class CollectionContentTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val actions = mutableListOf<CollectionAction>()
    private val state = mutableStateOf(owner())

    private val tiles = listOf(CollectionTile("e2", "e2-cover"), CollectionTile("e1", null))

    private fun owner(sheet: CollectionSheet? = null, selecting: Boolean = false, selected: List<String> = emptyList()) = CollectionState(
        loaded = true,
        journal = journal(PICNICS, "Picnics", count = 2),
        tiles = listOf(CollectionTile("e2", "e2-cover"), CollectionTile("e1", null)),
        selecting = selecting,
        selected = selected,
        sheet = sheet,
    )

    private fun recipient(sheet: CollectionSheet? = null) = CollectionState(
        loaded = true,
        journal = journal(FROM_BOB, "Hikes", CollectionRole.VIEWER, "bob", count = 2),
        tiles = tiles,
        selecting = false,
        selected = emptyList(),
        sheet = sheet,
    )

    private fun render(initial: CollectionState) {
        state.value = initial
        compose.show { CollectionContent(state = state.value, onAction = { actions += it }) }
    }

    private fun tap(tag: String) {
        compose.onNodeWithTag(tag).performClick()
        compose.waitForIdle()
    }

    @Test
    fun theOwnerSeesTheTitleTheGridAndMoreWithoutASubtitle() {
        render(owner())
        assertTrue(compose.exists(TestTags.Collection.SCREEN))
        compose.onNodeWithTag(TestTags.Collection.TITLE, useUnmergedTree = true).assertTextEquals("Picnics")
        assertFalse(compose.exists(TestTags.Collection.SUBTITLE))
        compose.onNodeWithTag(TestTags.Collection.MORE).assert(labeled("More"))
        assertTrue(compose.exists(listItem(SharingTestTags.GALLERY_TILE, 0)))
        assertTrue(compose.exists(listItem(SharingTestTags.GALLERY_TILE, 1)))
        assertFalse(compose.exists(listItem(SharingTestTags.GALLERY_TILE, 2)))
        assertFalse(compose.exists(SharingTestTags.GALLERY_ACTIONS))
        assertTrue(compose.exists(TestTags.Shared.BACK))
    }

    @Test
    fun aRecipientSeesWhoSharedIt() {
        render(recipient())
        compose.onNodeWithTag(TestTags.Collection.TITLE, useUnmergedTree = true).assertTextEquals("Hikes")
        compose.onNodeWithTag(TestTags.Collection.SUBTITLE, useUnmergedTree = true).assertTextEquals("Shared by bob")
    }

    @Test
    fun anUnnamedJournalReadsUntitled() {
        render(owner().copy(journal = journal(TRIPS, null)))
        compose.onNodeWithTag(TestTags.Collection.TITLE, useUnmergedTree = true).assertTextEquals("Untitled journal")
    }

    @Test
    fun anEmptyJournalShowsTheGalleryEmptyText() {
        render(owner().copy(tiles = emptyList()))
        compose.onNodeWithTag(SharingTestTags.GALLERY_EMPTY, useUnmergedTree = true).assert(contains("No entries yet."))
    }

    @Test
    fun aMissingJournalShowsOnlyTheWayBack() {
        render(CollectionState(loaded = true, journal = null, tiles = emptyList(), selecting = false, selected = emptyList(), sheet = null))
        assertTrue(compose.exists(TestTags.Collection.SCREEN))
        assertTrue(compose.exists(TestTags.Shared.BACK))
        assertFalse(compose.exists(TestTags.Collection.MORE))
        assertFalse(compose.exists(TestTags.Collection.TITLE))
        tap(TestTags.Shared.BACK)
        assertEquals(listOf<CollectionAction>(CollectionAction.Back), actions)
    }

    @Test
    fun tilesOpenOnTapAndSelectOnLongPressAndBackAndMoreAreActions() {
        render(owner())
        tap(listItem(SharingTestTags.GALLERY_TILE, 0))
        compose.onNodeWithTag(listItem(SharingTestTags.GALLERY_TILE, 1)).performTouchInput { longClick() }
        compose.waitForIdle()
        tap(TestTags.Collection.MORE)
        tap(TestTags.Shared.BACK)
        assertEquals(
            listOf(CollectionAction.TileClick("e2"), CollectionAction.TileLongClick("e1"), CollectionAction.More, CollectionAction.Back),
            actions,
        )
    }

    @Test
    fun theGridLoadsCoverThumbnailsThroughThePort() {
        val thumbnails = FakeThumbnails()
        state.value = owner()
        compose.show {
            CompositionLocalProvider(LocalSharingThumbnails provides thumbnails) {
                CollectionContent(state = state.value, onAction = { actions += it })
            }
        }
        compose.waitUntil(WAIT_MS) { thumbnails.requests.isNotEmpty() }
        assertEquals(listOf("e2" to "e2-cover"), thumbnails.requests.distinct())
    }

    @Test
    fun whileSelectingActionsReplacesMoreAndIsDisabledAtZero() {
        render(owner(selecting = true, selected = emptyList()))
        assertFalse(compose.exists(TestTags.Collection.MORE))
        compose.onNodeWithTag(SharingTestTags.GALLERY_ACTIONS).assert(labeled("Actions")).assertIsNotEnabled()
        state.value = owner(selecting = true, selected = listOf("e1"))
        compose.waitForIdle()
        compose.onNodeWithTag(SharingTestTags.GALLERY_ACTIONS).assertIsEnabled().performClick()
        compose.waitForIdle()
        assertEquals(listOf<CollectionAction>(CollectionAction.OpenActions), actions)
    }

    @Test
    fun theOwnersMenuOffersShareWithRenameAndDelete() {
        render(owner(sheet = CollectionSheet.Menu))
        assertTrue(compose.exists(TestTags.Collection.SHEET))
        compose.onNodeWithTag(TestTags.Collection.SHARE_WITH).assert(labeled("Share With…"))
        compose.onNodeWithTag(TestTags.Collection.RENAME).assert(labeled("Rename…"))
        compose.onNodeWithTag(TestTags.Collection.DELETE).assert(labeled("Delete Journal…"))
        assertFalse(compose.exists(TestTags.Collection.LEAVE))
        tap(TestTags.Collection.SHARE_WITH)
        tap(TestTags.Collection.RENAME)
        tap(TestTags.Collection.DELETE)
        assertEquals(listOf(CollectionAction.ShareWith, CollectionAction.Rename, CollectionAction.Delete), actions)
    }

    @Test
    fun aRecipientsMenuOffersOnlyLeave() {
        render(recipient(sheet = CollectionSheet.Menu))
        compose.onNodeWithTag(TestTags.Collection.LEAVE).assert(labeled("Leave…"))
        assertFalse(compose.exists(TestTags.Collection.SHARE_WITH))
        assertFalse(compose.exists(TestTags.Collection.RENAME))
        assertFalse(compose.exists(TestTags.Collection.DELETE))
        tap(TestTags.Collection.LEAVE)
        assertEquals(listOf<CollectionAction>(CollectionAction.Leave), actions)
    }

    @Test
    fun deleteConfirmationExplainsTheEntriesStay() {
        render(owner(sheet = CollectionSheet.ConfirmDelete(busy = false, error = null)))
        compose.onNodeWithTag(TestTags.Collection.SHEET)
            .assert(contains("Delete Picnics? The entries stay in your journal. People you shared it with lose access."))
        compose.onNodeWithTag(TestTags.Collection.DELETE_CONFIRM).assert(labeled("Delete"))
        tap(TestTags.Collection.DELETE_CONFIRM)
        assertEquals(listOf<CollectionAction>(CollectionAction.ConfirmDelete), actions)
    }

    @Test
    fun leaveConfirmationNamesTheOwnerAndShowsAFailure() {
        render(recipient(sheet = CollectionSheet.ConfirmLeave(busy = false, error = SharingMessage.Offline)))
        compose.onNodeWithTag(TestTags.Collection.SHEET).assert(contains("Leave Hikes? You'll lose access unless bob shares it again."))
        compose.onNodeWithTag(TestTags.Shared.ERROR, useUnmergedTree = true).assert(contains("You're offline. Sharing needs a connection."))
        compose.onNodeWithTag(TestTags.Collection.LEAVE_CONFIRM).assert(labeled("Leave"))
        tap(TestTags.Collection.LEAVE_CONFIRM)
        assertEquals(listOf<CollectionAction>(CollectionAction.ConfirmLeave), actions)
    }

    @Test
    fun aBusyConfirmationCannotBeTappedAgain() {
        render(recipient(sheet = CollectionSheet.ConfirmLeave(busy = true, error = null)))
        compose.onNodeWithTag(TestTags.Collection.LEAVE_CONFIRM).assertIsNotEnabled()
    }

    @Test
    fun renameHasTheNameFieldAndSaveDisabledWhenBlank() {
        render(owner(sheet = CollectionSheet.Rename(name = "Picnics", busy = false, error = null)))
        compose.onNodeWithTag(TestTags.Shared.SAVE).assert(labeled("Save")).assertIsEnabled()
        compose.onNodeWithTag(TestTags.Shared.NAME_FIELD).performTextReplacement("Lunches")
        compose.waitForIdle()
        tap(TestTags.Shared.SAVE)
        assertEquals(listOf(CollectionAction.NameChanged("Lunches"), CollectionAction.Save), actions)
        state.value = owner(sheet = CollectionSheet.Rename(name = " ", busy = false, error = null))
        compose.waitForIdle()
        compose.onNodeWithTag(TestTags.Shared.SAVE).assertIsNotEnabled()
    }

    @Test
    fun theActionsSheetOffersRemoveFromThisJournal() {
        render(owner(sheet = CollectionSheet.Actions(busy = false, error = null), selecting = true, selected = listOf("e1")))
        compose.onNodeWithTag(SharingTestTags.REMOVE_FROM_JOURNAL).assert(labeled("Remove from This Journal"))
        tap(SharingTestTags.REMOVE_FROM_JOURNAL)
        assertEquals(listOf<CollectionAction>(CollectionAction.RemoveFromJournal), actions)
    }

    private companion object {
        const val WAIT_MS = 5_000L
    }
}
