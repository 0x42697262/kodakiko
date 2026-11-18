// Adapted from https://github.com/mihonapp/mihon
// Copyright 2015 Javier Tomás, 2024 Mihon Open Source Project, Apache-2.0
@file:OptIn(ExperimentalMaterial3Api::class)

package dev.kodakiko.ui.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import dev.kodakiko.ui.core.R
import dev.kodakiko.ui.core.TestTags
import dev.kodakiko.ui.core.components.AppBar
import dev.kodakiko.ui.core.components.AppBarActions
import dev.kodakiko.ui.core.components.material.Scaffold
import dev.kodakiko.ui.core.icons.KodakikoIcons
import dev.kodakiko.ui.core.settings.widget.TextPreferenceWidget

@Composable
fun SettingsContent(state: SettingsState, onAction: (SettingsAction) -> Unit, modifier: Modifier = Modifier) {
    Scaffold(
        modifier = modifier.testTag(TestTags.Settings.SCREEN),
        topBar = { scrollBehavior ->
            AppBar(
                title = stringResource(R.string.settings_title),
                navigateUp = { onAction(SettingsAction.Back) },
                navigateUpTestTag = TestTags.Settings.BACK,
                actions = {
                    AppBarActions(
                        listOf(
                            AppBar.Action(
                                title = stringResource(R.string.prefs_search),
                                icon = KodakikoIcons.Search,
                                testTag = TestTags.Settings.SEARCH,
                                onClick = { onAction(SettingsAction.Search) },
                            ),
                        ),
                    )
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(contentPadding),
        ) {
            state.rows.forEachIndexed { position, row ->
                key(row) {
                    if (position > 0 && groupOf(state.rows[position - 1]) != groupOf(row)) HorizontalDivider()
                    SettingsRowItem(row, onAction)
                }
            }
        }
    }
    if (state.confirmingSignOut) {
        ConfirmSheet(
            sheetTag = TestTags.Settings.SIGN_OUT_SHEET,
            message = stringResource(R.string.settings_sign_out_confirm),
            confirmText = stringResource(R.string.settings_sign_out),
            confirmTag = TestTags.Settings.SIGN_OUT_CONFIRM,
            onConfirm = { onAction(SettingsAction.ConfirmSignOut) },
            onDismiss = { onAction(SettingsAction.DismissSignOut) },
            danger = true,
            cancelText = stringResource(R.string.settings_cancel),
            cancelTag = TestTags.Settings.CANCEL,
        )
    }
}

@Composable
private fun SettingsRowItem(row: SettingsRow, onAction: (SettingsAction) -> Unit) {
    val danger = row == SettingsRow.SIGN_OUT
    TextPreferenceWidget(
        modifier = Modifier.testTag(row.testTag),
        title = stringResource(row.title),
        subtitle = row.summary?.let { stringResource(it) },
        icon = iconOf(row),
        iconTint = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        titleColor = if (danger) MaterialTheme.colorScheme.error else Color.Unspecified,
        onPreferenceClick = { onAction(SettingsAction.Open(row)) },
    )
}

private fun groupOf(row: SettingsRow): Int = when (row) {
    SettingsRow.APPEARANCE, SettingsRow.GALLERY, SettingsRow.TAGS, SettingsRow.CAPTURE -> SettingsGroups.DISPLAY
    SettingsRow.SYNC, SettingsRow.STORAGE, SettingsRow.SECURITY -> SettingsGroups.DATA
    SettingsRow.ACCOUNT, SettingsRow.LINKS, SettingsRow.ADMIN -> SettingsGroups.ACCOUNT
    SettingsRow.ADVANCED, SettingsRow.ABOUT -> SettingsGroups.APP
    SettingsRow.LOCK_NOW, SettingsRow.SIGN_OUT -> SettingsGroups.SESSION
}

@Composable
private fun iconOf(row: SettingsRow): ImageVector = when (row) {
    SettingsRow.APPEARANCE -> KodakikoIcons.Palette
    SettingsRow.GALLERY -> KodakikoIcons.PhotoLibrary
    SettingsRow.TAGS -> KodakikoIcons.Label
    SettingsRow.CAPTURE -> KodakikoIcons.PhotoCamera
    SettingsRow.SYNC -> KodakikoIcons.Sync
    SettingsRow.STORAGE -> KodakikoIcons.Storage
    SettingsRow.SECURITY -> KodakikoIcons.Security
    SettingsRow.ACCOUNT -> KodakikoIcons.AccountCircle
    SettingsRow.LINKS -> KodakikoIcons.Link
    SettingsRow.ADMIN -> KodakikoIcons.People
    SettingsRow.ADVANCED -> KodakikoIcons.Code
    SettingsRow.ABOUT -> KodakikoIcons.Info
    SettingsRow.LOCK_NOW -> KodakikoIcons.Lock
    SettingsRow.SIGN_OUT -> KodakikoIcons.Logout
}
