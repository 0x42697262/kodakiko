@file:OptIn(ExperimentalMaterial3Api::class)

package dev.kodakiko.ui.core.components

import androidx.activity.ComponentActivity
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.kodakiko.ui.core.R
import dev.kodakiko.ui.core.TestIcons
import dev.kodakiko.ui.core.TestTags
import dev.kodakiko.ui.core.string
import dev.kodakiko.ui.core.theme.KodakikoTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AppBarTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val clicks = mutableListOf<String>()

    private fun overflowBar(includeAction: Boolean = true) {
        compose.setContent {
            KodakikoTheme {
                AppBar(
                    title = string(R.string.gallery_title),
                    actions = {
                        AppBarActions(
                            actions = listOfNotNull(
                                if (includeAction) {
                                    AppBar.Action(
                                        title = string(R.string.gallery_search),
                                        icon = TestIcons.Square,
                                        testTag = TestTags.Gallery.SEARCH,
                                        onClick = { clicks += "search" },
                                    )
                                } else {
                                    null
                                },
                                AppBar.OverflowAction(
                                    title = string(R.string.gallery_random),
                                    testTag = TestTags.Gallery.RANDOM,
                                    onClick = { clicks += "random" },
                                ),
                                AppBar.OverflowAction(
                                    title = string(R.string.lock_lock_now),
                                    testTag = TestTags.Lock.LOCK_NOW,
                                    onClick = { clicks += "lock" },
                                ),
                                AppBar.OverflowAction(
                                    title = string(R.string.gallery_trash),
                                    testTag = TestTags.Gallery.TRASH,
                                    onClick = { clicks += "trash" },
                                ),
                            ),
                            overflowTestTag = TestTags.Gallery.MORE,
                        )
                    },
                )
            }
        }
    }

    @Test
    fun titleAndSubtitleAreShownWithoutAnUpButtonByDefault() {
        compose.setContent {
            KodakikoTheme {
                AppBar(title = string(R.string.gallery_title), subtitle = string(R.string.gallery_trash))
            }
        }
        compose.onNodeWithText(string(R.string.gallery_title)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.gallery_trash)).assertIsDisplayed()
        compose.onNode(hasContentDescription(string(R.string.settings_back))).assertDoesNotExist()
    }

    @Test
    fun upButtonCarriesItsTestTagAndTheBackLabelAndNavigatesUp() {
        var ups = 0
        compose.setContent {
            KodakikoTheme {
                AppBar(
                    title = string(R.string.gallery_trash),
                    navigateUp = { ups++ },
                    navigateUpTestTag = TestTags.Trash.BACK,
                )
            }
        }
        compose.onNodeWithTag(TestTags.Trash.BACK)
            .assertIsDisplayed()
            .assertContentDescriptionEquals(string(R.string.settings_back))
            .performClick()
        assertEquals(1, ups)
    }

    @Test
    fun anActionIsAnIconButtonLabeledByItsTitle() {
        overflowBar()
        compose.onNodeWithTag(TestTags.Gallery.SEARCH)
            .assertIsDisplayed()
            .assertContentDescriptionEquals(string(R.string.gallery_search))
            .performClick()
        assertEquals(listOf("search"), clicks)
    }

    @Test
    fun aDisabledActionCannotBeClicked() {
        compose.setContent {
            KodakikoTheme {
                AppBar(
                    title = string(R.string.gallery_title),
                    actions = {
                        AppBarActions(
                            actions = listOf(
                                AppBar.Action(
                                    title = string(R.string.gallery_search),
                                    icon = TestIcons.Square,
                                    testTag = TestTags.Gallery.SEARCH,
                                    onClick = { clicks += "search" },
                                    enabled = false,
                                ),
                            ),
                        )
                    },
                )
            }
        }
        compose.onNodeWithTag(TestTags.Gallery.SEARCH).assertIsNotEnabled().performClick()
        assertEquals(emptyList<String>(), clicks)
    }

    @Test
    fun overflowMenuOpensFromTheMoreButtonAndShowsItsItemsInOrder() {
        overflowBar()
        compose.onNodeWithTag(TestTags.Gallery.RANDOM).assertDoesNotExist()
        compose.onNodeWithTag(TestTags.Gallery.MORE)
            .assertIsDisplayed()
            .assertIsEnabled()
            .assertContentDescriptionEquals(string(R.string.gallery_more))
            .performClick()
        val random = compose.onNodeWithTag(TestTags.Gallery.RANDOM).assertIsDisplayed()
        val lock = compose.onNodeWithTag(TestTags.Lock.LOCK_NOW).assertIsDisplayed()
        val trash = compose.onNodeWithTag(TestTags.Gallery.TRASH).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.gallery_random)).assertIsDisplayed()
        val tops = listOf(random, lock, trash).map { it.fetchSemanticsNode().boundsInRoot.top }
        assertEquals(tops.sorted(), tops)
        assertEquals(3, tops.distinct().size)
    }

    @Test
    fun choosingAnOverflowItemRunsItOnceAndClosesTheMenu() {
        overflowBar()
        compose.onNodeWithTag(TestTags.Gallery.MORE).performClick()
        compose.onNodeWithTag(TestTags.Lock.LOCK_NOW).performClick()
        compose.waitForIdle()
        assertEquals(listOf("lock"), clicks)
        compose.onNodeWithTag(TestTags.Lock.LOCK_NOW).assertDoesNotExist()
        compose.onNodeWithTag(TestTags.Gallery.MORE).performClick()
        compose.onNodeWithTag(TestTags.Gallery.TRASH).performClick()
        compose.waitForIdle()
        assertEquals(listOf("lock", "trash"), clicks)
    }

    @Test
    fun overflowItemsAreClickableMenuEntries() {
        overflowBar(includeAction = false)
        compose.onNodeWithTag(TestTags.Gallery.MORE).performClick()
        compose.onNode(hasClickAction() and hasTestTag(TestTags.Gallery.RANDOM)).assertIsDisplayed()
        compose.onNodeWithTag(TestTags.Gallery.SEARCH).assertDoesNotExist()
    }

    @Test
    fun noOverflowButtonWithoutOverflowActions() {
        compose.setContent {
            KodakikoTheme {
                AppBar(
                    title = string(R.string.gallery_title),
                    actions = {
                        AppBarActions(
                            actions = listOf(
                                AppBar.Action(
                                    title = string(R.string.gallery_search),
                                    icon = TestIcons.Square,
                                    testTag = TestTags.Gallery.SEARCH,
                                    onClick = {},
                                ),
                            ),
                            overflowTestTag = TestTags.Gallery.MORE,
                        )
                    },
                )
            }
        }
        compose.onNodeWithTag(TestTags.Gallery.SEARCH).assertIsDisplayed()
        compose.onNodeWithTag(TestTags.Gallery.MORE).assertDoesNotExist()
        compose.onNode(hasContentDescription(string(R.string.gallery_more))).assertDoesNotExist()
    }

    @Test
    fun titleContentOverloadRendersCustomContent() {
        compose.setContent {
            KodakikoTheme {
                AppBar(titleContent = { Text(string(R.string.welcome_title)) })
            }
        }
        compose.onNodeWithText(string(R.string.welcome_title)).assertIsDisplayed()
    }
}
