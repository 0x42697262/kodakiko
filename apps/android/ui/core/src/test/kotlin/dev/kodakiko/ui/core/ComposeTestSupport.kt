package dev.kodakiko.ui.core

import androidx.activity.ComponentActivity
import androidx.activity.ComponentDialog
import androidx.annotation.StringRes
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathData
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.github.takahirom.roborazzi.roborazziSystemPropertyTaskType
import org.robolectric.shadows.ShadowDialog
import java.io.File

fun string(@StringRes id: Int, vararg args: Any): String =
    ApplicationProvider.getApplicationContext<android.content.Context>().getString(id, *args)

fun AndroidComposeTestRule<*, ComponentActivity>.pressBack() {
    runOnIdle {
        val dialog = ShadowDialog.getLatestDialog()
        if (dialog is ComponentDialog && dialog.isShowing) {
            dialog.onBackPressedDispatcher.onBackPressed()
        } else {
            activity.onBackPressedDispatcher.onBackPressed()
        }
    }
    waitForIdle()
}

fun SemanticsNodeInteraction.bounds(): Rect = fetchSemanticsNode().boundsInRoot

fun SemanticsNodeInteraction.isNotShown(): Boolean = runCatching { assertIsDisplayed() }.isFailure

object TestIcons {
    val Square: ImageVector = ImageVector.Builder(
        name = "TestSquare",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).addPath(
        pathData = PathData {
            moveTo(4f, 4f)
            lineTo(20f, 4f)
            lineTo(20f, 20f)
            lineTo(4f, 20f)
            close()
        },
        fill = SolidColor(Color.Black),
    ).build()
}

object Screenshots {
    const val QUALIFIERS = "w360dp-h640dp-xhdpi"

    private const val DIRECTORY = "src/test/screenshots"

    fun path(name: String): String = "$DIRECTORY/$name.png"

    fun options(name: String): RoborazziOptions {
        val requested = roborazziSystemPropertyTaskType()
        val taskType = when {
            requested.isEnabled() -> requested
            File(path(name)).isFile -> RoborazziTaskType.Verify
            else -> RoborazziTaskType.Record
        }
        return RoborazziOptions(taskType = taskType)
    }
}

fun SemanticsNodeInteraction.captureScreenshot(name: String) {
    captureRoboImage(Screenshots.path(name), Screenshots.options(name))
}

fun captureScreenScreenshot(name: String) {
    captureScreenRoboImage(Screenshots.path(name), Screenshots.options(name))
}
