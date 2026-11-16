package dev.kodakiko.ui.feature.edit

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import dev.kodakiko.ui.core.TestTags
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class EntryNewContentTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun whileCreatingASpinnerShows() {
        compose.show { EntryNewContent(state = EntryNewState(failed = false)) }
        assertTrue(compose.exists(TestTags.Edit.CREATING))
        assertTrue(compose.exists(TestTags.Edit.CREATING_SPINNER))
        assertFalse(compose.exists(TestTags.Compose.FAILURE))
    }

    @Test
    fun aFailedCreateShowsTheGenericError() {
        compose.show { EntryNewContent(state = EntryNewState(failed = true)) }
        assertTrue(compose.exists(TestTags.Edit.CREATING))
        assertFalse(compose.exists(TestTags.Edit.CREATING_SPINNER))
        compose.onNodeWithTag(TestTags.Compose.FAILURE).assertTextContains("Something went wrong. Try again.")
    }
}
