// Adapted from https://github.com/mihonapp/mihon
// Copyright 2015 Javier Tomás, 2024 Mihon Open Source Project, Apache-2.0
package dev.kodakiko.ui.core.settings

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.kodakiko.ui.core.components.AppBar
import dev.kodakiko.ui.core.components.material.Scaffold

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PreferenceScaffold(
    title: String,
    items: List<Preference>,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
    onBackPressed: (() -> Unit)? = null,
    highlightKey: String? = null,
    navigateUpTestTag: String? = null,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            AppBar(
                title = title,
                navigateUp = onBackPressed,
                navigateUpTestTag = navigateUpTestTag,
                actions = actions,
                scrollBehavior = it,
            )
        },
        content = { contentPadding ->
            PreferenceScreen(
                items = items,
                contentPadding = contentPadding,
                highlightKey = highlightKey,
            )
        },
    )
}
