@file:OptIn(ExperimentalMaterial3Api::class)

package dev.kodakiko.ui.feature.edit

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.kodakiko.contract.NOTE_MAX_CHARS
import dev.kodakiko.markdown.TextSelection
import dev.kodakiko.ui.core.EditTags
import dev.kodakiko.ui.core.R
import dev.kodakiko.ui.core.TestTags
import dev.kodakiko.ui.core.icons.KodakikoIcons
import dev.kodakiko.ui.core.markdown.MarkdownView
import dev.kodakiko.ui.core.testTagsAsResourceIdRoot

@Composable
internal fun NoteEditorDialog(note: String, editor: NoteEditorUi, onAction: (EditAction) -> Unit) {
    Dialog(
        onDismissRequest = { onAction(EditAction.NoteDone) },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize().testTagsAsResourceIdRoot().testTag(EditTags.NOTE_EDITOR),
            topBar = { NoteEditorTopBar(onAction) },
            bottomBar = { if (!editor.preview) NoteToolbar(onAction) },
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = EditDimens.SCREEN_PADDING, vertical = EditDimens.SMALL_SPACING),
                verticalArrangement = Arrangement.spacedBy(EditDimens.SMALL_SPACING),
            ) {
                NoteModes(editor.preview, onAction)
                if (editor.preview) NotePreview(note, onAction) else NoteField(note, editor.selection, onAction)
            }
        }
    }
}

@Composable
private fun NoteEditorTopBar(onAction: (EditAction) -> Unit) {
    TopAppBar(
        title = { Text(text = stringResource(R.string.compose_note)) },
        navigationIcon = {
            IconButton(onClick = { onAction(EditAction.NoteDone) }, modifier = Modifier.testTag(EditTags.NOTE_CLOSE)) {
                Icon(imageVector = KodakikoIcons.Close, contentDescription = stringResource(R.string.edit_note_close))
            }
        },
        actions = {
            IconButton(onClick = { onAction(EditAction.CopyNote) }, modifier = Modifier.testTag(EditTags.NOTE_COPY)) {
                Icon(imageVector = KodakikoIcons.ContentCopy, contentDescription = stringResource(R.string.edit_note_copy))
            }
            IconButton(onClick = { onAction(EditAction.NoteDone) }, modifier = Modifier.testTag(TestTags.Edit.NOTE_DONE)) {
                Icon(imageVector = EditIcons.Check, contentDescription = stringResource(R.string.edit_note_done))
            }
        },
    )
}

@Composable
private fun NoteModes(preview: Boolean, onAction: (EditAction) -> Unit) {
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        SegmentedButton(
            selected = !preview,
            onClick = { onAction(EditAction.NoteMode(preview = false)) },
            shape = SegmentedButtonDefaults.itemShape(index = 0, count = EditConstants.NOTE_MODES),
            icon = { Icon(imageVector = KodakikoIcons.Code, contentDescription = null) },
            modifier = Modifier.testTag(EditTags.NOTE_MODE_CODE),
        ) {
            Text(text = stringResource(R.string.edit_note_code))
        }
        SegmentedButton(
            selected = preview,
            onClick = { onAction(EditAction.NoteMode(preview = true)) },
            shape = SegmentedButtonDefaults.itemShape(index = 1, count = EditConstants.NOTE_MODES),
            icon = { Icon(imageVector = EditIcons.Visibility, contentDescription = null) },
            modifier = Modifier.testTag(EditTags.NOTE_MODE_PREVIEW),
        ) {
            Text(text = stringResource(R.string.edit_note_preview))
        }
    }
}

@Composable
private fun ColumnScope.NotePreview(note: String, onAction: (EditAction) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .weight(1f)
            .verticalScroll(rememberScrollState())
            .testTag(EditTags.NOTE_PREVIEW),
    ) {
        if (note.isBlank()) {
            Text(text = stringResource(R.string.edit_note_preview_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            MarkdownView(source = note, onOpenLink = { onAction(EditAction.OpenLink(it)) })
        }
    }
}

@Composable
private fun ColumnScope.NoteField(note: String, selection: TextSelection, onAction: (EditAction) -> Unit) {
    val external = TextFieldValue(note, TextRange(selection.start, selection.end))
    val local = remember { mutableStateOf(external) }
    val shown = if (local.value.text == external.text && local.value.selection == external.selection) local.value else external
    val focus = remember { FocusRequester() }
    LaunchedEffect(focus) { focus.requestFocus() }
    OutlinedTextField(
        value = shown,
        onValueChange = { next ->
            val limited = if (next.text.length > NOTE_MAX_CHARS) TextFieldValue(next.text.take(NOTE_MAX_CHARS), TextRange(NOTE_MAX_CHARS)) else next
            local.value = limited
            onAction(EditAction.NoteChanged(limited.text, TextSelection(limited.selection.start, limited.selection.end)))
        },
        placeholder = { Text(text = stringResource(R.string.compose_note_placeholder)) },
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        modifier = Modifier
            .fillMaxWidth()
            .weight(1f)
            .focusRequester(focus)
            .testTag(TestTags.Compose.NOTE),
    )
}

@Composable
private fun NoteToolbar(onAction: (EditAction) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
                .horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TOOLBAR.forEach { (action, label) ->
                IconButton(onClick = { onAction(EditAction.Toolbar(action)) }, modifier = Modifier.testTag(toolbarTag(action))) {
                    Icon(imageVector = toolbarIcon(action), contentDescription = stringResource(label))
                }
            }
        }
    }
}
