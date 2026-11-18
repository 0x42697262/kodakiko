package dev.kodakiko.ui.core.settings

import androidx.activity.ComponentActivity
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.kodakiko.ui.core.R
import dev.kodakiko.ui.core.TestTags
import dev.kodakiko.ui.core.isNotShown
import dev.kodakiko.ui.core.string
import dev.kodakiko.ui.core.theme.KodakikoTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PreferenceScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val rowCount = 40

    private val targetRow = 34

    private fun rowTag(index: Int) = "preference.row.$index"

    private fun rowTitle(index: Int) = "Row $index"

    private fun rows(): List<Preference.PreferenceItem.SwitchPreference> = (0 until rowCount).map { index ->
        Preference.PreferenceItem.SwitchPreference(
            value = index % 2 == 0,
            title = rowTitle(index),
            testTag = rowTag(index),
            onValueChanged = {},
        )
    }

    private fun highlighted(value: Boolean) = SemanticsMatcher.expectValue(PreferenceHighlighted, value)

    private fun show(highlightKey: String?) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            KodakikoTheme {
                PreferenceScreen(items = rows(), highlightKey = highlightKey)
            }
        }
    }

    @Test
    fun withoutAHighlightKeyTheListStartsAtTheTopAndNothingIsHighlighted() {
        show(highlightKey = null)
        compose.mainClock.advanceTimeBy(HIGHLIGHT_VISIBLE_AT_MS)
        compose.onNodeWithTag(rowTag(0)).assertIsDisplayed().assert(highlighted(false))
        assertTrue(compose.onNodeWithTag(rowTag(targetRow)).isNotShown())
    }

    @Test
    fun theHighlightedItemIsScrolledIntoViewAndFlashesForAFewSeconds() {
        show(highlightKey = rowTitle(targetRow))
        compose.mainClock.advanceTimeBy(HIGHLIGHT_VISIBLE_AT_MS)
        compose.onNodeWithTag(rowTag(targetRow)).assertIsDisplayed().assert(highlighted(true))
        val neighbor = compose.onAllNodes(hasTestTag(rowTag(targetRow - 1))).fetchSemanticsNodes()
        if (neighbor.isNotEmpty()) compose.onNodeWithTag(rowTag(targetRow - 1)).assert(highlighted(false))
        compose.mainClock.advanceTimeBy(HIGHLIGHT_OVER_AT_MS - HIGHLIGHT_VISIBLE_AT_MS)
        compose.onNodeWithTag(rowTag(targetRow)).assertIsDisplayed().assert(highlighted(false))
    }

    @Test
    fun aGroupedItemIsFoundByItsTitleToo() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            KodakikoTheme {
                PreferenceScreen(
                    items = listOf(
                        Preference.PreferenceGroup(title = string(R.string.prefs_appearance), preferenceItems = rows()),
                    ),
                    highlightKey = rowTitle(targetRow),
                )
            }
        }
        compose.mainClock.advanceTimeBy(HIGHLIGHT_VISIBLE_AT_MS)
        compose.onNodeWithTag(rowTag(targetRow)).assertIsDisplayed().assert(highlighted(true))
    }

    @Test
    fun aPreferenceScaffoldShowsTheTitleTheItemsAndAnUpButton() {
        var ups = 0
        compose.setContent {
            KodakikoTheme {
                PreferenceScaffold(
                    title = string(R.string.prefs_appearance),
                    items = listOf(
                        Preference.PreferenceItem.SwitchPreference(
                            value = true,
                            title = string(R.string.prefs_full_screen),
                            testTag = TestTags.Settings.FULL_SCREEN,
                            onValueChanged = {},
                        ),
                    ),
                    onBackPressed = { ups++ },
                    modifier = Modifier.testTag(TestTags.Settings.APPEARANCE_SCREEN),
                )
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag(TestTags.Settings.APPEARANCE_SCREEN).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.prefs_appearance)).assertIsDisplayed()
        compose.onNodeWithTag(TestTags.Settings.FULL_SCREEN).assertIsDisplayed().assert(hasText(string(R.string.prefs_full_screen)))
        compose.onNodeWithContentDescription(string(R.string.settings_back)).performClick()
        compose.waitForIdle()
        assertEquals(1, ups)
    }

    private companion object {
        const val HIGHLIGHT_VISIBLE_AT_MS = 1_500L
        const val HIGHLIGHT_OVER_AT_MS = 6_000L
    }
}
