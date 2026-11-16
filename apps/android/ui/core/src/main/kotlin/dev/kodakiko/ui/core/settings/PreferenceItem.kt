// Adapted from https://github.com/mihonapp/mihon
// Copyright 2015 Javier Tomás, 2024 Mihon Open Source Project, Apache-2.0
package dev.kodakiko.ui.core.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.structuralEqualityPolicy
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import dev.kodakiko.ui.core.R
import dev.kodakiko.ui.core.components.BaseSliderItem
import dev.kodakiko.ui.core.settings.widget.InfoWidget
import dev.kodakiko.ui.core.settings.widget.ListPreferenceWidget
import dev.kodakiko.ui.core.settings.widget.MultiSelectListPreferenceWidget
import dev.kodakiko.ui.core.settings.widget.SwitchPreferenceWidget
import dev.kodakiko.ui.core.settings.widget.TextPreferenceWidget
import dev.kodakiko.ui.core.settings.widget.highlight
import dev.kodakiko.ui.core.settings.widget.switchTag
import dev.kodakiko.ui.core.util.optionalTestTag

val LocalPreferenceHighlighted: ProvidableCompositionLocal<Boolean> = compositionLocalOf(structuralEqualityPolicy()) { false }

val PreferenceHighlighted: SemanticsPropertyKey<Boolean> = SemanticsPropertyKey(SettingsConstants.HIGHLIGHTED_PROPERTY)

var SemanticsPropertyReceiver.preferenceHighlighted: Boolean by PreferenceHighlighted

@Composable
internal fun PreferenceItem(
    item: Preference.PreferenceItem,
    highlightKey: String?,
) {
    if (!item.visible) return
    CompositionLocalProvider(LocalPreferenceHighlighted provides (item.title == highlightKey)) {
        PreferenceItemContent(item)
    }
}

@Composable
private fun PreferenceItemContent(item: Preference.PreferenceItem) {
    val tagged = Modifier.optionalTestTag(item.testTag)
    when (item) {
        is Preference.PreferenceItem.SwitchPreference -> {
            SwitchPreferenceWidget(
                modifier = tagged,
                title = item.title,
                subtitle = item.subtitle,
                icon = item.icon,
                checked = item.value,
                enabled = item.enabled,
                switchTestTag = item.testTag?.let(::switchTag),
                onCheckedChanged = item.onValueChanged,
            )
        }
        is Preference.PreferenceItem.SliderPreference -> {
            Box(modifier = tagged.highlight(LocalPreferenceHighlighted.current, mergeDescendants = false)) {
                BaseSliderItem(
                    value = item.value,
                    valueRange = item.valueRange,
                    steps = item.steps,
                    title = item.title,
                    subtitle = item.subtitle,
                    valueString = item.valueText(item.value),
                    onChange = item.onValueChanged,
                    titleStyle = MaterialTheme.typography.titleLarge.copy(fontSize = SettingsConstants.TITLE_FONT_SIZE),
                    modifier = Modifier.padding(
                        horizontal = SettingsConstants.PREFS_HORIZONTAL_PADDING,
                        vertical = SettingsConstants.PREFS_VERTICAL_PADDING,
                    ),
                )
            }
        }
        is Preference.PreferenceItem.ListPreference<*> -> ListItem(item, tagged)
        is Preference.PreferenceItem.MultiSelectListPreference<*> -> MultiSelectItem(item, tagged)
        is Preference.PreferenceItem.TextPreference -> {
            TextPreferenceWidget(
                modifier = tagged,
                title = item.title,
                subtitle = item.subtitle,
                icon = item.icon,
                iconTint = if (item.danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                titleColor = if (item.danger) MaterialTheme.colorScheme.error else Color.Unspecified,
                widget = item.widget,
                enabled = item.enabled,
                onPreferenceClick = item.onClick,
            )
        }
        is Preference.PreferenceItem.InfoPreference -> InfoWidget(text = item.title, modifier = tagged)
        is Preference.PreferenceItem.CustomPreference -> Box(modifier = tagged) { item.content() }
    }
}

@Composable
private fun <T> ListItem(item: Preference.PreferenceItem.ListPreference<T>, modifier: Modifier) {
    ListPreferenceWidget(
        modifier = modifier,
        value = item.value,
        title = item.title,
        subtitle = item.shownSubtitle,
        icon = item.icon,
        entries = item.entries,
        enabled = item.enabled,
        testTag = item.testTag,
        onValueChange = item.onValueChanged,
    )
}

@Composable
private fun <T> MultiSelectItem(item: Preference.PreferenceItem.MultiSelectListPreference<T>, modifier: Modifier) {
    MultiSelectListPreferenceWidget(
        modifier = modifier,
        values = item.values,
        title = item.title,
        subtitle = item.subtitle ?: item.chosenLabels.ifBlank { stringResource(R.string.prefs_none) },
        icon = item.icon,
        entries = item.entries,
        enabled = item.enabled,
        testTag = item.testTag,
        onValuesChange = item.onValuesChanged,
    )
}
