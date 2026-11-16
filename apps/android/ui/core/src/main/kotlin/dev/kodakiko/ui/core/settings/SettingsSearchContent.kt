// Adapted from https://github.com/mihonapp/mihon
// Copyright 2015 Javier Tomás, 2024 Mihon Open Source Project, Apache-2.0
package dev.kodakiko.ui.core.settings

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.paddingFromBaseline
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import dev.kodakiko.ui.core.R
import dev.kodakiko.ui.core.TestTags
import dev.kodakiko.ui.core.components.UpIcon
import dev.kodakiko.ui.core.components.material.Scaffold
import dev.kodakiko.ui.core.icons.KodakikoIcons
import dev.kodakiko.ui.core.screens.EmptyScreen
import dev.kodakiko.ui.core.util.runOnEnterKeyPressed

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSearchContent(
    sections: List<SettingsSection>,
    onResultClick: (SettingsIndexEntry) -> Unit,
    navigateUp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val softKeyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val focusRequester = remember { FocusRequester() }
    val listState = rememberLazyListState()

    DisposableEffect(Unit) {
        onDispose {
            softKeyboardController?.hide()
        }
    }

    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress) {
            focusManager.clearFocus()
        }
    }

    val textFieldState = rememberTextFieldState()
    Scaffold(
        modifier = modifier,
        topBar = {
            Column {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = navigateUp) {
                            UpIcon()
                        }
                    },
                    title = {
                        LaunchedEffect(focusRequester) {
                            focusRequester.requestFocus()
                        }
                        BasicTextField(
                            state = textFieldState,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag(TestTags.Settings.SEARCH_FIELD)
                                .focusRequester(focusRequester)
                                .runOnEnterKeyPressed(action = focusManager::clearFocus),
                            textStyle = MaterialTheme.typography.bodyLarge
                                .copy(color = MaterialTheme.colorScheme.onSurface),
                            lineLimits = TextFieldLineLimits.SingleLine,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            onKeyboardAction = { focusManager.clearFocus() },
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            decorator = {
                                if (textFieldState.text.isEmpty()) {
                                    Text(
                                        text = stringResource(R.string.prefs_search),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        style = MaterialTheme.typography.bodyLarge,
                                    )
                                }
                                it()
                            },
                        )
                    },
                    actions = {
                        if (textFieldState.text.isNotEmpty()) {
                            IconButton(onClick = { textFieldState.clearText() }) {
                                Icon(
                                    imageVector = KodakikoIcons.Close,
                                    contentDescription = stringResource(R.string.settings_clear_action),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    },
                )
                HorizontalDivider()
            }
        },
    ) { contentPadding ->
        val query = textFieldState.text.toString()
        val results = remember(sections, query) {
            if (searchWords(query).isEmpty()) null else searchSettings(sections, query)
        }
        SearchResult(
            result = results,
            listState = listState,
            contentPadding = contentPadding,
            onItemClick = onResultClick,
        )
    }
}

@Composable
private fun SearchResult(
    result: List<SettingsIndexEntry>?,
    listState: LazyListState,
    contentPadding: PaddingValues,
    onItemClick: (SettingsIndexEntry) -> Unit,
) {
    Crossfade(
        targetState = result,
        label = SettingsConstants.SEARCH_RESULTS_LABEL,
    ) {
        when {
            it == null -> {}
            it.isEmpty() -> {
                EmptyScreen(stringResource(R.string.prefs_no_results), modifier = Modifier.padding(contentPadding))
            }
            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    state = listState,
                    contentPadding = contentPadding,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    itemsIndexed(items = it) { position, item ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag(TestTags.listItem(TestTags.Settings.RESULT, position))
                                .clickable { onItemClick(item) }
                                .padding(
                                    horizontal = SettingsConstants.SEARCH_RESULT_HORIZONTAL_PADDING,
                                    vertical = SettingsConstants.SEARCH_RESULT_VERTICAL_PADDING,
                                ),
                        ) {
                            Text(
                                text = item.title,
                                overflow = TextOverflow.Ellipsis,
                                maxLines = 1,
                                fontWeight = FontWeight.Normal,
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                text = item.breadcrumbs,
                                modifier = Modifier.paddingFromBaseline(top = SettingsConstants.SEARCH_BREADCRUMB_BASELINE),
                                maxLines = 1,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }
    }
}
