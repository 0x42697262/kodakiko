// Adapted from https://github.com/mihonapp/mihon
// Copyright 2015 Javier Tomás, 2024 Mihon Open Source Project, Apache-2.0
package dev.kodakiko.ui.core.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.kodakiko.ui.core.components.material.Scaffold
import dev.kodakiko.ui.core.components.material.padding
import dev.kodakiko.ui.core.util.optionalTestTag
import dev.kodakiko.ui.core.util.secondaryItemAlpha

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FormScreen(
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    headingText: String? = null,
    headingTestTag: String? = null,
    subtitleText: String? = null,
    subtitleTestTag: String? = null,
    topBar: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Scaffold(
        modifier = modifier,
        topBar = { topBar?.invoke() },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(top = if (topBar == null) ScreenConstants.FORM_SCREEN_TOP_PADDING else 0.dp)
                .padding(horizontal = MaterialTheme.padding.medium, vertical = MaterialTheme.padding.medium),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.medium),
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(ScreenConstants.INFO_SCREEN_ICON_SIZE),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            if (headingText != null) {
                Text(
                    text = headingText,
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { heading() }
                        .optionalTestTag(headingTestTag),
                    style = MaterialTheme.typography.headlineLarge,
                )
            }
            if (subtitleText != null) {
                Text(
                    text = subtitleText,
                    modifier = Modifier
                        .fillMaxWidth()
                        .optionalTestTag(subtitleTestTag)
                        .secondaryItemAlpha(),
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            content()
        }
    }
}
