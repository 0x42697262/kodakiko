@file:OptIn(ExperimentalMaterial3Api::class)

package dev.kodakiko.ui.core.components

import androidx.activity.ComponentActivity
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.AnnotatedString
import dev.kodakiko.ui.core.R
import dev.kodakiko.ui.core.TestTags
import dev.kodakiko.ui.core.string
import dev.kodakiko.ui.core.theme.KodakikoTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SearchToolbarTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var query by mutableStateOf<String?>(null)

    private val changes = mutableListOf<String?>()

    private val searches = mutableListOf<String>()

    private fun toolbar(initial: String?, searchEnabled: Boolean = true) {
        query = initial
        compose.setContent {
            KodakikoTheme {
                SearchToolbar(
                    searchQuery = query,
                    onChangeSearchQuery = {
                        changes += it
                        query = it
                    },
                    titleContent = { Text(string(R.string.gallery_title)) },
                    searchEnabled = searchEnabled,
                    placeholderText = string(R.string.gallery_search_field),
                    searchTestTag = TestTags.Gallery.SEARCH,
                    searchFieldTestTag = TestTags.Gallery.SEARCH_FIELD,
                    onSearch = { searches += it },
                )
            }
        }
    }

    private fun fieldText(text: String) =
        SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(text))

    @Test
    fun closedSearchShowsTheTitleAndASearchButton() {
        toolbar(initial = null)
        compose.onNodeWithText(string(R.string.gallery_title)).assertIsDisplayed()
        compose.onNodeWithTag(TestTags.Gallery.SEARCH_FIELD).assertDoesNotExist()
        compose.onNodeWithTag(TestTags.Gallery.SEARCH)
            .assertIsDisplayed()
            .assertContentDescriptionEquals(string(R.string.gallery_search))
    }

    @Test
    fun theSearchButtonOpensAnEmptySearch() {
        toolbar(initial = null)
        compose.onNodeWithTag(TestTags.Gallery.SEARCH).performClick()
        compose.waitForIdle()
        assertEquals(listOf<String?>(""), changes)
        compose.onNodeWithTag(TestTags.Gallery.SEARCH_FIELD).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.gallery_title)).assertDoesNotExist()
        compose.onNodeWithText(string(R.string.gallery_search_field), useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun typingReportsTheQuery() {
        toolbar(initial = "")
        compose.onNodeWithTag(TestTags.Gallery.SEARCH_FIELD).performTextInput("beach")
        compose.waitForIdle()
        assertEquals("beach", changes.last())
        assertEquals("beach", query)
        compose.onNodeWithTag(TestTags.Gallery.SEARCH_FIELD).assert(fieldText("beach"))
    }

    @Test
    fun aQueryChangedFromOutsideIsShownInTheField() {
        toolbar(initial = "")
        compose.runOnIdle { query = "sunset" }
        compose.onNode(hasSetTextAction() and fieldText("sunset")).assertIsDisplayed()
    }

    @Test
    fun theClearButtonEmptiesTheQueryButKeepsSearchOpen() {
        toolbar(initial = "")
        compose.onNode(hasContentDescription(string(R.string.settings_clear_action))).assertDoesNotExist()
        compose.onNodeWithTag(TestTags.Gallery.SEARCH_FIELD).performTextInput("beach")
        compose.onNode(hasContentDescription(string(R.string.settings_clear_action))).assertIsDisplayed().performClick()
        compose.waitForIdle()
        assertEquals("", changes.last())
        compose.onNodeWithTag(TestTags.Gallery.SEARCH_FIELD).assertIsDisplayed().assert(fieldText(""))
    }

    @Test
    fun theUpButtonClosesSearchAndRestoresTheTitle() {
        toolbar(initial = "beach")
        compose.onNode(hasContentDescription(string(R.string.settings_back))).assertIsDisplayed().performClick()
        compose.waitForIdle()
        assertEquals(null, changes.last())
        compose.onNodeWithTag(TestTags.Gallery.SEARCH_FIELD).assertDoesNotExist()
        compose.onNodeWithText(string(R.string.gallery_title)).assertIsDisplayed()
        compose.onNodeWithTag(TestTags.Gallery.SEARCH).assertIsDisplayed()
    }

    @Test
    fun theKeyboardSearchActionSubmitsANonBlankQuery() {
        toolbar(initial = "")
        compose.onNodeWithTag(TestTags.Gallery.SEARCH_FIELD).performImeAction()
        assertEquals(emptyList<String>(), searches)
        compose.onNodeWithTag(TestTags.Gallery.SEARCH_FIELD).performTextInput("beach")
        compose.onNodeWithTag(TestTags.Gallery.SEARCH_FIELD).performImeAction()
        compose.waitForIdle()
        assertEquals(listOf("beach"), searches)
    }

    @Test
    fun searchCanBeHidden() {
        toolbar(initial = null, searchEnabled = false)
        compose.onNodeWithText(string(R.string.gallery_title)).assertIsDisplayed()
        compose.onNodeWithTag(TestTags.Gallery.SEARCH).assertDoesNotExist()
        compose.onNode(hasContentDescription(string(R.string.gallery_search))).assertDoesNotExist()
        compose.onNode(hasText(string(R.string.gallery_search_field))).assertDoesNotExist()
    }
}
