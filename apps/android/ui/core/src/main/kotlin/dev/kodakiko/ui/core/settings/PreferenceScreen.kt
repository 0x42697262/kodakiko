// Adapted from https://github.com/mihonapp/mihon
// Copyright 2015 Javier Tomás, 2024 Mihon Open Source Project, Apache-2.0
package dev.kodakiko.ui.core.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastForEachIndexed
import dev.kodakiko.ui.core.settings.widget.PreferenceGroupHeader
import kotlinx.coroutines.delay

@Composable
fun PreferenceScreen(
    items: List<Preference>,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    highlightKey: String? = null,
) {
    val state = rememberLazyListState()
    var activeKey by remember(highlightKey) { mutableStateOf(highlightKey) }
    LaunchedEffect(highlightKey) {
        val key = highlightKey ?: return@LaunchedEffect
        val index = items.findHighlightedIndex(key)
        if (index >= 0) {
            delay(SettingsConstants.HIGHLIGHT_SCROLL_DELAY)
            state.animateScrollToItem(index)
        }
        delay(SettingsConstants.HIGHLIGHT_DURATION)
        activeKey = null
    }

    LazyColumn(
        modifier = modifier,
        state = state,
        contentPadding = contentPadding,
    ) {
        items.fastForEachIndexed { i, preference ->
            if (!preference.visible) return@fastForEachIndexed
            when (preference) {
                is Preference.PreferenceGroup -> {
                    item {
                        Column {
                            PreferenceGroupHeader(
                                title = preference.title,
                                highlighted = preference.title == activeKey,
                            )
                        }
                    }
                    items(preference.preferenceItems.filter { it.visible }) { item ->
                        PreferenceItem(
                            item = item,
                            highlightKey = activeKey,
                        )
                    }
                    item {
                        if (i < items.lastIndex) {
                            Spacer(modifier = Modifier.height(SettingsConstants.GROUP_SPACER_HEIGHT))
                        }
                    }
                }

                is Preference.PreferenceItem -> item {
                    PreferenceItem(
                        item = preference,
                        highlightKey = activeKey,
                    )
                }
            }
        }
    }
}

private fun List<Preference>.findHighlightedIndex(highlightKey: String): Int = filter { it.visible }
    .flatMap {
        if (it is Preference.PreferenceGroup) {
            buildList<String?> {
                add(it.title)
                addAll(it.preferenceItems.filter { groupItem -> groupItem.visible }.map { groupItem -> groupItem.title })
                add(null)
            }
        } else {
            listOf(it.title)
        }
    }
    .indexOfFirst { it == highlightKey }
