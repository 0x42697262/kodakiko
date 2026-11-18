package dev.kodakiko.ui.feature.tags

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.AnnotatedString
import dev.kodakiko.index.TagCount
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
class TagsContentTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val actions = mutableListOf<TagsAction>()
    private var backs = 0

    private val three = listOf(TagCount("beach", 2), TagCount("city", 1), TagCount("later", 0))

    private fun show(tags: List<TagCount> = three, dialog: TagsDialog? = null) {
        compose.show { TagsContent(state = TagsState(tags = tags, dialog = dialog), onAction = { actions += it }, onBack = { backs += 1 }) }
    }

    private fun node(tag: String): SemanticsNodeInteraction = compose.onNodeWithTag(tag)

    private fun click(tag: String) {
        node(tag).performClick()
        compose.waitForIdle()
    }

    private fun textShown(text: String): Boolean = compose.onAllNodes(hasText(text), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun moves(position: Int): List<CustomAccessibilityAction> =
        node(listItem(TestTags.Tags.ROW, position)).fetchSemanticsNode().config.getOrElse(SemanticsActions.CustomActions) { emptyList() }

    private fun labels(position: Int): List<String> = moves(position).map { it.label }

    private fun run(position: Int, label: String) {
        val move = moves(position).single { it.label == label }
        compose.runOnUiThread { move.action() }
        compose.waitForIdle()
    }

    private fun contains(text: String): SemanticsMatcher = hasText(text) or hasAnyDescendant(hasText(text))

    @Test
    fun eachRowShowsTheTagAndItsEntryCount() {
        show()
        assertTrue(compose.exists(TestTags.Tags.SCREEN))
        assertTrue(textShown("Edit tags"))
        node(listItem(TestTags.Tags.ROW, 0)).assert(contains("beach")).assert(contains("2 entries"))
        node(listItem(TestTags.Tags.ROW, 1)).assert(contains("city")).assert(contains("1 entry"))
        node(listItem(TestTags.Tags.ROW, 2)).assert(contains("later")).assert(contains("0 entries"))
        assertFalse(compose.exists(listItem(TestTags.Tags.ROW, 3)))
        (0 until 3).forEach {
            assertTrue(compose.exists(listItem(TagsTestTags.DRAG, it)))
            node(listItem(TestTags.Tags.RENAME, it)).assertContentDescriptionEquals("Rename tag")
            node(listItem(TestTags.Tags.DELETE, it)).assertContentDescriptionEquals("Delete tag")
        }
        compose.onNodeWithTag(TestTags.Tags.ADD, useUnmergedTree = true).assert(labeled("Add"))
        assertTrue(compose.exists(TagsTestTags.BACK))
        assertFalse(textShown("No tags yet. Add one, or tag entries from the viewer."))
    }

    @Test
    fun anEmptyListSaysHowToGetTags() {
        show(tags = emptyList())
        assertTrue(textShown("No tags yet. Add one, or tag entries from the viewer."))
        assertFalse(compose.exists(listItem(TestTags.Tags.ROW, 0)))
        assertTrue(compose.exists(TestTags.Tags.ADD))
    }

    @Test
    fun theRowButtonsTheAddButtonAndBackSendTheirActions() {
        show()
        click(listItem(TestTags.Tags.RENAME, 1))
        click(listItem(TestTags.Tags.DELETE, 0))
        click(TestTags.Tags.ADD)
        assertEquals(listOf(TagsAction.Rename("city"), TagsAction.Delete("beach"), TagsAction.Add), actions)
        click(TagsTestTags.BACK)
        assertEquals(1, backs)
    }

    @Test
    fun rowsOfferMoveUpAndMoveDownToAccessibilityServicesExceptPastTheEnds() {
        show()
        assertEquals(listOf("Move down"), labels(0))
        assertEquals(setOf("Move up", "Move down"), labels(1).toSet())
        assertEquals(listOf("Move up"), labels(2))
        run(1, "Move up")
        run(1, "Move down")
        assertEquals(listOf(TagsAction.Move(from = 1, to = 0), TagsAction.Move(from = 1, to = 2)), actions)
    }

    @Test
    fun theAddDialogEditsTheNameAndSavesOnSaveOrEnter() {
        show(dialog = TagsDialog.Add(name = "fam", invalid = false))
        assertTrue(compose.onAllNodes(hasText("Add"), useUnmergedTree = true).fetchSemanticsNodes().size >= 2)
        assertTrue(textShown("Tag name"))
        node(TestTags.Tags.NAME_FIELD).assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("fam")))
        node(TestTags.Tags.NAME_FIELD).performTextReplacement("family")
        compose.waitForIdle()
        assertEquals(TagsAction.NameChanged("family"), actions.last())
        node(TestTags.Tags.SAVE).assert(hasText("Save"))
        click(TestTags.Tags.SAVE)
        assertEquals(TagsAction.Save, actions.last())
        node(TestTags.Tags.NAME_FIELD).performImeAction()
        compose.waitForIdle()
        assertEquals(TagsAction.Save, actions.last())
        assertFalse(textShown("Tags use letters, numbers, - and _ (up to 32 characters)."))
    }

    @Test
    fun cancelDismissesTheDialog() {
        show(dialog = TagsDialog.Add(name = "", invalid = false))
        compose.onAllNodes(hasText("Cancel"), useUnmergedTree = false).onFirst().performClick()
        compose.waitForIdle()
        assertEquals(listOf<TagsAction>(TagsAction.Dismiss), actions)
    }

    @Test
    fun anInvalidNameShowsTheRule() {
        show(dialog = TagsDialog.Add(name = " ", invalid = true))
        assertTrue(textShown("Tags use letters, numbers, - and _ (up to 32 characters)."))
    }

    @Test
    fun theRenameDialogStartsFromTheCurrentName() {
        show(dialog = TagsDialog.Rename(tag = "beach", name = "beach", invalid = false))
        assertTrue(textShown("Rename tag"))
        node(TestTags.Tags.NAME_FIELD).assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("beach")))
        assertTrue(compose.exists(TestTags.Tags.SAVE))
    }

    @Test
    fun theDeleteQuestionNamesTheTagAndCountsItsEntries() {
        show(dialog = TagsDialog.Delete(tag = "beach", count = 2))
        assertTrue(textShown("Remove #beach from 2 entries? The entries stay."))
        node(TestTags.Tags.CONFIRM_DELETE).assert(hasText("Delete tag"))
        click(TestTags.Tags.CONFIRM_DELETE)
        assertEquals(listOf<TagsAction>(TagsAction.ConfirmDelete), actions)
    }

    @Test
    fun theDeleteQuestionUsesTheSingularForOneEntry() {
        show(dialog = TagsDialog.Delete(tag = "city", count = 1))
        assertTrue(textShown("Remove #city from 1 entry? The entries stay."))
        assertFalse(compose.exists(TestTags.Tags.NAME_FIELD))
    }
}
