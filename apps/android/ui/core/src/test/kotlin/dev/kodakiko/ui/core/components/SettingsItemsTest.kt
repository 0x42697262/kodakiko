package dev.kodakiko.ui.core.components

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
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
class SettingsItemsTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val videoTag = "${TestTags.Sheet.FILTER}.video"

    private val noteTag = "${TestTags.Sheet.FILTER}.note"

    private fun toggleable(state: ToggleableState) = SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, state)

    private fun role(role: Role) = SemanticsMatcher.expectValue(SemanticsProperties.Role, role)

    @Test
    fun triStateCyclesOffIncludeExcludeLikeV031() {
        assertEquals(TriState.ENABLED_IS, TriState.DISABLED.next())
        assertEquals(TriState.ENABLED_NOT, TriState.ENABLED_IS.next())
        assertEquals(TriState.DISABLED, TriState.ENABLED_NOT.next())
    }

    @Test
    fun aTriStateItemReportsItsStateAndAsksForTheNextOneOnClick() {
        var state by mutableStateOf(TriState.DISABLED)
        val requested = mutableListOf<TriState>()
        compose.setContent {
            KodakikoTheme {
                TriStateItem(
                    label = string(R.string.sheet_has_video),
                    state = state,
                    onClick = {
                        requested += it
                        state = it
                    },
                    modifier = Modifier.testTag(videoTag),
                )
            }
        }
        val item = compose.onNodeWithTag(videoTag)
        item.assertIsDisplayed().assert(hasText(string(R.string.sheet_has_video))).assert(role(Role.Checkbox)).assert(toggleable(ToggleableState.Off))
        item.performClick()
        compose.onNodeWithTag(videoTag).assert(toggleable(ToggleableState.On))
        item.performClick()
        compose.onNodeWithTag(videoTag).assert(toggleable(ToggleableState.Indeterminate))
        item.performClick()
        compose.onNodeWithTag(videoTag).assert(toggleable(ToggleableState.Off))
        assertEquals(listOf(TriState.ENABLED_IS, TriState.ENABLED_NOT, TriState.DISABLED), requested)
    }

    @Test
    fun aDisabledOrReadOnlyTriStateItemIgnoresClicks() {
        val requested = mutableListOf<TriState>()
        compose.setContent {
            KodakikoTheme {
                Column {
                    TriStateItem(
                        label = string(R.string.sheet_has_video),
                        state = TriState.ENABLED_IS,
                        onClick = { requested += it },
                        modifier = Modifier.testTag(videoTag),
                        enabled = false,
                    )
                    TriStateItem(
                        label = string(R.string.sheet_has_note),
                        state = TriState.ENABLED_NOT,
                        onClick = null,
                        modifier = Modifier.testTag(noteTag),
                    )
                }
            }
        }
        compose.onNodeWithTag(videoTag).assert(toggleable(ToggleableState.On)).performClick()
        compose.onNodeWithTag(noteTag).assert(toggleable(ToggleableState.Indeterminate)).performClick()
        assertEquals(emptyList<TriState>(), requested)
    }

    @Test
    fun aSortItemIsARadioSelectedOnlyWhenItHasADirection() {
        var descending by mutableStateOf<Boolean?>(null)
        var clicks = 0
        val tag = "${TestTags.Sheet.SORT}.takenAt"
        compose.setContent {
            KodakikoTheme {
                SortItem(
                    label = string(R.string.sort_taken_at),
                    sortDescending = descending,
                    onClick = { clicks++ },
                    modifier = Modifier.testTag(tag),
                )
            }
        }
        compose.onNodeWithTag(tag).assertIsDisplayed().assert(hasText(string(R.string.sort_taken_at))).assert(role(Role.RadioButton)).assertIsNotSelected()
        descending = true
        compose.onNodeWithTag(tag).assertIsSelected()
        descending = false
        compose.onNodeWithTag(tag).assertIsSelected().performClick()
        assertEquals(1, clicks)
    }

    @Test
    fun aCheckboxItemReportsItsStateAndClicks() {
        var checked by mutableStateOf(false)
        compose.setContent {
            KodakikoTheme {
                CheckboxItem(
                    label = string(R.string.sheet_favorite),
                    checked = checked,
                    onClick = { checked = !checked },
                    modifier = Modifier.testTag(TestTags.Sheet.GROUP),
                )
            }
        }
        compose.onNodeWithTag(TestTags.Sheet.GROUP)
            .assertIsDisplayed()
            .assert(hasText(string(R.string.sheet_favorite)))
            .assert(role(Role.Checkbox))
            .assert(toggleable(ToggleableState.Off))
            .performClick()
        compose.onNodeWithTag(TestTags.Sheet.GROUP).assert(toggleable(ToggleableState.On))
    }

    @Test
    fun aSliderItemExposesItsRangeShowsItsValueTextAndReportsWholeSteps() {
        var columns by mutableIntStateOf(3)
        val changes = mutableListOf<Int>()
        compose.setContent {
            KodakikoTheme {
                SliderItem(
                    value = columns,
                    valueRange = 0..10,
                    label = string(R.string.prefs_columns_portrait),
                    onChange = {
                        changes += it
                        columns = it
                    },
                    modifier = Modifier.testTag(TestTags.Sheet.COLUMNS),
                    valueString = if (columns == 0) string(R.string.prefs_columns_auto) else columns.toString(),
                )
            }
        }
        val within = hasTestTag(TestTags.Sheet.COLUMNS) or hasAnyAncestor(hasTestTag(TestTags.Sheet.COLUMNS))
        val slider = SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress) and within
        compose.onNode(hasText(string(R.string.prefs_columns_portrait)) and within).assertIsDisplayed()
        compose.onNode(hasText("3") and within).assertIsDisplayed()
        compose.onNode(slider).assert(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo(3f, 0f..10f, 9)))
        compose.onNode(slider).performSemanticsAction(SemanticsActions.SetProgress) { it(7.4f) }
        compose.waitForIdle()
        assertEquals(7, changes.last())
        compose.onNode(hasText("7") and within).assertIsDisplayed()
        compose.onNode(slider).performSemanticsAction(SemanticsActions.SetProgress) { it(0f) }
        compose.waitForIdle()
        assertEquals(0, changes.last())
        compose.onNode(hasText(string(R.string.prefs_columns_auto)) and within).assertIsDisplayed()
    }

    @Test
    fun aHeadingItemIsAHeading() {
        compose.setContent {
            KodakikoTheme {
                HeadingItem(text = string(R.string.sheet_tags_and_dates))
            }
        }
        compose.onNode(hasText(string(R.string.sheet_tags_and_dates)))
            .assertIsDisplayed()
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
    }
}
