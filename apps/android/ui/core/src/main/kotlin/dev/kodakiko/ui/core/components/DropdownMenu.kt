// Adapted from https://github.com/mihonapp/mihon
// Copyright 2015 Javier Tomás, 2024 Mihon Open Source Project, Apache-2.0
package dev.kodakiko.ui.core.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.window.PopupProperties
import dev.kodakiko.ui.core.testTagsAsResourceIdRoot
import androidx.compose.material3.DropdownMenu as ComposeDropdownMenu

@Composable
fun DropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset(ComponentConstants.MENU_OFFSET_X, ComponentConstants.MENU_OFFSET_Y),
    scrollState: ScrollState = rememberScrollState(),
    properties: PopupProperties = PopupProperties(focusable = true),
    content: @Composable ColumnScope.() -> Unit,
) {
    ComposeDropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier.testTagsAsResourceIdRoot().sizeIn(minWidth = ComponentConstants.MENU_WIDTH, maxWidth = ComponentConstants.MENU_WIDTH),
        offset = offset,
        scrollState = scrollState,
        properties = properties,
        content = content,
    )
}
