package dev.kodakiko.app

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.semantics.SemanticsPropertiesAndroid
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalComposeUiApi::class)
@RunWith(RobolectricTestRunner::class)
class ResourceIdTagsTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun theAppRootExposesTestTagsAsResourceIdsForMaestro() {
        compose.waitForIdle()
        val root = compose.onNodeWithTag(TestTags.APP_ROOT, useUnmergedTree = true).fetchSemanticsNode()
        assertEquals(true, root.config.getOrNull(SemanticsPropertiesAndroid.TestTagsAsResourceId))
    }
}
