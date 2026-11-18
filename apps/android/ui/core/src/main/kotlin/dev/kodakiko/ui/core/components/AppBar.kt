// Adapted from https://github.com/mihonapp/mihon
// Copyright 2015 Javier Tomás, 2024 Mihon Open Source Project, Apache-2.0
@file:OptIn(ExperimentalMaterial3Api::class)

package dev.kodakiko.ui.core.components

import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults.rememberTooltipPositionProvider
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.rememberTooltipState
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.kodakiko.ui.core.R
import dev.kodakiko.ui.core.icons.KodakikoIcons
import dev.kodakiko.ui.core.util.clearFocusOnSoftKeyboardHide
import dev.kodakiko.ui.core.util.optionalTestTag
import dev.kodakiko.ui.core.util.runOnEnterKeyPressed
import dev.kodakiko.ui.core.util.secondaryItemAlpha
import dev.kodakiko.ui.core.util.showSoftKeyboard
import kotlinx.coroutines.flow.drop

@Composable
fun AppBar(
    title: String?,

    modifier: Modifier = Modifier,
    backgroundColor: Color? = null,
    subtitle: String? = null,
    navigateUp: (() -> Unit)? = null,
    navigationIcon: ImageVector? = null,
    navigateUpTestTag: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
    actionModeCounter: Int = 0,
    onCancelActionMode: () -> Unit = {},
    actionModeActions: @Composable RowScope.() -> Unit = {},

    scrollBehavior: TopAppBarScrollBehavior? = null,
) {
    val isActionMode by remember(actionModeCounter) {
        derivedStateOf { actionModeCounter > 0 }
    }

    AppBar(
        modifier = modifier,
        backgroundColor = backgroundColor,
        titleContent = {
            if (isActionMode) {
                AppBarTitle(actionModeCounter.toString())
            } else {
                AppBarTitle(title, subtitle = subtitle)
            }
        },
        navigateUp = navigateUp,
        navigationIcon = navigationIcon,
        navigateUpTestTag = navigateUpTestTag,
        actions = {
            if (isActionMode) {
                actionModeActions()
            } else {
                actions()
            }
        },
        isActionMode = isActionMode,
        onCancelActionMode = onCancelActionMode,
        scrollBehavior = scrollBehavior,
    )
}

@Composable
fun AppBar(
    titleContent: @Composable () -> Unit,

    modifier: Modifier = Modifier,
    backgroundColor: Color? = null,
    navigateUp: (() -> Unit)? = null,
    navigationIcon: ImageVector? = null,
    navigateUpTestTag: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
    isActionMode: Boolean = false,
    onCancelActionMode: () -> Unit = {},
    cancelActionModeTestTag: String? = null,

    scrollBehavior: TopAppBarScrollBehavior? = null,
) {
    Column(
        modifier = modifier,
    ) {
        TopAppBar(
            navigationIcon = {
                if (isActionMode) {
                    IconButton(
                        onClick = onCancelActionMode,
                        modifier = Modifier.optionalTestTag(cancelActionModeTestTag),
                    ) {
                        Icon(
                            imageVector = KodakikoIcons.Close,
                            contentDescription = stringResource(R.string.gallery_cancel_selection),
                        )
                    }
                } else {
                    navigateUp?.let {
                        IconButton(
                            onClick = it,
                            modifier = Modifier.optionalTestTag(navigateUpTestTag),
                        ) {
                            UpIcon(navigationIcon = navigationIcon)
                        }
                    }
                }
            },
            title = titleContent,
            actions = actions,
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = backgroundColor ?: MaterialTheme.colorScheme.surfaceColorAtElevation(
                    elevation = if (isActionMode) ComponentConstants.ACTION_MODE_ELEVATION else 0.dp,
                ),
            ),
            scrollBehavior = scrollBehavior,
        )
    }
}

@Composable
fun AppBarTitle(
    title: String?,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
) {
    Column(modifier = modifier) {
        title?.let {
            Text(
                text = it,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        subtitle?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.basicMarquee(
                    repeatDelayMillis = ComponentConstants.SUBTITLE_MARQUEE_REPEAT_DELAY_MILLIS,
                ),
            )
        }
    }
}

@Composable
private fun ActionTooltip(
    text: String,
    content: @Composable () -> Unit,
) {
    TooltipBox(
        positionProvider = rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = {
            PlainTooltip {
                Text(text)
            }
        },
        state = rememberTooltipState(),
        focusable = false,
        content = content,
    )
}

@Composable
fun AppBarActions(
    actions: List<AppBar.AppBarAction>,
    overflowTestTag: String? = null,
) {
    var showMenu by remember { mutableStateOf(false) }

    actions.filterIsInstance<AppBar.Action>().forEach {
        ActionTooltip(text = it.title) {
            IconButton(
                onClick = it.onClick,
                enabled = it.enabled,
                modifier = Modifier.optionalTestTag(it.testTag),
            ) {
                Icon(
                    imageVector = it.icon,
                    tint = it.iconTint ?: LocalContentColor.current,
                    contentDescription = it.title,
                )
            }
        }
    }

    val overflowActions = actions.filterIsInstance<AppBar.OverflowAction>()
    if (overflowActions.isNotEmpty()) {
        val overflowDescription = stringResource(R.string.gallery_more)
        ActionTooltip(text = overflowDescription) {
            IconButton(
                onClick = { showMenu = !showMenu },
                modifier = Modifier.optionalTestTag(overflowTestTag),
            ) {
                Icon(
                    KodakikoIcons.MoreVert,
                    contentDescription = overflowDescription,
                )
            }
        }

        DropdownMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false },
        ) {
            overflowActions.forEach {
                DropdownMenuItem(
                    onClick = {
                        it.onClick()
                        showMenu = false
                    },
                    text = { Text(it.title, fontWeight = FontWeight.Normal) },
                    modifier = Modifier.optionalTestTag(it.testTag),
                )
            }
        }
    }
}

