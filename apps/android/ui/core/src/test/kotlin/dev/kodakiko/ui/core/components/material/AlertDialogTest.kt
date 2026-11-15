package dev.kodakiko.ui.core.components.material

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import dev.kodakiko.data.db.AppTheme
import dev.kodakiko.ui.core.R
import dev.kodakiko.ui.core.TestTags
import dev.kodakiko.ui.core.pressBack
import dev.kodakiko.ui.core.string
import dev.kodakiko.ui.core.theme.KodakikoTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
class AlertDialogTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val events = mutableListOf<String>()

    private var open by mutableStateOf(true)

    private var errorColor = Color.Unspecified

    private var primaryColor = Color.Unspecified

    private fun dialog(withCancel: Boolean = true, destructive: Boolean = true) {
        compose.setContent {
            KodakikoTheme(appTheme = AppTheme.LIGHT) {
                errorColor = MaterialTheme.colorScheme.error
                primaryColor = MaterialTheme.colorScheme.primary
                if (open) {
                    AlertDialog(
                        onDismissRequest = {
                            events += "dismiss"
                            open = false
                        },
                        confirmButton = DialogButton(
                            text = string(R.string.settings_sign_out),
                            testTag = TestTags.Settings.SIGN_OUT_CONFIRM,
                            onClick = { events += "confirm" },
                        ),
                        modifier = Modifier.testTag(TestTags.Settings.SIGN_OUT_SHEET),
                        dismissButton = if (withCancel) {
                            DialogButton(
                                text = string(R.string.settings_cancel),
                                testTag = TestTags.Settings.CANCEL,
                                onClick = { events += "cancel" },
                            )
                        } else {
                            null
                        },
                        title = string(R.string.settings_sign_out),
                        text = string(R.string.settings_sign_out_confirm),
                        destructive = destructive,
                    )
                }
            }
        }
    }

    @Test
    fun theDialogShowsTitleTextAndButtonsInADialogWindow() {
        dialog()
        compose.onNode(isDialog()).assertExists()
        compose.onNodeWithTag(TestTags.Settings.SIGN_OUT_SHEET).assertIsDisplayed()
        compose.onNode(hasText(string(R.string.settings_sign_out_confirm)) and hasAnyAncestor(isDialog())).assertIsDisplayed()
        compose.onNodeWithTag(TestTags.Settings.SIGN_OUT_CONFIRM).assertIsDisplayed().assertTextEquals(string(R.string.settings_sign_out))
        compose.onNodeWithTag(TestTags.Settings.CANCEL).assertIsDisplayed().assertTextEquals(string(R.string.settings_cancel))
    }

    @Test
    fun confirmRunsOnlyTheConfirmAction() {
        dialog()
        compose.onNodeWithTag(TestTags.Settings.SIGN_OUT_CONFIRM).performClick()
        compose.waitForIdle()
        assertEquals(listOf("confirm"), events)
    }

    @Test
    fun cancelRunsOnlyTheCancelAction() {
        dialog()
        compose.onNodeWithTag(TestTags.Settings.CANCEL).performClick()
        compose.waitForIdle()
        assertEquals(listOf("cancel"), events)
    }

    @Test
    fun backRequestsDismissal() {
        dialog()
        compose.pressBack()
        assertEquals(listOf("dismiss"), events)
        compose.onNodeWithTag(TestTags.Settings.SIGN_OUT_SHEET).assertDoesNotExist()
    }

    @Test
    fun theCancelButtonIsOptional() {
        dialog(withCancel = false)
        compose.onNodeWithTag(TestTags.Settings.SIGN_OUT_CONFIRM).assertIsDisplayed()
        compose.onNodeWithTag(TestTags.Settings.CANCEL).assertDoesNotExist()
    }

    private fun hasColor(tag: String, color: Color): Boolean {
        val pixels = compose.onNodeWithTag(tag).captureToImage().toPixelMap()
        val target = color
        for (x in 0 until pixels.width) {
            for (y in 0 until pixels.height) {
                val pixel = pixels[x, y]
                if (abs(pixel.red - target.red) < 0.04f && abs(pixel.green - target.green) < 0.04f && abs(pixel.blue - target.blue) < 0.04f) return true
            }
        }
        return false
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "xxhdpi")
    fun aDestructiveConfirmIsDrawnInTheErrorColor() {
        dialog(destructive = true)
        compose.waitForIdle()
        assertTrue(hasColor(TestTags.Settings.SIGN_OUT_CONFIRM, errorColor))
        assertFalse(hasColor(TestTags.Settings.SIGN_OUT_CONFIRM, primaryColor))
        assertTrue(hasColor(TestTags.Settings.CANCEL, primaryColor))
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "xxhdpi")
    fun aRegularConfirmIsDrawnInThePrimaryColor() {
        dialog(destructive = false)
        compose.waitForIdle()
        assertTrue(hasColor(TestTags.Settings.SIGN_OUT_CONFIRM, primaryColor))
        assertFalse(hasColor(TestTags.Settings.SIGN_OUT_CONFIRM, errorColor))
    }
}
