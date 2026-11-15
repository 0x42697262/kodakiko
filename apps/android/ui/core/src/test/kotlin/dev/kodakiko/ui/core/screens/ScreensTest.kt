@file:OptIn(ExperimentalMaterial3Api::class)

package dev.kodakiko.ui.core.screens

import androidx.activity.ComponentActivity
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import dev.kodakiko.ui.core.R
import dev.kodakiko.ui.core.TestIcons
import dev.kodakiko.ui.core.TestTags
import dev.kodakiko.ui.core.bounds
import dev.kodakiko.ui.core.string
import dev.kodakiko.ui.core.theme.KodakikoTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
class ScreensTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val clicks = mutableListOf<String>()

    private fun textsBelow(tag: String): List<String> =
        compose.onAllNodes(hasAnyAncestor(hasTestTag(tag)), useUnmergedTree = true).fetchSemanticsNodes()
            .flatMap { node -> node.config.getOrElse(SemanticsProperties.Text) { emptyList() } }
            .map { it.text }

    @Test
    fun emptyScreenShowsOnlyItsMessageWithoutRandomDecoration() {
        compose.setContent {
            KodakikoTheme {
                EmptyScreen(message = string(R.string.gallery_empty), modifier = Modifier.testTag(TestTags.Gallery.EMPTY))
            }
        }
        compose.onNodeWithTag(TestTags.Gallery.EMPTY).assertIsDisplayed()
        compose.onNode(hasText(string(R.string.gallery_empty)) and hasAnyAncestor(hasTestTag(TestTags.Gallery.EMPTY))).assertIsDisplayed()
        assertEquals(listOf(string(R.string.gallery_empty)), textsBelow(TestTags.Gallery.EMPTY))
    }

    @Test
    fun emptyScreenActionsAreButtonsWithTheirTestTags() {
        compose.setContent {
            KodakikoTheme {
                EmptyScreen(
                    message = string(R.string.gallery_empty),
                    modifier = Modifier.testTag(TestTags.Gallery.EMPTY),
                    actions = listOf(
                        EmptyScreenAction(
                            title = string(R.string.gallery_clear_filters),
                            icon = TestIcons.Square,
                            testTag = TestTags.Gallery.CLEAR_FILTERS,
                            onClick = { clicks += "clear" },
                        ),
                    ),
                )
            }
        }
        compose.onNodeWithTag(TestTags.Gallery.CLEAR_FILTERS).assertIsDisplayed().performClick()
        assertEquals(listOf("clear"), clicks)
        assertEquals(listOf(string(R.string.gallery_empty), string(R.string.gallery_clear_filters)), textsBelow(TestTags.Gallery.EMPTY))
    }

    @Test
    fun loadingScreenFillsTheScreenWithACenteredLabeledSpinner() {
        compose.setContent {
            KodakikoTheme {
                LoadingScreen(modifier = Modifier.testTag(TestTags.App.LOADING_SCREEN), indicatorTestTag = TestTags.App.LOADING)
            }
        }
        val root = compose.onRoot().bounds()
        val screen = compose.onNodeWithTag(TestTags.App.LOADING_SCREEN).assertIsDisplayed().bounds()
        assertEquals(root, screen)
        val spinner = compose.onNodeWithTag(TestTags.App.LOADING)
            .assertIsDisplayed()
            .assertContentDescriptionEquals(string(R.string.app_loading))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo.Indeterminate))
            .bounds()
        assertTrue(abs(spinner.center.x - root.center.x) <= 1f)
        assertTrue(abs(spinner.center.y - root.center.y) <= 1f)
    }

    private fun welcome(canAccept: Boolean, withReject: Boolean) {
        compose.setContent {
            KodakikoTheme {
                InfoScreen(
                    icon = TestIcons.Square,
                    headingText = string(R.string.welcome_title),
                    subtitleText = string(R.string.welcome_subtitle),
                    acceptText = string(R.string.welcome_continue),
                    onAcceptClick = { clicks += "accept" },
                    modifier = Modifier.testTag(TestTags.Welcome.SCREEN),
                    canAccept = canAccept,
                    acceptTestTag = TestTags.Welcome.CONTINUE,
                    rejectText = if (withReject) string(R.string.welcome_local) else null,
                    onRejectClick = if (withReject) ({ clicks += "reject" }) else null,
                    rejectTestTag = TestTags.Welcome.LOCAL,
                    headingTestTag = TestTags.Welcome.TITLE,
                    subtitleTestTag = TestTags.Welcome.SUBTITLE,
                ) {
                    Text(string(R.string.welcome_server_label), modifier = Modifier.testTag(TestTags.Welcome.SERVER_LABEL))
                }
            }
        }
    }

    @Test
    fun infoScreenShowsHeadingSubtitleContentAndAcceptButton() {
        welcome(canAccept = true, withReject = true)
        compose.onNodeWithTag(TestTags.Welcome.SCREEN).assertIsDisplayed()
        compose.onNodeWithTag(TestTags.Welcome.TITLE).assertIsDisplayed().assertTextEquals(string(R.string.welcome_title))
        compose.onNodeWithTag(TestTags.Welcome.SUBTITLE).assertIsDisplayed().assertTextEquals(string(R.string.welcome_subtitle))
        compose.onNodeWithTag(TestTags.Welcome.SERVER_LABEL).assertIsDisplayed()
        compose.onNodeWithTag(TestTags.Welcome.TITLE).assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        val title = compose.onNodeWithTag(TestTags.Welcome.TITLE).bounds()
        val accept = compose.onNodeWithTag(TestTags.Welcome.CONTINUE).assertIsDisplayed().assertIsEnabled().bounds()
        assertTrue("accept button sits at the bottom", accept.top > title.bottom)
        compose.onNodeWithTag(TestTags.Welcome.CONTINUE).performClick()
        compose.onNodeWithTag(TestTags.Welcome.LOCAL).assertIsDisplayed().assertTextEquals(string(R.string.welcome_local)).performClick()
        assertEquals(listOf("accept", "reject"), clicks)
    }

    @Test
    fun infoScreenAcceptCanBeDisabled() {
        welcome(canAccept = false, withReject = false)
        compose.onNodeWithTag(TestTags.Welcome.CONTINUE).assertIsNotEnabled().performClick()
        compose.onNodeWithTag(TestTags.Welcome.LOCAL).assertDoesNotExist()
        assertEquals(emptyList<String>(), clicks)
    }
}
