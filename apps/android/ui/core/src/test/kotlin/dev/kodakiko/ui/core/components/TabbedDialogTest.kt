package dev.kodakiko.ui.core.components

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.unit.dp
import dev.kodakiko.ui.core.R
import dev.kodakiko.ui.core.TestTags
import dev.kodakiko.ui.core.isNotShown
import dev.kodakiko.ui.core.pressBack
import dev.kodakiko.ui.core.string
import dev.kodakiko.ui.core.theme.KodakikoTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h640dp-mdpi")
class TabbedDialogTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var open by mutableStateOf(true)

    private var dismissals = 0

    private val menuClicks = mutableListOf<String>()

    private val keys = listOf("filter", "sort", "display", "group")

    private val titles get() = listOf(R.string.sheet_filter, R.string.sheet_sort, R.string.sheet_display, R.string.sheet_group).map { string(it) }

    private fun tabTag(key: String) = "${TestTags.Sheet.TAB}.$key"

    private fun pageText(index: Int) = "page ${keys[index]}"

    private fun dialog(withMenu: Boolean = false) {
        compose.setContent {
            KodakikoTheme {
                if (open) {
                    TabbedDialog(
                        onDismissRequest = {
                            dismissals++
                            open = false
                        },
                        tabs = keys.zip(titles).map { (key, title) -> TabbedDialogTab(title = title, testTag = tabTag(key)) },
                        modifier = Modifier.testTag(TestTags.Filter.SHEET),
                        tabOverflowMenuContent = if (withMenu) {
                            { closeMenu ->
                                DropdownMenuItem(
                                    text = { Text(string(R.string.filter_reset)) },
                                    onClick = {
                                        menuClicks += "reset"
                                        closeMenu()
                                    },
                                    modifier = Modifier.testTag(TestTags.Filter.RESET),
                                )
                            }
                        } else {
                            null
                        },
                    ) { page ->
                        Box(Modifier.fillMaxWidth().height(PAGE_HEIGHT.dp)) {
                            Text(pageText(page))
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun assertSelected(index: Int) {
        keys.forEachIndexed { tab, key ->
            if (tab == index) {
                compose.onNodeWithTag(tabTag(key)).assertIsSelected()
            } else {
                compose.onNodeWithTag(tabTag(key)).assertIsNotSelected()
            }
        }
        compose.onNodeWithText(pageText(index)).assertIsDisplayed()
        keys.indices.filter { it != index }.forEach { other ->
            assertTrue("page $other is visible", compose.onNodeWithText(pageText(other)).isNotShown())
        }
    }

    @Test
    fun everyTabIsShownWithItsTestTagAndTheFirstIsSelected() {
        dialog()
        keys.zip(titles).forEach { (key, title) ->
            compose.onNodeWithTag(tabTag(key)).assertIsDisplayed().assertTextEquals(title)
        }
        compose.onNodeWithTag(TestTags.Filter.SHEET).assertIsDisplayed()
        assertSelected(0)
    }

    @Test
    fun tappingATabShowsItsPage() {
        dialog()
        compose.onNodeWithTag(tabTag("sort")).performClick()
        compose.waitForIdle()
        assertSelected(1)
        compose.onNodeWithTag(tabTag("group")).performClick()
        compose.waitForIdle()
        assertSelected(3)
        assertEquals(0, dismissals)
    }

    @Test
    fun swipingThePagesMovesTheSelectedTab() {
        dialog()
        compose.onNodeWithTag(TestTags.Filter.SHEET).performTouchInput { swipeLeft(startX = right - 10f, endX = left + 10f) }
        compose.waitForIdle()
        assertSelected(1)
        compose.onNodeWithTag(TestTags.Filter.SHEET).performTouchInput { swipeRight(startX = left + 10f, endX = right - 10f) }
        compose.waitForIdle()
        assertSelected(0)
        assertEquals(0, dismissals)
    }

    @Test
    fun theDialogIsAnAdaptiveSheetThatClosesOnOutsideTapAndBack() {
        dialog()
        compose.onNodeWithTag(TestTags.Filter.SHEET).performTouchInput { click(Offset(centerX, -100f)) }
        compose.waitForIdle()
        assertEquals(1, dismissals)
        compose.onNodeWithTag(TestTags.Filter.SHEET).assertDoesNotExist()
        open = true
        compose.waitForIdle()
        compose.onNodeWithTag(TestTags.Filter.SHEET).assertIsDisplayed()
        compose.pressBack()
        assertEquals(2, dismissals)
    }

    @Test
    fun theTabOverflowMenuOpensFromAMoreButtonAndCloses() {
        dialog(withMenu = true)
        compose.onNodeWithTag(TestTags.Filter.RESET).assertDoesNotExist()
        compose.onNode(hasContentDescription(string(R.string.gallery_more)))
            .assertIsDisplayed()
            .assertContentDescriptionEquals(string(R.string.gallery_more))
            .performClick()
        compose.onNodeWithTag(TestTags.Filter.RESET).assertIsDisplayed().performClick()
        compose.waitForIdle()
        assertEquals(listOf("reset"), menuClicks)
        compose.onNodeWithTag(TestTags.Filter.RESET).assertDoesNotExist()
        assertEquals(0, dismissals)
    }

    @Test
    fun noMoreButtonWithoutOverflowContent() {
        dialog()
        compose.onNode(hasContentDescription(string(R.string.gallery_more))).assertDoesNotExist()
    }

    private companion object {
        const val PAGE_HEIGHT = 300f
    }
}
