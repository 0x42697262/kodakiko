package dev.kodakiko.ui.feature.links

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import dev.kodakiko.service.links.OwnerLink
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
class LinksListContentTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val actions = mutableListOf<LinksListAction>()

    private val picnic = ownerLink("picnic00000000000000001", "Picnic", expiresAt = isoAt(NOW_MS + 3 * DAY_MS), viewCount = 2)
    private val party = ownerLink("party000000000000000002", "Party", maxViews = 1, viewCount = 1)

    private fun list(links: List<OwnerLink>? = listOf(picnic, party), revoking: OwnerLink? = null, error: Boolean = false) =
        LinksListState(links = links, revoking = revoking, error = error, nowMs = NOW_MS)

    private val state = mutableStateOf(list())

    private fun render(initial: LinksListState) {
        state.value = initial
        compose.show { LinksListContent(state = state.value, onAction = { actions += it }) }
    }

    private fun click(tag: String) {
        compose.onNodeWithTag(tag).performScrollTo().performClick()
        compose.waitForIdle()
    }

    @Test
    fun eachRowShowsTitleExpiryAndViews() {
        render(list())
        assertTrue(compose.exists(TestTags.Links.LIST_SCREEN))
        assertTrue(compose.showsText("Public Links"))
        compose.onNodeWithTag(listItem(TestTags.Links.ROW, 0)).assert(contains("Picnic")).assert(contains("Expires in 3 days · 2 views"))
        compose.onNodeWithTag(listItem(TestTags.Links.ROW, 1)).assert(contains("Party")).assert(contains("Opened · 1 view"))
        assertFalse(compose.exists(listItem(TestTags.Links.ROW, 2)))
        assertFalse(compose.exists(TestTags.Links.LIST_EMPTY))
        assertFalse(compose.exists(TestTags.Links.ERROR))
    }

    @Test
    fun eachRowCanBeCopiedSharedOrRevoked() {
        render(list())
        compose.onNodeWithTag(listItem(TestTags.Links.COPY_ROW, 1)).assert(labeled("Copy Link"))
        compose.onNodeWithTag(listItem(LinksTestTags.SHARE_ROW, 1)).assert(labeled("Share…"))
        compose.onNodeWithTag(listItem(TestTags.Links.REVOKE_ROW, 1)).assert(labeled("Revoke…"))
        click(listItem(TestTags.Links.COPY_ROW, 1))
        click(listItem(LinksTestTags.SHARE_ROW, 0))
        click(listItem(TestTags.Links.REVOKE_ROW, 0))
        assertEquals(listOf(LinksListAction.Copy(party), LinksListAction.Share(picnic), LinksListAction.Revoke(picnic)), actions)
    }

    @Test
    fun noLinksSaysSo() {
        render(list(links = emptyList()))
        compose.onNodeWithTag(TestTags.Links.LIST_EMPTY, useUnmergedTree = true).assertTextEquals("You haven't created any links.")
    }

    @Test
    fun aFailedLoadShowsTheGenericErrorAndNoEmptyText() {
        render(list(links = null, error = true))
        compose.onNodeWithTag(TestTags.Links.ERROR, useUnmergedTree = true).assert(contains("Something went wrong. Try again."))
        assertFalse(compose.exists(TestTags.Links.LIST_EMPTY))
    }

    @Test
    fun revokingAsksWithTheWarningAboutCopies() {
        render(list(revoking = picnic))
        assertTrue(compose.exists(TestTags.Links.REVOKE_SHEET))
        compose.onNodeWithTag(TestTags.Links.REVOKE_SHEET)
            .assert(contains("Revoke this link? People who open it will see nothing. Copies already saved stay with them."))
        compose.onNodeWithTag(TestTags.Links.REVOKE_CONFIRM).assert(labeled("Revoke"))
        compose.onNodeWithTag(TestTags.Links.REVOKE_CONFIRM).performClick()
        compose.waitForIdle()
        assertEquals(listOf<LinksListAction>(LinksListAction.ConfirmRevoke), actions)
    }

    @Test
    fun backIsAnAction() {
        render(list())
        compose.onNodeWithTag(LinksTestTags.BACK).performClick()
        compose.waitForIdle()
        assertEquals(listOf<LinksListAction>(LinksListAction.Back), actions)
    }
}
