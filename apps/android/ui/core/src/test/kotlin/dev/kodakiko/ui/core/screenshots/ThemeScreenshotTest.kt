@file:OptIn(ExperimentalMaterial3Api::class)

package dev.kodakiko.ui.core.screenshots

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kodakiko.data.db.AppTheme
import dev.kodakiko.ui.core.R
import dev.kodakiko.ui.core.Screenshots
import dev.kodakiko.ui.core.TestIcons
import dev.kodakiko.ui.core.TestTags
import dev.kodakiko.ui.core.captureScreenshot
import dev.kodakiko.ui.core.components.AppBar
import dev.kodakiko.ui.core.components.AppBarActions
import dev.kodakiko.ui.core.components.Badge
import dev.kodakiko.ui.core.components.CheckboxItem
import dev.kodakiko.ui.core.components.ListGroupHeader
import dev.kodakiko.ui.core.components.Pill
import dev.kodakiko.ui.core.components.SortItem
import dev.kodakiko.ui.core.components.TriState
import dev.kodakiko.ui.core.components.TriStateItem
import dev.kodakiko.ui.core.components.material.Scaffold
import dev.kodakiko.ui.core.string
import dev.kodakiko.ui.core.theme.KodakikoTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@Composable
fun ThemeSample() {
    Scaffold(
        topBar = {
            AppBar(
                title = string(R.string.gallery_title),
                subtitle = string(R.string.tabs_gallery),
                navigateUp = {},
                navigateUpTestTag = TestTags.Trash.BACK,
                actions = {
                    AppBarActions(
                        actions = listOf(
                            AppBar.Action(title = string(R.string.gallery_search), icon = TestIcons.Square, testTag = TestTags.Gallery.SEARCH, onClick = {}),
                            AppBar.OverflowAction(title = string(R.string.gallery_random), testTag = TestTags.Gallery.RANDOM, onClick = {}),
                        ),
                        overflowTestTag = TestTags.Gallery.MORE,
                    )
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding)) {
            Swatches(MaterialTheme.colorScheme)
            HorizontalDivider()
            ListGroupHeader(text = string(R.string.sheet_tags_and_dates))
            TriStateItem(label = string(R.string.sheet_has_video), state = TriState.ENABLED_IS, onClick = {})
            TriStateItem(label = string(R.string.sheet_has_note), state = TriState.ENABLED_NOT, onClick = {})
            SortItem(label = string(R.string.sort_taken_at), sortDescending = true, onClick = {})
            CheckboxItem(label = string(R.string.sheet_favorite), checked = true, onClick = {})
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(onClick = {}) { Text(string(R.string.welcome_continue)) }
                OutlinedButton(onClick = {}) { Text(string(R.string.settings_cancel)) }
                Pill(text = "42")
                Badge(text = "3")
            }
            Text(
                text = string(R.string.settings_sign_out_confirm),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
    }
}

@Composable
private fun Swatches(scheme: ColorScheme) {
    val roles = listOf(
        "primary" to scheme.primary,
        "onPrimary" to scheme.onPrimary,
        "secondaryContainer" to scheme.secondaryContainer,
        "background" to scheme.background,
        "onBackground" to scheme.onBackground,
        "surface" to scheme.surface,
        "surfaceVariant" to scheme.surfaceVariant,
        "surfaceContainer" to scheme.surfaceContainer,
        "surfaceContainerHigh" to scheme.surfaceContainerHigh,
        "onSurfaceVariant" to scheme.onSurfaceVariant,
        "outline" to scheme.outline,
        "error" to scheme.error,
    )
    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        roles.chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth()) {
                pair.forEach { (name, color) -> Swatch(name, color, Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun Swatch(name: String, color: Color, modifier: Modifier) {
    Row(modifier.padding(vertical = 2.dp)) {
        Box(Modifier.width(28.dp).height(16.dp).background(MaterialTheme.colorScheme.outline).padding(1.dp).background(color))
        Text(text = name, fontSize = 11.sp, color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.padding(start = 6.dp))
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = Screenshots.QUALIFIERS)
class ThemeScreenshotTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun capture(name: String, appTheme: AppTheme, pureBlack: Boolean, dynamicColor: Boolean) {
        compose.setContent {
            KodakikoTheme(appTheme = appTheme, pureBlack = pureBlack, dynamicColor = dynamicColor) {
                ThemeSample()
            }
        }
        compose.waitForIdle()
        compose.onRoot().captureScreenshot("theme/$name")
    }

    @Test
    fun light() = capture("light", AppTheme.LIGHT, pureBlack = false, dynamicColor = false)

    @Test
    fun dark() = capture("dark", AppTheme.DARK, pureBlack = false, dynamicColor = false)

    @Test
    fun darkPureBlack() = capture("dark_pure_black", AppTheme.DARK, pureBlack = true, dynamicColor = false)

    @Test
    fun lightDynamic() = capture("light_dynamic", AppTheme.LIGHT, pureBlack = false, dynamicColor = true)

    @Test
    fun darkDynamic() = capture("dark_dynamic", AppTheme.DARK, pureBlack = false, dynamicColor = true)

    @Test
    fun darkPureBlackDynamic() = capture("dark_pure_black_dynamic", AppTheme.DARK, pureBlack = true, dynamicColor = true)

    @Test
    fun lightFontScale130() {
        RuntimeEnvironment.setFontScale(FONT_SCALE)
        capture("light_font_scale_130", AppTheme.LIGHT, pureBlack = false, dynamicColor = false)
    }

    private companion object {
        const val FONT_SCALE = 1.3f
    }
}
