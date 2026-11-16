// Adapted from https://github.com/mihonapp/mihon
// Copyright 2015 Javier Tomás, 2024 Mihon Open Source Project, Apache-2.0
package dev.kodakiko.ui.core.settings.widget

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import dev.kodakiko.ui.core.settings.SettingsConstants

@Composable
fun PreferenceGroupHeader(title: String, highlighted: Boolean = false) {
    Box(
        contentAlignment = Alignment.CenterStart,
        modifier = Modifier
            .fillMaxWidth()
            .highlight(highlighted, mergeDescendants = false)
            .padding(bottom = SettingsConstants.GROUP_HEADER_BOTTOM_PADDING, top = SettingsConstants.GROUP_HEADER_TOP_PADDING),
    ) {
        Text(
            text = title,
            color = MaterialTheme.colorScheme.secondary,
            modifier = Modifier
                .semantics { heading() }
                .padding(horizontal = SettingsConstants.PREFS_HORIZONTAL_PADDING),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
