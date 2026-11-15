package dev.kodakiko.ui.core.components

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import dev.kodakiko.ui.core.R
import dev.kodakiko.ui.core.TestIcons
import dev.kodakiko.ui.core.TestTags
import dev.kodakiko.ui.core.bounds
import dev.kodakiko.ui.core.string
import dev.kodakiko.ui.core.theme.KodakikoTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BadgePillHeaderTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun aTextBadgeShowsItsTextOnOneLine() {
        compose.setContent {
            KodakikoTheme {
                Badge(text = "12", modifier = Modifier.testTag(TestTags.Settings.BADGE))
            }
        }
        val badge = compose.onNodeWithTag(TestTags.Settings.BADGE).assertIsDisplayed().assertTextEquals("12").bounds()
        assertTrue("badge is ${badge.width}x${badge.height}", badge.width > 0f && badge.height > 0f)
    }

    @Test
    fun badgesInAGroupSitSideBySide() {
        compose.setContent {
            KodakikoTheme {
                BadgeGroup {
                    Badge(text = "3", modifier = Modifier.testTag(FIRST))
                    Badge(imageVector = TestIcons.Square, modifier = Modifier.testTag(SECOND))
                }
            }
        }
        val first = compose.onNodeWithTag(FIRST).assertIsDisplayed().bounds()
        val second = compose.onNodeWithTag(SECOND).assertIsDisplayed().bounds()
        assertTrue("second badge starts at ${second.left}, first ends at ${first.right}", second.left >= first.right - 0.5f)
        assertTrue(second.top < first.bottom && first.top < second.bottom)
    }

    @Test
    fun aPillShowsItsText() {
        compose.setContent {
            KodakikoTheme {
                Pill(text = "42", modifier = Modifier.testTag(TestTags.Gallery.TAG_TAB_ALL))
            }
        }
        compose.onNodeWithTag(TestTags.Gallery.TAG_TAB_ALL).assertIsDisplayed().assertTextEquals("42")
    }

    @Test
    fun aListGroupHeaderIsAHeadingWithItsText() {
        val tag = TestTags.listItem(TestTags.Gallery.DAY_HEADER, 0)
        compose.setContent {
            KodakikoTheme {
                Column {
                    ListGroupHeader(text = string(R.string.sheet_tags_and_dates), modifier = Modifier.testTag(tag))
                }
            }
        }
        compose.onNodeWithTag(tag)
            .assertIsDisplayed()
            .assertTextEquals(string(R.string.sheet_tags_and_dates))
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
    }

    private companion object {
        const val FIRST = "test.badge.first"
        const val SECOND = "test.badge.second"
    }
}
