package dev.kodakiko.ui.core.settings

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import dev.kodakiko.ui.core.R
import dev.kodakiko.ui.core.TestTags
import dev.kodakiko.ui.core.navigation.Route
import dev.kodakiko.ui.core.string
import dev.kodakiko.ui.core.theme.KodakikoTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SettingsSearchContentTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val opened = mutableListOf<SettingsIndexEntry>()

    private var upCount = 0

    private val sections by lazy { V031SettingsIndex.sections() }

    private fun result(position: Int) = compose.onNodeWithTag(TestTags.listItem(TestTags.Settings.RESULT, position))

    private fun resultCount(): Int =
        generateSequence(0) { it + 1 }
            .takeWhile { compose.onAllNodes(hasTestTag(TestTags.listItem(TestTags.Settings.RESULT, it))).fetchSemanticsNodes().isNotEmpty() }
            .count()

    private fun show() {
        compose.setContent {
            KodakikoTheme {
                SettingsSearchContent(
                    sections = sections,
                    onResultClick = { opened += it },
                    navigateUp = { upCount++ },
                )
            }
        }
        compose.waitForIdle()
    }

    private fun type(query: String) {
        compose.onNodeWithTag(TestTags.Settings.SEARCH_FIELD).performTextClearance()
        compose.onNodeWithTag(TestTags.Settings.SEARCH_FIELD).performTextInput(query)
        compose.waitForIdle()
    }

    @Test
    fun theFieldIsFocusedAndShowsThePlaceholderWithNoResultsYet() {
        show()
        compose.onNodeWithTag(TestTags.Settings.SEARCH_FIELD).assertIsFocused()
        compose.onNodeWithText(string(R.string.prefs_search), useUnmergedTree = true).assertIsDisplayed()
        assertEquals(0, resultCount())
        assertEquals(0, compose.onAllNodes(hasText(string(R.string.prefs_no_results))).fetchSemanticsNodes().size)
    }

    @Test
    fun aResultShowsItsTitleAndBreadcrumbsAndOpensWithItsHighlightKey() {
        show()
        type("secure screen")
        assertEquals(1, resultCount())
        result(0).assertIsDisplayed()
            .assert(hasText(string(R.string.prefs_secure_screen)))
            .assert(hasText(string(R.string.prefs_security)))
        result(0).performClick()
        compose.waitForIdle()
        val entry = opened.single()
        assertEquals(Route.SettingsSecurity, entry.route)
        assertEquals(string(R.string.prefs_secure_screen), entry.highlightKey)
    }

    @Test
    fun resultsAreListedInIndexOrder() {
        show()
        type("screen")
        val expected = listOf(
            string(R.string.prefs_start_screen) to string(R.string.prefs_appearance),
            string(R.string.prefs_full_screen) to string(R.string.prefs_appearance),
            string(R.string.prefs_keep_screen_on) to string(R.string.prefs_capture),
            string(R.string.prefs_secure_screen) to string(R.string.prefs_security),
        )
        assertEquals(expected.size, resultCount())
        expected.forEachIndexed { position, (title, breadcrumbs) ->
            result(position).assert(hasText(title)).assert(hasText(breadcrumbs))
        }
    }

    @Test
    fun aQueryWithoutMatchesSaysSoAndABlankQueryShowsNothing() {
        show()
        type("zebra")
        compose.onNodeWithText(string(R.string.prefs_no_results)).assertIsDisplayed()
        assertEquals(0, resultCount())
        type("   ")
        assertEquals(0, resultCount())
        assertEquals(0, compose.onAllNodes(hasText(string(R.string.prefs_no_results))).fetchSemanticsNodes().size)
    }

    @Test
    fun theUpButtonNavigatesUp() {
        show()
        compose.onNodeWithContentDescription(string(R.string.settings_back)).performClick()
        compose.waitForIdle()
        assertEquals(1, upCount)
    }
}
