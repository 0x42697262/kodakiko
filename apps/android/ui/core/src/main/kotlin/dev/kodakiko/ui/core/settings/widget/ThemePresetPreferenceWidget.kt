// Adapted from https://github.com/mihonapp/mihon
// Copyright 2015 Javier Tomás, 2024 Mihon Open Source Project, Apache-2.0
package dev.kodakiko.ui.core.settings.widget

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import dev.kodakiko.data.db.AppTheme
import dev.kodakiko.data.db.ThemePreset
import dev.kodakiko.ui.core.TestTags
import dev.kodakiko.ui.core.TestTags.listItem
import dev.kodakiko.ui.core.settings.SettingsConstants
import dev.kodakiko.ui.core.theme.KodakikoTheme
import dev.kodakiko.ui.core.theme.ThemePresets

@Composable
fun ThemePresetPreferenceWidget(
    selected: ThemePreset,
    appTheme: AppTheme,
    pureBlack: Boolean,
    onSelect: (ThemePreset) -> Unit,
    modifier: Modifier = Modifier,
) {
    val choices = ThemePreset.entries
    FlowRow(
        modifier = modifier.fillMaxWidth().padding(horizontal = SettingsConstants.PREFS_HORIZONTAL_PADDING).testTag(TestTags.Settings.THEME_PRESETS),
        maxItemsInEachRow = ThemePresetConstants.COLUMNS,
        horizontalArrangement = Arrangement.spacedBy(ThemePresetConstants.TILE_SPACING),
        verticalArrangement = Arrangement.spacedBy(ThemePresetConstants.TILE_SPACING),
    ) {
        choices.forEach { preset ->
            Box(modifier = Modifier.weight(ThemePresetConstants.WEIGHT)) {
                KodakikoTheme(appTheme = appTheme, pureBlack = pureBlack, dynamicColor = false, preset = preset) {
                    ThemeTile(
                        label = stringResource(ThemePresets.label(preset)),
                        selected = preset == selected,
                        tag = listItem(TestTags.Settings.THEME_PRESET, preset.ordinal),
                        onClick = { onSelect(preset) },
                    )
                }
            }
        }
        repeat((ThemePresetConstants.COLUMNS - choices.size % ThemePresetConstants.COLUMNS) % ThemePresetConstants.COLUMNS) {
            Spacer(modifier = Modifier.weight(ThemePresetConstants.WEIGHT))
        }
    }
}

@Composable
private fun ThemeTile(label: String, selected: Boolean, tag: String, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().testTag(tag).semantics {
            role = Role.RadioButton
            this.selected = selected
        },
        shape = RoundedCornerShape(ThemePresetConstants.TILE_CORNER),
        color = colors.surfaceContainerLow,
        border = if (selected) {
            BorderStroke(ThemePresetConstants.BORDER_SELECTED, colors.primary)
        } else {
            BorderStroke(ThemePresetConstants.BORDER, colors.outlineVariant)
        },
    ) {
        Column(modifier = Modifier.padding(ThemePresetConstants.TILE_PADDING), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(
                modifier = Modifier.fillMaxWidth().height(ThemePresetConstants.SWATCH_HEIGHT).clip(RoundedCornerShape(ThemePresetConstants.SWATCH_CORNER)),
                horizontalArrangement = Arrangement.spacedBy(ThemePresetConstants.SWATCH_SPACING),
            ) {
                Swatch(colors.primary)
                Swatch(colors.secondaryContainer)
                Swatch(colors.background)
            }
            Text(
                text = label,
                modifier = Modifier.padding(top = ThemePresetConstants.TILE_PADDING),
                style = MaterialTheme.typography.labelMedium,
                color = colors.onSurface,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun RowScope.Swatch(color: Color) {
    Box(modifier = Modifier.weight(ThemePresetConstants.WEIGHT).height(ThemePresetConstants.SWATCH_HEIGHT).background(color))
}
