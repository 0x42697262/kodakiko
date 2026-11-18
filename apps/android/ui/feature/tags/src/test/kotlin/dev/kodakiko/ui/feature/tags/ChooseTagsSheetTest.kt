package dev.kodakiko.ui.feature.tags

import androidx.activity.ComponentActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import dev.kodakiko.contract.TAGS_PER_ENTRY_MAX
import dev.kodakiko.index.orderTags
import dev.kodakiko.ui.core.TestTags
import dev.kodakiko.ui.core.tags.ChooseTagsSheet
import dev.kodakiko.ui.core.tags.toggleTagChoice
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
class ChooseTagsSheetTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val toggled = mutableListOf<String>()
    private var editTags = 0
    private var dismissed = 0

    private fun option(tag: String): String = TestTags.Tags.OPTION + "." + tag

    private fun sheet(choices: List<String>, selected: List<String>, single: Boolean = false) {
        compose.show {
            ChooseTagsSheet(
                choices = choices,
                selected = selected,
                onToggle = { toggled += it },
                onEditTags = { editTags += 1 },
                onDismiss = { dismissed += 1 },
                single = single,
            )
        }
    }

    private fun click(tag: String) {
        compose.onNodeWithTag(tag).performClick()
        compose.waitForIdle()
    }

    private fun top(tag: String): Float = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.top

    @Test
    fun theSheetListsTheChoicesInOrderWithTheChosenOnesChecked() {
        sheet(listOf("zoo", "beach", "city"), listOf("beach"))
        assertTrue(compose.exists(TestTags.Tags.PICKER))
        assertTrue(compose.onAllNodes(hasText("Tags"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        compose.onNodeWithTag(option("beach")).assertIsOn()
        compose.onNodeWithTag(option("zoo")).assertIsOff()
        compose.onNodeWithTag(option("city")).assertIsOff()
        compose.onNodeWithTag(option("zoo")).assert(hasText("zoo"))
        assertTrue(top(option("zoo")) < top(option("beach")))
        assertTrue(top(option("beach")) < top(option("city")))
        compose.onNodeWithTag(TestTags.Tags.EDIT_TAGS).assert(hasText("Edit tags"))
        compose.onNodeWithTag(TestTags.Tags.DONE).assert(hasText("Done"))
        assertFalse(compose.onAllNodes(hasText("No tags yet. Create them in Settings › Tags."), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun tappingAnOptionTogglesIt() {
        sheet(listOf("zoo", "beach"), listOf("beach"))
        click(option("zoo"))
        click(option("beach"))
        assertEquals(listOf("zoo", "beach"), toggled)
    }

    @Test
    fun noMoreThan50CanBeCheckedOnAnEntry() {
        val chosen = (1..TAGS_PER_ENTRY_MAX).map { "t$it" }
        sheet(listOf("extra1", "extra2") + chosen, chosen)
        compose.onNodeWithTag(option("extra1")).assertIsNotEnabled()
        compose.onNodeWithTag(option("extra2")).assertIsNotEnabled()
        compose.onNodeWithTag(option("t1")).assertIsEnabled().assertIsOn()
        click(option("extra1"))
        click(option("t1"))
        assertEquals(listOf("t1"), toggled)
    }

    @Test
    fun belowTheLimitEveryOptionCanBeChecked() {
        val chosen = (1 until TAGS_PER_ENTRY_MAX).map { "t$it" }
        sheet(listOf("extra1") + chosen, chosen)
        compose.onNodeWithTag(option("extra1")).assertIsEnabled().assertIsOff()
        click(option("extra1"))
        assertEquals(listOf("extra1"), toggled)
    }

    @Test
    fun aSinglePickNeverLocksOptions() {
        val chosen = (1..TAGS_PER_ENTRY_MAX).map { "t$it" }
        sheet(listOf("extra1") + chosen, chosen, single = true)
        compose.onNodeWithTag(option("extra1")).assertIsEnabled()
        click(option("extra1"))
        assertEquals(listOf("extra1"), toggled)
    }

    @Test
    fun anEmptyListPointsToTheTagEditor() {
        sheet(emptyList(), emptyList())
        assertTrue(compose.onAllNodes(hasText("No tags yet. Create them in Settings › Tags."), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        assertTrue(compose.exists(TestTags.Tags.EDIT_TAGS))
        assertTrue(compose.exists(TestTags.Tags.DONE))
    }

    @Test
    fun editTagsAndDoneReportBack() {
        sheet(listOf("zoo"), emptyList())
        click(TestTags.Tags.EDIT_TAGS)
        assertEquals(1, editTags)
        click(TestTags.Tags.DONE)
        assertEquals(1, dismissed)
        assertEquals(emptyList<String>(), toggled)
    }

    @Test
    fun togglingAddsAtTheEndRemovesInPlaceAndASinglePickReplacesOrClears() {
        assertEquals(listOf("b", "a"), toggleTagChoice(listOf("b"), "a", single = false))
        assertEquals(listOf("c"), toggleTagChoice(listOf("b", "c"), "b", single = false))
        assertEquals(listOf("a"), toggleTagChoice(listOf("b"), "a", single = true))
        assertEquals(emptyList<String>(), toggleTagChoice(listOf("a"), "a", single = true))
        assertEquals(listOf("a"), toggleTagChoice(emptyList(), "a", single = true))
    }

    @Test
    fun editTagsRoundTripATagAddedInTheEditorCanBeChosenRightAway() {
        val h = TagsHarness()
        h.beachAndCity()
        val vm = TagsViewModel(h.deps)
        val editing = mutableStateOf(false)
        val chosen = mutableStateOf(listOf("city"))
        compose.show {
            val settings by h.settings.settings.collectAsState()
            if (editing.value) {
                val state by vm.state.collectAsState()
                TagsContent(state = state, onAction = vm::onAction, onBack = { editing.value = false })
            } else {
                ChooseTagsSheet(
                    choices = orderTags(h.index.gallery.allTags(), settings.tagOrder).map { it.tag },
                    selected = chosen.value,
                    onToggle = { chosen.value = toggleTagChoice(chosen.value, it, single = false) },
                    onEditTags = { editing.value = true },
                    onDismiss = {},
                )
            }
        }
        assertFalse(compose.exists(option("family")))
        click(TestTags.Tags.EDIT_TAGS)
        assertTrue(compose.exists(TestTags.Tags.SCREEN))
        click(TestTags.Tags.ADD)
        compose.onNodeWithTag(TestTags.Tags.NAME_FIELD).performTextReplacement("Family")
        compose.waitForIdle()
        click(TestTags.Tags.SAVE)
        click(TagsTestTags.BACK)
        assertFalse(compose.exists(TestTags.Tags.SCREEN))
        compose.onNodeWithTag(option("family")).assertIsOff()
        click(option("family"))
        compose.onNodeWithTag(option("family")).assertIsOn()
        assertEquals(listOf("city", "family"), chosen.value)
        assertEquals(listOf("beach", "city", "family"), h.settings.tagOrder)
    }
}
