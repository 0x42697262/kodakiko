package dev.kodakiko.app.navigation

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.AnnotatedString
import androidx.test.core.app.ActivityScenario
import dev.kodakiko.app.FakeAndroidKeyStore
import dev.kodakiko.app.MainActivity
import dev.kodakiko.app.UpdatedV031App
import dev.kodakiko.app.V031Fixture
import dev.kodakiko.ui.core.TestTags
import dev.kodakiko.ui.core.TestTags.listItem
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class EditWiringTest {
    @get:Rule
    val compose: ComposeTestRule = createEmptyComposeRule()

    private var scenario: ActivityScenario<MainActivity>? = null

    @After
    fun tearDown() {
        scenario?.close()
        FakeAndroidKeyStore.uninstall()
    }

    @OptIn(ExperimentalTestApi::class)
    private fun awaitTag(tag: String, timeoutMs: Long = WAIT_MS) {
        compose.waitUntilAtLeastOneExists(hasTestTag(tag), timeoutMs)
    }

    private fun exists(tag: String): Boolean = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

    private fun click(tag: String) {
        awaitTag(tag)
        compose.onNodeWithTag(tag).performScrollTo().performClick()
        compose.waitForIdle()
    }

    private fun unlock() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        awaitTag(TestTags.Lock.PASSWORD)
        compose.onNodeWithTag(TestTags.Lock.PASSWORD).performScrollTo().performClick().performTextInput(V031Fixture.PASSWORD)
        compose.onNodeWithTag(TestTags.Lock.PASSWORD).performImeAction()
        awaitTag(listItem(TestTags.Gallery.TILE, 0), UNLOCK_WAIT_MS)
    }

    private fun openEditPage() {
        click(TestTags.Viewer.EDIT)
        awaitTag(TestTags.Edit.SCREEN)
        awaitTag(listItem(TestTags.Edit.PAGE, 0))
    }

    @Test
    @Config(application = UpdatedV031App::class)
    fun theViewersPencilOpensTheEditPageAndANoteTypedThereIsSavedWhenLeaving() {
        unlock()
        click(listItem(TestTags.Gallery.TILE, 0))
        awaitTag(TestTags.Viewer.SCREEN)
        openEditPage()
        assertTrue(exists(TestTags.Compose.WHEN_VALUE))
        assertTrue(exists(TestTags.Edit.TAKE_PHOTO))
        if (exists(TestTags.Edit.ADD_NOTE)) click(TestTags.Edit.ADD_NOTE) else click(TestTags.Edit.EDIT_NOTE)
        awaitTag(TestTags.Compose.NOTE)
        compose.onNodeWithTag(TestTags.Compose.NOTE).performTextReplacement("Lake day ")
        click(BOLD)
        compose.onNodeWithTag(TestTags.Compose.NOTE).performTextInput("swim")
        compose.waitForIdle()
        compose.onNodeWithTag(TestTags.Compose.NOTE)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("Lake day **swim**")))
        click(TestTags.Compose.BACK)
        awaitTag(TestTags.Viewer.SCREEN)
        openEditPage()
        awaitTag(TestTags.Markdown.VIEW)
        compose.waitUntil(WAIT_MS) {
            compose.onAllNodes(hasText("Lake day swim", substring = true), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private companion object {
        const val WAIT_MS = 15_000L
        const val UNLOCK_WAIT_MS = 120_000L
        const val BOLD = "toolbar.bold"
    }
}
