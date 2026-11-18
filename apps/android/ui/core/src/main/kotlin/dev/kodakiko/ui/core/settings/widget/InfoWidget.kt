// Adapted from https://github.com/mihonapp/mihon
// Copyright 2015 Javier Tomás, 2024 Mihon Open Source Project, Apache-2.0
package dev.kodakiko.ui.core.settings.widget

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.kodakiko.ui.core.components.material.padding
import dev.kodakiko.ui.core.icons.KodakikoIcons
import dev.kodakiko.ui.core.settings.LocalPreferenceHighlighted
import dev.kodakiko.ui.core.settings.SettingsConstants
import dev.kodakiko.ui.core.util.secondaryItemAlpha

@Composable
internal fun InfoWidget(text: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .highlight(LocalPreferenceHighlighted.current)
            .fillMaxWidth()
            .padding(
                horizontal = SettingsConstants.PREFS_HORIZONTAL_PADDING,
                vertical = MaterialTheme.padding.medium,
            )
            .secondaryItemAlpha(),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.medium),
    ) {
        Icon(
            imageVector = KodakikoIcons.Info,
            contentDescription = null,
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
