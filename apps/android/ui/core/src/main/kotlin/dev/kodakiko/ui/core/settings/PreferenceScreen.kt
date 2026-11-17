// Adapted from https://github.com/mihonapp/mihon
// Copyright 2015 Javier Tomás, 2024 Mihon Open Source Project, Apache-2.0
package dev.kodakiko.ui.core.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastForEachIndexed
import dev.kodakiko.ui.core.settings.widget.PreferenceGroupHeader
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

@Composable
fun PreferenceScreen(
    items: List<Preference>,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    highlightKey: String? = null,
) {
    val state = rememberScrollState()
    val target = remember { ScrollTarget() }
    var activeKey by remember(highlightKey) { mutableStateOf(highlightKey) }
    LaunchedEffect(highlightKey) {
        val key = highlightKey ?: return@LaunchedEffect
        if (items.findHighlightedIndex(key) >= 0) {
            delay(SettingsConstants.HIGHLIGHT_SCROLL_DELAY)
            target.top?.let { state.animateScrollTo(it) }
        }
        delay(SettingsConstants.HIGHLIGHT_DURATION)
        activeKey = null
    }

    Column(
        modifier = modifier
            .verticalScroll(state)
            .padding(contentPadding),
    ) {
        items.fastForEachIndexed { i, preference ->
            if (!preference.visible) return@fastForEachIndexed
            when (preference) {
                is Preference.PreferenceGroup -> {
                    Column(modifier = Modifier.scrollTarget(preference.title == highlightKey, target)) {
                        PreferenceGroupHeader(
                            title = preference.title,
                            highlighted = preference.title == activeKey,
                        )
                    }
                    preference.preferenceItems.filter { it.visible }.forEach { item ->
                        Box(modifier = Modifier.scrollTarget(item.title == highlightKey, target)) {
                            PreferenceItem(
                                item = item,
                                highlightKey = activeKey,
                            )
                        }
                    }
                    if (i < items.lastIndex) {
                        Spacer(modifier = Modifier.height(SettingsConstants.GROUP_SPACER_HEIGHT))
                    }
                }

                is Preference.PreferenceItem -> Box(modifier = Modifier.scrollTarget(preference.title == highlightKey, target)) {
                    PreferenceItem(
                        item = preference,
                        highlightKey = activeKey,
                    )
                }
            }
        }
    }
}

private class ScrollTarget {
    var top: Int? = null
}

private fun Modifier.scrollTarget(isTarget: Boolean, target: ScrollTarget): Modifier =
    if (isTarget) onGloballyPositioned { target.top = it.positionInParent().y.roundToInt() } else this

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
