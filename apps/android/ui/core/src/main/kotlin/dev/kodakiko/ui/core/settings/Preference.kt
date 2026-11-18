// Adapted from https://github.com/mihonapp/mihon
// Copyright 2015 Javier Tomás, 2024 Mihon Open Source Project, Apache-2.0
package dev.kodakiko.ui.core.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.vector.ImageVector

@Immutable
sealed class Preference {
    abstract val title: String
    abstract val visible: Boolean

    @Immutable
    sealed class PreferenceItem : Preference() {
        abstract val subtitle: String?
        abstract val icon: ImageVector?
        abstract val testTag: String?
        abstract val enabled: Boolean

        data class TextPreference(
            override val title: String,
            override val subtitle: String? = null,
            override val icon: ImageVector? = null,
            override val testTag: String? = null,
            override val enabled: Boolean = true,
            override val visible: Boolean = true,
            val danger: Boolean = false,
            val widget: @Composable (() -> Unit)? = null,
            val onClick: (() -> Unit)? = null,
        ) : PreferenceItem()

        data class SwitchPreference(
            val value: Boolean,
            override val title: String,
            override val subtitle: String? = null,
            override val icon: ImageVector? = null,
            override val testTag: String? = null,
            override val enabled: Boolean = true,
            override val visible: Boolean = true,
            val onValueChanged: (Boolean) -> Unit,
        ) : PreferenceItem()

        data class SliderPreference(
            val value: Int,
            override val title: String,
            override val subtitle: String? = null,
            val valueText: (Int) -> String = { it.toString() },
            val valueRange: IntProgression = 0..1,
            val steps: Int = with(valueRange) { ((last - first) / step) - 1 },
            override val testTag: String? = null,
            override val enabled: Boolean = true,
            override val visible: Boolean = true,
            val onValueChanged: (Int) -> Unit,
        ) : PreferenceItem() {
            override val icon: ImageVector? = null
        }

        data class ListPreference<T>(
            val value: T,
            val entries: Map<T, String>,
            override val title: String,
            override val subtitle: String? = null,
            override val icon: ImageVector? = null,
            override val testTag: String? = null,
            override val enabled: Boolean = true,
            override val visible: Boolean = true,
            val onValueChanged: (T) -> Unit,
        ) : PreferenceItem() {
            internal val shownSubtitle: String? get() = subtitle ?: entries[value]
        }

        data class MultiSelectListPreference<T>(
            val values: Set<T>,
            val entries: Map<T, String>,
            override val title: String,
            override val subtitle: String? = null,
            override val icon: ImageVector? = null,
            override val testTag: String? = null,
            override val enabled: Boolean = true,
            override val visible: Boolean = true,
            val onValuesChanged: (Set<T>) -> Unit,
        ) : PreferenceItem() {
            internal val chosenLabels: String
                get() = entries.filterKeys { it in values }.values.joinToString(SettingsConstants.VALUES_SEPARATOR)
        }

        data class InfoPreference(
            override val title: String,
            override val testTag: String? = null,
            override val visible: Boolean = true,
        ) : PreferenceItem() {
            override val subtitle: String? = null
            override val icon: ImageVector? = null
            override val enabled: Boolean = true
        }

        data class CustomPreference(
            override val title: String,
            override val testTag: String? = null,
            override val visible: Boolean = true,
            val content: @Composable () -> Unit,
        ) : PreferenceItem() {
            override val subtitle: String? = null
            override val icon: ImageVector? = null
            override val enabled: Boolean = true
        }
    }

    data class PreferenceGroup(
        override val title: String,
        override val visible: Boolean = true,
        val preferenceItems: List<PreferenceItem>,
    ) : Preference()
}
