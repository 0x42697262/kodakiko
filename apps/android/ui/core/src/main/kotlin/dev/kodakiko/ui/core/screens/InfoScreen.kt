// Adapted from https://github.com/mihonapp/mihon
// Copyright 2015 Javier Tomás, 2024 Mihon Open Source Project, Apache-2.0
package dev.kodakiko.ui.core.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.zIndex
import dev.kodakiko.ui.core.components.material.Scaffold
import dev.kodakiko.ui.core.components.material.padding
import dev.kodakiko.ui.core.util.optionalTestTag
import dev.kodakiko.ui.core.util.secondaryItemAlpha

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InfoScreen(
    icon: ImageVector,
    headingText: String,
    subtitleText: String,
    acceptText: String,
    onAcceptClick: () -> Unit,
    modifier: Modifier = Modifier,
    canAccept: Boolean = true,
    acceptTestTag: String? = null,
    rejectText: String? = null,
    onRejectClick: (() -> Unit)? = null,
    rejectTestTag: String? = null,
    headingTestTag: String? = null,
    subtitleTestTag: String? = null,
    rejectAction: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Scaffold(
        modifier = modifier,
        bottomBar = {
            val strokeWidth = Dp.Hairline
            val borderColor = MaterialTheme.colorScheme.outline
            Column(
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.background)
                    .drawBehind {
                        drawLine(
                            borderColor,
                            Offset(0f, 0f),
                            Offset(size.width, 0f),
                            strokeWidth.value,
                        )
                    }
                    .windowInsetsPadding(NavigationBarDefaults.windowInsets)
                    .padding(
                        horizontal = MaterialTheme.padding.medium,
                        vertical = MaterialTheme.padding.small,
                    ),
            ) {
                Button(
                    modifier = Modifier.fillMaxWidth().optionalTestTag(acceptTestTag),
                    enabled = canAccept,
                    onClick = onAcceptClick,
                ) {
                    Text(text = acceptText)
                }
                if (rejectText != null && onRejectClick != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall)) {
                        OutlinedButton(
                            modifier = Modifier.weight(1f).optionalTestTag(rejectTestTag),
                            onClick = onRejectClick,
                        ) {
                            Text(text = rejectText)
                        }
                        rejectAction?.invoke(this)
                    }
                }
            }
        },
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .zIndex(ScreenConstants.INFO_SCREEN_SCRIM_Z_INDEX)
                .secondaryItemAlpha()
                .background(MaterialTheme.colorScheme.background)
                .fillMaxWidth()
                .height(paddingValues.calculateTopPadding()),
        )

        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .fillMaxWidth()
                .padding(paddingValues)
                .padding(top = ScreenConstants.INFO_SCREEN_TOP_PADDING)
                .padding(horizontal = MaterialTheme.padding.medium),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier
                    .padding(bottom = MaterialTheme.padding.small)
                    .size(ScreenConstants.INFO_SCREEN_ICON_SIZE),
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = headingText,
                modifier = Modifier
                    .semantics { heading() }
                    .optionalTestTag(headingTestTag),
                style = MaterialTheme.typography.headlineLarge,
            )
            Text(
                text = subtitleText,
                modifier = Modifier
                    .optionalTestTag(subtitleTestTag)
                    .secondaryItemAlpha()
                    .padding(vertical = MaterialTheme.padding.small),
                style = MaterialTheme.typography.titleSmall,
            )

            content()
        }
    }
}