@Composable
fun SearchToolbar(
    searchQuery: String?,
    onChangeSearchQuery: (String?) -> Unit,
    modifier: Modifier = Modifier,
    titleContent: @Composable () -> Unit = {},
    navigateUp: (() -> Unit)? = null,
    searchEnabled: Boolean = true,
    placeholderText: String? = null,
    searchTestTag: String? = null,
    searchFieldTestTag: String? = null,
    onSearch: (String) -> Unit = {},
    onClickCloseSearch: () -> Unit = { onChangeSearchQuery(null) },
    actions: @Composable RowScope.() -> Unit = {},
    scrollBehavior: TopAppBarScrollBehavior? = null,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
) {
    val focusRequester = remember { FocusRequester() }
    val searchFieldState = rememberTextFieldState(searchQuery.orEmpty())
    val currentOnChangeSearchQuery by rememberUpdatedState(onChangeSearchQuery)
    val searchOpen by rememberUpdatedState(searchQuery != null)

    LaunchedEffect(searchFieldState) {
        snapshotFlow { searchFieldState.text.toString() }
            .drop(1)
            .collect { if (searchOpen) currentOnChangeSearchQuery(it) }
    }
    LaunchedEffect(searchQuery) {
        when {
            searchQuery == null -> searchFieldState.clearText()
            searchQuery != searchFieldState.text.toString() -> {
                searchFieldState.setTextAndPlaceCursorAtEnd(searchQuery)
            }
        }
    }

    AppBar(
        modifier = modifier,
        titleContent = {
            if (searchQuery == null) return@AppBar titleContent()

            val keyboardController = LocalSoftwareKeyboardController.current
            val focusManager = LocalFocusManager.current

            val searchAndClearFocus: () -> Unit = f@{
                val query = searchFieldState.text.toString()
                if (query.isBlank()) return@f
                onSearch(query)
                focusManager.clearFocus()
                keyboardController?.hide()
                focusManager.moveFocus(FocusDirection.Next)
            }

            BasicTextField(
                state = searchFieldState,
                modifier = Modifier
                    .fillMaxWidth()
                    .optionalTestTag(searchFieldTestTag)
                    .focusRequester(focusRequester)
                    .runOnEnterKeyPressed(action = searchAndClearFocus)
                    .showSoftKeyboard(remember { searchQuery.isEmpty() })
                    .clearFocusOnSoftKeyboardHide(),
                textStyle = MaterialTheme.typography.titleMedium.copy(
                    color = MaterialTheme.colorScheme.onBackground,
                    fontWeight = FontWeight.Normal,
                    fontSize = ComponentConstants.SEARCH_FIELD_FONT_SIZE,
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                onKeyboardAction = { searchAndClearFocus() },
                lineLimits = TextFieldLineLimits.SingleLine,
                cursorBrush = SolidColor(MaterialTheme.colorScheme.onBackground),
                interactionSource = interactionSource,
                decorator = TextFieldDefaults.decorator(
                    state = searchFieldState,
                    enabled = true,
                    lineLimits = TextFieldLineLimits.SingleLine,
                    outputTransformation = null,
                    interactionSource = interactionSource,
                    placeholder = {
                        Text(
                            modifier = Modifier.secondaryItemAlpha(),
                            text = (placeholderText ?: stringResource(R.string.gallery_search)),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontSize = ComponentConstants.SEARCH_FIELD_FONT_SIZE,
                                fontWeight = FontWeight.Normal,
                            ),
                        )
                    },
                    container = {},
                ),
            )
        },
        navigateUp = if (searchQuery == null) navigateUp else onClickCloseSearch,
        actions = {
            key(ComponentConstants.SEARCH_ACTION_KEY) {
                val onClick = { onChangeSearchQuery("") }

                if (!searchEnabled) {
                    Unit
                } else if (searchQuery == null) {
                    val searchDescription = stringResource(R.string.gallery_search)
                    ActionTooltip(text = searchDescription) {
                        IconButton(
                            onClick = onClick,
                            modifier = Modifier.optionalTestTag(searchTestTag),
                        ) {
                            Icon(
                                KodakikoIcons.Search,
                                contentDescription = searchDescription,
                            )
                        }
                    }
                } else if (searchQuery.isNotEmpty()) {
                    val clearDescription = stringResource(R.string.settings_clear_action)
                    ActionTooltip(text = clearDescription) {
                        IconButton(
                            onClick = {
                                searchFieldState.clearText()
                                onClick()
                                focusRequester.requestFocus()
                            },
                        ) {
                            Icon(
                                KodakikoIcons.Close,
                                contentDescription = clearDescription,
                            )
                        }
                    }
                }
            }

            key(ComponentConstants.TOOLBAR_ACTIONS_KEY) { actions() }
        },
        isActionMode = false,
        scrollBehavior = scrollBehavior,
    )
}

@Composable
fun UpIcon(
    modifier: Modifier = Modifier,
    navigationIcon: ImageVector? = null,
) {
    val icon = navigationIcon
        ?: KodakikoIcons.ArrowBack
    Icon(
        imageVector = icon,
        contentDescription = stringResource(R.string.settings_back),
        modifier = modifier,
    )
}

sealed interface AppBar {
    sealed interface AppBarAction

    data class Action(
        val title: String,
        val icon: ImageVector,
        val iconTint: Color? = null,
        val testTag: String? = null,
        val onClick: () -> Unit,
        val enabled: Boolean = true,
    ) : AppBarAction

    data class OverflowAction(
        val title: String,
        val testTag: String? = null,
        val onClick: () -> Unit,
    ) : AppBarAction
}
