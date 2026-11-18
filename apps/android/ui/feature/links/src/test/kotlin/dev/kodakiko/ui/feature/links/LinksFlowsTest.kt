package dev.kodakiko.ui.feature.links

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import dev.kodakiko.contract.LinkExpiry
import dev.kodakiko.service.links.CreateLinkInput
import dev.kodakiko.ui.core.TestTags
import dev.kodakiko.ui.core.TestTags.listItem
import dev.kodakiko.ui.core.navigation.LocalRouteNavigator
import dev.kodakiko.ui.core.navigation.Route
import dev.kodakiko.ui.core.navigation.RouteNavigator
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
class LinksFlowsTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val h = LinksHarness()
    private var pops = 0
    private var popResult = true
    private val replacedAll = mutableListOf<Route>()

    private val navigator = object : RouteNavigator {
        override val routes: List<Route> = listOf(Route.Gallery, Route.LinkNew(listOf(ENTRY_A)))

        override fun push(route: Route) = error("the link screens open nothing")

        override fun pop(): Boolean {
            pops += 1
            return popResult
        }

        override fun replace(route: Route) = error("the link screens replace nothing")

        override fun replaceAll(route: Route) {
            replacedAll += route
        }
    }

    private fun node(tag: String): SemanticsNodeInteraction = compose.onNodeWithTag(tag)

    private fun await(tag: String) {
        compose.waitUntil(WAIT_MS) { compose.exists(tag) }
    }

    private fun click(tag: String) {
        await(tag)
        node(tag).performScrollTo().performClick()
        compose.waitForIdle()
    }

    private fun tap(tag: String) {
        await(tag)
        node(tag).performClick()
        compose.waitForIdle()
    }

    private fun create(): LinkCreateViewModel {
        val vm = LinkCreateViewModel(h.deps, listOf(ENTRY_A))
        compose.show { CompositionLocalProvider(LocalRouteNavigator provides navigator) { LinkCreateScreen(viewModel = vm) } }
        return vm
    }

    private fun list(): LinksListViewModel {
        val vm = LinksListViewModel(h.deps)
        compose.show { CompositionLocalProvider(LocalRouteNavigator provides navigator) { LinksListScreen(viewModel = vm) } }
        return vm
    }

    @Test
    fun u18CreatingALinkWithoutTheMovAndWithoutNotesShowsTheUrl() {
        h.u18Entry()
        create()
        await(listItem(TestTags.Links.MEDIA_TOGGLE, 2))
        click(listItem(TestTags.Links.MEDIA_TOGGLE, 1))
        click(TestTags.Links.INCLUDE_NOTES)
        click(TestTags.Links.CREATE)
        await(TestTags.Links.URL)
        node(TestTags.Links.URL).assert(contains("/s/"))
        assertEquals(
            listOf(CreateLinkInput(listOf(ENTRY_A), setOf("photogps", "video20s"), DEFAULT_TITLE, LinkExpiry.SEVEN_DAYS, includeNotes = false)),
            h.links.created,
        )
        tap(LinksTestTags.COPY)
        await(LinksTestTags.COPIED)
        assertEquals(listOf(LINK_URL), h.sharing.copied)
        tap(TestTags.Links.SHARE)
        assertEquals(listOf(LINK_URL), h.sharing.shared)
        tap(TestTags.Links.DONE)
        assertEquals(1, pops)
    }

    @Test
    fun cancelLeavesWithoutCreating() {
        h.u18Entry()
        create()
        click(TestTags.Links.CANCEL)
        assertEquals(1, pops)
        assertTrue(h.links.created.isEmpty())
    }

    @Test
    fun b085RevokingALinkAfterConfirmingRemovesItFromTheList() {
        h.links.links += listOf(
            ownerLink("picnic00000000000000001", "Picnic", expiresAt = isoAt(NOW_MS + 3 * DAY_MS), viewCount = 2),
            ownerLink("party000000000000000002", "Party", maxViews = 1),
        )
        list()
        await(listItem(TestTags.Links.ROW, 1))
        click(listItem(TestTags.Links.REVOKE_ROW, 0))
        tap(TestTags.Links.REVOKE_CONFIRM)
        compose.waitUntil(WAIT_MS) { !compose.exists(listItem(TestTags.Links.ROW, 1)) }
        node(listItem(TestTags.Links.ROW, 0)).assert(contains("Party"))
        assertEquals(listOf("picnic00000000000000001"), h.links.revoked)
        assertFalse(compose.exists(TestTags.Links.REVOKE_SHEET))
    }

    @Test
    fun copyingFromTheListUsesTheClipboard() {
        h.links.links += ownerLink("picnic00000000000000001", "Picnic")
        list()
        click(listItem(TestTags.Links.COPY_ROW, 0))
        click(listItem(LinksTestTags.SHARE_ROW, 0))
        assertEquals(listOf("https://kodakiko.example/s/picnic00000000000000001#secret"), h.sharing.copied)
        assertEquals(listOf("https://kodakiko.example/s/picnic00000000000000001#secret"), h.sharing.shared)
    }

    @Test
    fun backFromTheListPopsAndFallsBackToSettings() {
        list()
        tap(LinksTestTags.BACK)
        assertEquals(1, pops)
        assertEquals(emptyList<Route>(), replacedAll)
        popResult = false
        tap(LinksTestTags.BACK)
        assertEquals(listOf<Route>(Route.Settings), replacedAll)
    }

    private companion object {
        const val WAIT_MS = 5_000L
    }
}
