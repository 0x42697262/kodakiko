// Adapted from https://github.com/mihonapp/mihon
// Copyright 2015 Javier Tomás, 2024 Mihon Open Source Project, Apache-2.0
package dev.kodakiko.ui.core.settings.widget

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.StartOffsetType
import androidx.compose.animation.core.repeatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import dev.kodakiko.ui.core.components.material.DISABLED_ALPHA
import dev.kodakiko.ui.core.settings.LocalPreferenceHighlighted
import dev.kodakiko.ui.core.settings.SettingsConstants
import dev.kodakiko.ui.core.settings.preferenceHighlighted
import kotlinx.coroutines.delay

@Composable
internal fun BasePreferenceWidget(
    modifier: Modifier = Modifier,
    title: String? = null,
    titleColor: Color = Color.Unspecified,
    subcomponent: @Composable (ColumnScope.() -> Unit)? = null,
    icon: @Composable (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    widget: @Composable (() -> Unit)? = null,
    enabled: Boolean = true,
    clickModifier: Modifier? = null,
) {
    val contentAlpha = if (enabled) 1f else DISABLED_ALPHA
    val highlighted = LocalPreferenceHighlighted.current
    val interaction = clickModifier ?: if (onClick != null) {
        Modifier.clickable(enabled = enabled, onClick = onClick)
    } else {
        Modifier
    }
    Row(
        modifier = modifier
            .highlight(highlighted)
            .then(interaction)
            .sizeIn(minHeight = SettingsConstants.PREFERENCE_MIN_HEIGHT)
            .fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Box(
                modifier = Modifier
                    .padding(start = SettingsConstants.PREFS_HORIZONTAL_PADDING, end = SettingsConstants.ICON_END_PADDING)
                    .alpha(contentAlpha),
                content = { icon() },
            )
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(vertical = SettingsConstants.PREFS_VERTICAL_PADDING)
                .alpha(contentAlpha),
        ) {
            if (!title.isNullOrBlank()) {
                Text(
                    modifier = Modifier.padding(horizontal = SettingsConstants.PREFS_HORIZONTAL_PADDING),
                    text = title,
                    color = titleColor,
                    overflow = TextOverflow.Ellipsis,
                    maxLines = SettingsConstants.TITLE_MAX_LINES,
                    style = MaterialTheme.typography.titleLarge,
                    fontSize = SettingsConstants.TITLE_FONT_SIZE,
                )
            }
            subcomponent?.invoke(this)
        }
        if (widget != null) {
            Box(
                modifier = Modifier.padding(end = SettingsConstants.PREFS_HORIZONTAL_PADDING),
                content = { widget() },
            )
        }
    }
}

@Composable
internal fun Modifier.highlight(highlighted: Boolean, mergeDescendants: Boolean = true): Modifier {
    var highlightFlag by remember { mutableStateOf(false) }
    LaunchedEffect(highlighted) {
        if (highlighted) {
            highlightFlag = true
            delay(SettingsConstants.HIGHLIGHT_DURATION)
        }
        highlightFlag = false
    }
    val highlight by animateColorAsState(
        targetValue = if (highlightFlag) {
            MaterialTheme.colorScheme.surfaceTint.copy(alpha = SettingsConstants.HIGHLIGHT_ALPHA)
        } else {
            Color.Transparent
        },
        animationSpec = if (highlightFlag) {
            repeatable(
                iterations = SettingsConstants.HIGHLIGHT_ITERATIONS,
                animation = tween(durationMillis = SettingsConstants.HIGHLIGHT_STEP_MILLIS),
                repeatMode = RepeatMode.Reverse,
                initialStartOffset = StartOffset(
                    offsetMillis = SettingsConstants.HIGHLIGHT_START_OFFSET_MILLIS,
                    offsetType = StartOffsetType.Delay,
                ),
            )
        } else {
            tween(SettingsConstants.HIGHLIGHT_STEP_MILLIS)
        },
        label = SettingsConstants.HIGHLIGHT_LABEL,
    )
    return this
        .semantics(mergeDescendants = mergeDescendants) { preferenceHighlighted = highlightFlag }
        .background(color = highlight)
}
