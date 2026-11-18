// Adapted from https://github.com/mihonapp/mihon
// Copyright 2015 Javier Tomás, 2024 Mihon Open Source Project, Apache-2.0
package dev.kodakiko.ui.core.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.paddingFromBaseline
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import dev.kodakiko.ui.core.components.ActionButton
import dev.kodakiko.ui.core.components.material.padding
import dev.kodakiko.ui.core.util.optionalTestTag
import dev.kodakiko.ui.core.util.secondaryItemAlpha

@Immutable
data class EmptyScreenAction(
    val title: String,
    val icon: ImageVector,
    val onClick: () -> Unit,
    val testTag: String? = null,
)

@Composable
fun EmptyScreen(
    message: String,
    modifier: Modifier = Modifier,
    actions: List<EmptyScreenAction>? = null,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = ScreenConstants.EMPTY_SCREEN_HORIZONTAL_PADDING),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = message,
            modifier = Modifier
                .paddingFromBaseline(top = ScreenConstants.EMPTY_SCREEN_MESSAGE_BASELINE)
                .secondaryItemAlpha(),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )

        if (!actions.isNullOrEmpty()) {
            Row(
                modifier = Modifier
                    .padding(top = ScreenConstants.EMPTY_SCREEN_ACTIONS_TOP_PADDING),
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
            ) {
                actions.forEach {
                    ActionButton(
                        modifier = Modifier
                            .weight(1f)
                            .optionalTestTag(it.testTag),
                        title = it.title,
                        icon = it.icon,
                        onClick = it.onClick,
                    )
                }
            }
        }
    }
}
