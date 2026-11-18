// Adapted from https://github.com/mihonapp/mihon
// Copyright 2015 Javier Tomás, 2024 Mihon Open Source Project, Apache-2.0
package dev.kodakiko.ui.core.components.material

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.offset
import dev.kodakiko.ui.core.components.Pill
import dev.kodakiko.ui.core.components.TabBadgeStyle

@Composable
fun TabText(text: String, badgeCount: Int? = null) {
    val pillAlpha = if (isSystemInDarkTheme()) TabBadgeStyle.ALPHA_DARK else TabBadgeStyle.ALPHA_LIGHT

    Layout(
        content = {
            Text(
                text = text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (badgeCount != null) {
                Pill(
                    text = badgeCount.toString(),
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = pillAlpha),
                    style = LocalTextStyle.current.merge(fontSize = TabBadgeStyle.FONT_SIZE),
                )
            }
        },
    ) { measurables, constraints ->
        val looseConstraints = constraints.copy(minWidth = 0, minHeight = 0)
        val badge = measurables.getOrNull(1)?.measure(looseConstraints)
        val badgeWidth = badge?.width ?: 0
        val label = measurables[0].measure(looseConstraints.offset(horizontal = -badgeWidth))
        val height = maxOf(label.height, badge?.height ?: 0)
        layout(label.width + badgeWidth, height) {
            label.placeRelative(0, (height - label.height) / TabBadgeStyle.CENTER_DIVISOR)
            badge?.placeRelative(label.width, (height - badge.height) / TabBadgeStyle.CENTER_DIVISOR)
        }
    }
}
