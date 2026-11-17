package dev.kodakiko.ui.feature.tags

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
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
class TagsFlowsTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val h = TagsHarness()
    private var pops = 0

    private val navigator = object : RouteNavigator {
        override val routes: List<Route> = listOf(Route.Gallery, Route.Tags)

        override fun push(route: Route) = error("the tag editor opens nothing")

        override fun pop(): Boolean {
            pops += 1
            return true
        }

        override fun replace(route: Route) = error("the tag editor replaces nothing")

        override fun replaceAll(route: Route) = error("the tag editor resets nothing")
    }

    private fun open(): TagsViewModel {
        val vm = TagsViewModel(h.deps)
        compose.show {
            CompositionLocalProvider(LocalRouteNavigator provides navigator) { TagsScreen(viewModel = vm) }
        }
        return vm
    }

    private fun node(tag: String): SemanticsNodeInteraction = compose.onNodeWithTag(tag)

    private fun click(tag: String) {
        node(tag).performClick()
        compose.waitForIdle()
    }

    private fun contains(text: String): SemanticsMatcher = hasText(text) or hasAnyDescendant(hasText(text))

    private fun row(position: Int): SemanticsNodeInteraction = node(listItem(TestTags.Tags.ROW, position))

    @Test
    fun u28RenamingATagRenamesItOnEveryEntryAndKeepsItFirst() {
        h.beachAndCity()
        open()
        row(0).assert(contains("beach")).assert(contains("2 entries"))
        click(listItem(TestTags.Tags.RENAME, 0))
        node(TestTags.Tags.NAME_FIELD).performTextReplacement("seaside")
        compose.waitForIdle()
        click(TestTags.Tags.SAVE)
        assertFalse(compose.exists(TestTags.Tags.NAME_FIELD))
        row(0).assert(contains("seaside")).assert(contains("2 entries"))
        row(1).assert(contains("city")).assert(contains("1 entry"))
        assertEquals(listOf("city", "seaside"), h.index.tagsOf("e2"))
        assertEquals(listOf("seaside"), h.index.tagsOf("e1"))
        assertEquals(listOf("seaside", "city"), h.settings.tagOrder)
    }

    @Test
    fun anAddedTagIsListedLastWithNoEntries() {
        h.beachAndCity()
        open()
        click(TestTags.Tags.ADD)
        node(TestTags.Tags.NAME_FIELD).performTextReplacement("Family")
        compose.waitForIdle()
        node(TestTags.Tags.NAME_FIELD).performImeAction()
        compose.waitForIdle()
        assertFalse(compose.exists(TestTags.Tags.NAME_FIELD))
        row(2).assert(contains("family")).assert(contains("0 entries"))
    }

    @Test
    fun anEmptyNameKeepsTheDialogOpenWithTheRule() {
        open()
        click(TestTags.Tags.ADD)
        click(TestTags.Tags.SAVE)
        assertTrue(compose.exists(TestTags.Tags.NAME_FIELD))
        assertTrue(compose.onAllNodes(hasText("Tags use letters, numbers, - and _ (up to 32 characters)."), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        assertEquals(emptyList<List<String>>(), h.settings.writes)
    }

    @Test
    fun deletingATagAsksFirstAndThenRemovesTheRow() {
        h.beachAndCity()
        open()
        click(listItem(TestTags.Tags.DELETE, 1))
        assertTrue(compose.onAllNodes(hasText("Remove #city from 1 entry? The entries stay."), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        click(TestTags.Tags.CONFIRM_DELETE)
        assertFalse(compose.exists(TestTags.Tags.CONFIRM_DELETE))
        assertFalse(compose.exists(listItem(TestTags.Tags.ROW, 1)))
        assertEquals(listOf("beach"), h.index.tagsOf("e2"))
    }

    @Test
    fun draggingARowByItsHandleReordersTheTags() {
        h.beachAndCity()
        h.index.put("e3", listOf("zoo"))
        open()
        val first = row(0).fetchSemanticsNode().boundsInRoot
        val second = row(1).fetchSemanticsNode().boundsInRoot
        val step = second.top - first.top
        compose.onNodeWithTag(listItem(TagsTestTags.DRAG, 0), useUnmergedTree = true).performTouchInput {
            down(center)
            repeat(DRAG_STEPS) { moveBy(Offset(0f, step * DRAG_ROWS / DRAG_STEPS)) }
            up()
        }
        compose.waitForIdle()
        assertEquals(listOf("city", "beach", "zoo"), h.settings.tagOrder)
        row(0).assert(contains("city"))
        row(1).assert(contains("beach"))
        row(2).assert(contains("zoo"))
    }

    @Test
    fun theBackArrowLeavesTheEditor() {
        open()
        click(TagsTestTags.BACK)
        assertEquals(1, pops)
    }

    private companion object {
        const val DRAG_STEPS = 10
        const val DRAG_ROWS = 1.2f
    }
}
