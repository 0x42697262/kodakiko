@file:OptIn(ExperimentalMaterial3Api::class)

package dev.kodakiko.ui.feature.sharing

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import dev.kodakiko.js.jsTrim
import dev.kodakiko.ui.core.R
import dev.kodakiko.ui.core.TestTags
import dev.kodakiko.ui.core.TestTags.listItem
import dev.kodakiko.ui.core.components.AppBar
import dev.kodakiko.ui.core.components.FormButton
import dev.kodakiko.ui.core.components.FormButtonStyle
import dev.kodakiko.ui.core.components.ListGroupHeader
import dev.kodakiko.ui.core.components.material.PullRefresh
import dev.kodakiko.ui.core.components.material.Scaffold
import dev.kodakiko.ui.core.components.material.padding
import dev.kodakiko.ui.core.format.collectionNameText
import androidx.compose.material3.AlertDialog as MaterialAlertDialog

@Composable
fun SharedContent(state: SharedState, onAction: (SharedAction) -> Unit, modifier: Modifier = Modifier) {
    Scaffold(
        modifier = modifier.testTag(TestTags.Shared.SCREEN),
        topBar = { AppBar(title = stringResource(R.string.shared_tab), scrollBehavior = it) },
    ) { padding ->
        if (state.availability == SharedAvailability.ENABLED) {
            PullRefresh(
                refreshing = state.refreshing,
                enabled = true,
                onRefresh = { onAction(SharedAction.Refresh) },
                modifier = Modifier.fillMaxSize().padding(padding),
            ) {
                LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = MaterialTheme.padding.small)) {
                    mineSection(state.mine, onAction)
                    withMeSection(state.withMe, onAction)
                }
            }
        } else {
            Text(
                text = stringResource(if (state.availability == SharedAvailability.LOCAL_ONLY) R.string.shared_local_only else R.string.shared_flag_off),
                modifier = Modifier.padding(padding).padding(SharingDimens.SCREEN_PADDING).testTag(TestTags.Shared.FLAG_OFF),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    when (val dialog = state.dialog) {
        is SharedDialog.New -> NewJournalDialog(dialog, onAction)
        null -> Unit
    }
}

private fun LazyListScope.mineSection(mine: List<SharedJournal>?, onAction: (SharedAction) -> Unit) {
    item { ListGroupHeader(text = stringResource(R.string.shared_mine)) }
    if (mine != null && mine.isEmpty()) item { EmptyText(text = stringResource(R.string.shared_empty_mine), testTag = TestTags.Shared.EMPTY_MINE) }
    itemsIndexed(mine.orEmpty(), key = { _, journal -> journal.id }) { position, journal ->
        JournalRow(
            journal = journal,
            detail = pluralStringResource(R.plurals.shared_entry_count, journal.entryCount, journal.entryCount),
            testTag = listItem(TestTags.Shared.MINE_ROW, position),
            onClick = { onAction(SharedAction.Open(journal.id)) },
        )
    }
    item {
        FormButton(
            text = stringResource(R.string.shared_new),
            onClick = { onAction(SharedAction.New) },
            testTag = TestTags.Shared.NEW,
            style = FormButtonStyle.SECONDARY,
            modifier = Modifier.padding(horizontal = SharingDimens.SCREEN_PADDING, vertical = MaterialTheme.padding.small),
        )
    }
}

private fun LazyListScope.withMeSection(withMe: List<SharedJournal>?, onAction: (SharedAction) -> Unit) {
    item { ListGroupHeader(text = stringResource(R.string.shared_with_me)) }
    if (withMe != null && withMe.isEmpty()) item { EmptyText(text = stringResource(R.string.shared_empty_with_me), testTag = TestTags.Shared.EMPTY_WITH_ME) }
    itemsIndexed(withMe.orEmpty(), key = { _, journal -> journal.id }) { position, journal ->
        JournalRow(
            journal = journal,
            detail = stringResource(R.string.shared_from, journal.ownerUsername),
            testTag = listItem(TestTags.Shared.WITH_ME_ROW, position),
            onClick = { onAction(SharedAction.Open(journal.id)) },
        )
    }
}

@Composable
private fun EmptyText(text: String, testTag: String) {
    Text(
        text = text,
        modifier = Modifier.padding(horizontal = SharingDimens.SCREEN_PADDING, vertical = MaterialTheme.padding.small).testTag(testTag),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun JournalRow(journal: SharedJournal, detail: String, testTag: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(testTag)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = SharingDimens.ROW_PADDING, vertical = MaterialTheme.padding.medium),
    ) {
        Text(text = collectionNameText(LocalContext.current.resources, journal.name), style = MaterialTheme.typography.titleMedium)
        Text(text = detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun NewJournalDialog(dialog: SharedDialog.New, onAction: (SharedAction) -> Unit) {
    MaterialAlertDialog(
        onDismissRequest = { onAction(SharedAction.Dismiss) },
        modifier = Modifier.testTag(TestTags.Shared.NEW_SHEET),
        confirmButton = {
            TextButton(
                onClick = { onAction(SharedAction.Create) },
                enabled = !dialog.busy && jsTrim(dialog.name).isNotEmpty(),
                modifier = Modifier.testTag(TestTags.Shared.CREATE),
            ) {
                Text(text = stringResource(R.string.shared_create))
            }
        },
        dismissButton = {
            TextButton(onClick = { onAction(SharedAction.Dismiss) }, modifier = Modifier.testTag(TestTags.Shared.CANCEL)) {
                Text(text = stringResource(R.string.shared_cancel))
            }
        },
        title = { Text(text = stringResource(R.string.shared_new)) },
        text = {
            Column {
                NameField(name = dialog.name, onChange = { onAction(SharedAction.NameChanged(it)) }, onDone = { onAction(SharedAction.Create) })
                SharingError(message = dialog.error, modifier = Modifier.padding(top = MaterialTheme.padding.small))
            }
        },
    )
}

@Composable
internal fun NameField(name: String, onChange: (String) -> Unit, onDone: () -> Unit, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = name,
        onValueChange = onChange,
        label = { Text(text = stringResource(R.string.shared_name_field)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        modifier = modifier.fillMaxWidth().testTag(TestTags.Shared.NAME_FIELD),
    )
}
