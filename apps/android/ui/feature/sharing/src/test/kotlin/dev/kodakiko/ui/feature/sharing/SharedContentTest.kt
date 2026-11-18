package dev.kodakiko.ui.feature.sharing

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
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
class SharedContentTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val actions = mutableListOf<SharedAction>()
    private val state = mutableStateOf(enabled())

    private fun enabled(
        mine: List<SharedJournal>? = emptyList(),
        withMe: List<SharedJournal>? = emptyList(),
        dialog: SharedDialog? = null,
    ) = SharedState(availability = SharedAvailability.ENABLED, mine = mine, withMe = withMe, dialog = dialog, refreshing = false)

    private fun render(initial: SharedState) {
        state.value = initial
        compose.show { SharedContent(state = state.value, onAction = { actions += it }) }
    }

    private fun click(tag: String) {
        compose.onNodeWithTag(tag).performScrollTo().performClick()
        compose.waitForIdle()
    }

    @Test
    fun sharingOffShowsOnlyTheNotice() {
        render(SharedState(availability = SharedAvailability.FLAG_OFF, mine = null, withMe = null, dialog = null, refreshing = false))
        assertTrue(compose.exists(TestTags.Shared.SCREEN))
        compose.onNodeWithTag(TestTags.Shared.FLAG_OFF, useUnmergedTree = true).assertTextEquals("Sharing is turned off on this server.")
        assertFalse(compose.exists(TestTags.Shared.NEW))
        assertFalse(compose.exists(TestTags.Shared.EMPTY_MINE))
        assertFalse(compose.exists(TestTags.Shared.EMPTY_WITH_ME))
    }

    @Test
    fun aLocalAccountIsAskedToConnectAServer() {
        render(SharedState(availability = SharedAvailability.LOCAL_ONLY, mine = null, withMe = null, dialog = null, refreshing = false))
        compose.onNodeWithTag(TestTags.Shared.FLAG_OFF, useUnmergedTree = true).assertTextEquals("Connect a server to share journals.")
        assertFalse(compose.exists(TestTags.Shared.NEW))
    }

    @Test
    fun emptySectionsSayNothingIsSharedYetAndOfferANewJournal() {
        render(enabled())
        compose.onNodeWithTag(TestTags.Shared.SCREEN).assert(contains("My Shared Journals")).assert(contains("Shared With Me"))
        compose.onNodeWithTag(TestTags.Shared.EMPTY_MINE, useUnmergedTree = true).assertTextEquals("You haven't shared anything yet.")
        compose.onNodeWithTag(TestTags.Shared.EMPTY_WITH_ME, useUnmergedTree = true).assertTextEquals("Nothing has been shared with you yet.")
        compose.onNodeWithTag(TestTags.Shared.NEW).assert(labeled("New Shared Journal…")).assertIsEnabled()
        assertFalse(compose.exists(TestTags.Shared.FLAG_OFF))
    }

    @Test
    fun whileLoadingNeitherEmptyTextShows() {
        render(enabled(mine = null, withMe = null))
        assertFalse(compose.exists(TestTags.Shared.EMPTY_MINE))
        assertFalse(compose.exists(TestTags.Shared.EMPTY_WITH_ME))
        assertTrue(compose.exists(TestTags.Shared.NEW))
    }

    @Test
    fun ownRowsShowNameAndEntryCountAndRecipientRowsShowTheOwner() {
        render(
            enabled(
                mine = listOf(journal(PICNICS, "Picnics", count = 2), journal(TRIPS, null, count = 1)),
                withMe = listOf(journal(FROM_BOB, "Hikes", CollectionRole.VIEWER, "bob", count = 5)),
            ),
        )
        compose.onNodeWithTag(listItem(TestTags.Shared.MINE_ROW, 0)).assert(contains("Picnics")).assert(contains("2 entries"))
        compose.onNodeWithTag(listItem(TestTags.Shared.MINE_ROW, 1)).assert(contains("Untitled journal")).assert(contains("1 entry"))
        compose.onNodeWithTag(listItem(TestTags.Shared.WITH_ME_ROW, 0)).assert(contains("Hikes")).assert(contains("from bob"))
        assertFalse(compose.exists(TestTags.Shared.EMPTY_MINE))
        assertFalse(compose.exists(TestTags.Shared.EMPTY_WITH_ME))
        assertFalse(compose.exists(listItem(TestTags.Shared.MINE_ROW, 2)))
        assertFalse(compose.exists(listItem(TestTags.Shared.WITH_ME_ROW, 1)))
    }

    @Test
    fun tappingRowsOpensThemAndNewAsksForAName() {
        render(
            enabled(
                mine = listOf(journal(PICNICS, "Picnics", count = 2)),
                withMe = listOf(journal(FROM_BOB, "Hikes", CollectionRole.VIEWER, "bob")),
            ),
        )
        click(listItem(TestTags.Shared.MINE_ROW, 0))
        click(listItem(TestTags.Shared.WITH_ME_ROW, 0))
        click(TestTags.Shared.NEW)
        assertEquals(listOf(SharedAction.Open(PICNICS), SharedAction.Open(FROM_BOB), SharedAction.New), actions)
    }

    @Test
    fun theNewJournalDialogHasANameFieldCreateAndCancel() {
        render(enabled(dialog = SharedDialog.New(name = "", busy = false, error = null)))
        assertTrue(compose.exists(TestTags.Shared.NEW_SHEET))
        compose.onNodeWithTag(TestTags.Shared.CREATE).assert(labeled("Create")).assertIsNotEnabled()
        compose.onNodeWithTag(TestTags.Shared.CANCEL).assert(labeled("Cancel"))
        compose.onNodeWithTag(TestTags.Shared.NAME_FIELD).performTextReplacement("Picnics")
        compose.waitForIdle()
        assertEquals(listOf<SharedAction>(SharedAction.NameChanged("Picnics")), actions)
        state.value = enabled(dialog = SharedDialog.New(name = "Picnics", busy = false, error = null))
        compose.waitForIdle()
        compose.onNodeWithTag(TestTags.Shared.CREATE).assertIsEnabled().performClick()
        compose.onNodeWithTag(TestTags.Shared.CANCEL).performClick()
        compose.waitForIdle()
        assertEquals(listOf(SharedAction.NameChanged("Picnics"), SharedAction.Create, SharedAction.Dismiss), actions)
    }

    @Test
    fun aBlankNameCannotBeCreated() {
        render(enabled(dialog = SharedDialog.New(name = "   ", busy = false, error = null)))
        compose.onNodeWithTag(TestTags.Shared.CREATE).assertIsNotEnabled()
    }

    @Test
    fun aFailedCreateShowsItsMessageInTheDialog() {
        render(enabled(dialog = SharedDialog.New(name = "Picnics", busy = false, error = SharingMessage.Offline)))
        compose.onNodeWithTag(TestTags.Shared.ERROR, useUnmergedTree = true).assert(contains("You're offline. Sharing needs a connection."))
    }

    @Test
    fun aBusyCreateCannotBeTappedTwice() {
        render(enabled(dialog = SharedDialog.New(name = "Picnics", busy = true, error = null)))
        compose.onNodeWithTag(TestTags.Shared.CREATE).assertIsNotEnabled()
        assertFalse(compose.exists(TestTags.Shared.ERROR))
    }
}
