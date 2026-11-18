// Adapted from https://github.com/mihonapp/mihon
// Copyright 2015 Javier Tomás, 2024 Mihon Open Source Project, Apache-2.0
@file:OptIn(ExperimentalMaterial3Api::class)

package dev.kodakiko.ui.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import dev.kodakiko.ui.core.components.AppBar
import dev.kodakiko.ui.core.settings.Preference
import dev.kodakiko.ui.core.settings.PreferenceScreen

@Composable
internal fun PinnedPreferencePage(
    title: String,
    items: List<Preference>,
    screenTag: String,
    onBack: () -> Unit,
    backTag: String,
    highlightKey: String? = null,
    snackbarHost: @Composable () -> Unit = {},
) {
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground) {
        PinnedPreferenceBox(title, items, screenTag, onBack, backTag, highlightKey, snackbarHost)
    }
}

@Composable
private fun PinnedPreferenceBox(
    title: String,
    items: List<Preference>,
    screenTag: String,
    onBack: () -> Unit,
    backTag: String,
    highlightKey: String?,
    snackbarHost: @Composable () -> Unit,
) {
    val scroll = rememberScrollState()
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .testTag(screenTag),
    ) {
        PreferenceScreen(
            items = items,
            modifier = Modifier.fillMaxSize(),
            contentPadding = WindowInsets.navigationBars.asPaddingValues(),
            highlightKey = highlightKey,
            state = scroll,
            header = {
                AppBar(
                    title = title,
                    navigateUp = onBack,
                    navigateUpTestTag = backTag,
                    modifier = Modifier
                        .offset { IntOffset(0, scroll.value) }
                        .background(MaterialTheme.colorScheme.background),
                )
            },
        )
        Box(modifier = Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.navigationBars)) { snackbarHost() }
    }
}
