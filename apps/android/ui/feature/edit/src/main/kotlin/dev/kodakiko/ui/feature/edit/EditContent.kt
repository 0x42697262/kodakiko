@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package dev.kodakiko.ui.feature.edit

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.zIndex
import dev.kodakiko.contract.EntryLocation
import dev.kodakiko.contract.LocationSource
import dev.kodakiko.js.jsToFixed
import dev.kodakiko.markdown.MarkdownAction
import dev.kodakiko.ui.core.R
import dev.kodakiko.ui.core.TestTags
import dev.kodakiko.ui.core.TestTags.listItem
import dev.kodakiko.ui.core.components.AppBar
import dev.kodakiko.ui.core.components.Banner
import dev.kodakiko.ui.core.components.BannerKind
import dev.kodakiko.ui.core.components.DropdownMenu
import dev.kodakiko.ui.core.format.formatDuration
import dev.kodakiko.ui.core.format.formatEntryTime
import dev.kodakiko.ui.core.icons.KodakikoIcons
import dev.kodakiko.ui.core.markdown.MarkdownView
import dev.kodakiko.ui.core.navigation.CaptureMode
import dev.kodakiko.ui.core.screens.LoadingScreen

@Composable
fun EditContent(state: EditState, onAction: (EditAction) -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .testTag(if (state.isNew) TestTags.Compose.SCREEN else TestTags.Edit.SCREEN),
    ) {
        val body = state.body
        if (body == null) {
            LoadingScreen(indicatorTestTag = TestTags.Edit.LOADING)
        } else {
            val scroll = rememberScrollState()
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scroll)
                    .windowInsetsPadding(WindowInsets.navigationBars),
            ) {
                EditTopBar(
                    isNew = state.isNew,
                    onAction = onAction,
                    modifier = Modifier
                        .zIndex(1f)
                        .offset { IntOffset(0, scroll.value) }
                        .background(MaterialTheme.colorScheme.background),
                )
                EditBodyParts(state, body, onAction)
            }
            EditSheets(state, body, onAction)
            state.noteEditor?.let { NoteEditorDialog(body.note, it, onAction) }
        }
    }
}

@Composable
private fun EditTopBar(isNew: Boolean, onAction: (EditAction) -> Unit, modifier: Modifier = Modifier) {
    AppBar(
        title = stringResource(if (isNew) R.string.compose_title else R.string.edit_title),
        modifier = modifier,
        navigateUp = { onAction(EditAction.Back) },
        navigateUpTestTag = TestTags.Compose.BACK,
        actions = {
            IconButton(onClick = { onAction(EditAction.Done) }, modifier = Modifier.testTag(TestTags.Compose.SAVE)) {
                Icon(imageVector = EditIcons.Check, contentDescription = stringResource(R.string.edit_done))
            }
            EditOverflow(onAction)
        },
    )
}

@Composable
private fun EditOverflow(onAction: (EditAction) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val danger = MaterialTheme.colorScheme.error
    Box {
        IconButton(onClick = { expanded = true }, modifier = Modifier.testTag(TestTags.Edit.MORE)) {
            Icon(imageVector = KodakikoIcons.MoreVert, contentDescription = stringResource(R.string.gallery_more))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(text = stringResource(R.string.edit_view_entry)) },
                leadingIcon = { Icon(imageVector = EditIcons.Visibility, contentDescription = null) },
                onClick = {
                    expanded = false
                    onAction(EditAction.ViewEntry)
                },
                modifier = Modifier.testTag(TestTags.Edit.VIEW),
            )
            DropdownMenuItem(
                text = { Text(text = stringResource(R.string.edit_trash), color = danger) },
                leadingIcon = { Icon(imageVector = KodakikoIcons.Delete, contentDescription = null, tint = danger) },
                onClick = {
                    expanded = false
                    onAction(EditAction.Trash)
                },
                modifier = Modifier.testTag(TestTags.Edit.TRASH),
            )
        }
    }
}

@Composable
private fun EditBodyParts(state: EditState, body: EditBody, onAction: (EditAction) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = EditDimens.SCREEN_PADDING, vertical = EditDimens.SMALL_SPACING),
        verticalArrangement = Arrangement.spacedBy(EditDimens.SECTION_SPACING),
    ) {
        if (body.pages.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(EditDimens.SMALL_SPACING)) {
                MediaPager(state.entryId, body.pages, state.page, onAction)
                PageDots(body.pages.size, state.page, onAction)
            }
        }
        ActionBar(state, body, onAction)
        state.recording?.let { RecordingTile(it, onAction) }
        if (state.voiceFailed) Banner(text = stringResource(R.string.voice_failed), kind = BannerKind.ERROR, testTag = TestTags.Voice.FAILED)
        if (body.voiceNotes.isNotEmpty()) VoiceNotes(body.voiceNotes, removable = body.itemCount > EditConstants.SINGLE_ITEM, playing = state.voicePlaying, onAction = onAction)
        WhenSection(body.time, state.timeEditor, onAction)
        body.location?.let { WhereSection(it, onAction) }
        TagsSection(body.tags, onAction)
        NoteSection(body.note, onAction)
        if (state.failed) Banner(text = stringResource(R.string.edit_failed), kind = BannerKind.ERROR, testTag = TestTags.Edit.FAILED)
    }
}

@Composable
private fun PageDots(count: Int, active: Int, onAction: (EditAction) -> Unit) {
    if (count < EditConstants.MIN_PAGES_FOR_DOTS) return
    Row(
        modifier = Modifier.fillMaxWidth().testTag(TestTags.Edit.DOTS),
        horizontalArrangement = Arrangement.spacedBy(EditDimens.DOT_GAP, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(count) { index ->
            val label = stringResource(R.string.edit_page, (index + 1).toString(), count.toString())
            val selected = index == active
            Box(
                modifier = Modifier
                    .size(EditDimens.DOT_TARGET)
                    .clip(CircleShape)
                    .selectable(selected = selected, role = Role.Tab) { onAction(EditAction.PageChanged(index)) }
                    .semantics { contentDescription = label }
                    .testTag(listItem(TestTags.Edit.DOT, index)),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(width = if (selected) EditDimens.ACTIVE_DOT else EditDimens.DOT, height = EditDimens.DOT)
                        .clip(CircleShape)
                        .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                )
            }
        }
    }
}

@Composable
private fun ActionBar(state: EditState, body: EditBody, onAction: (EditAction) -> Unit) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalArrangement = Arrangement.spacedBy(EditDimens.SMALL_SPACING),
    ) {
        BarButton(EditIcons.Camera, stringResource(R.string.edit_take_photo), TestTags.Edit.TAKE_PHOTO) { onAction(EditAction.Capture(CaptureMode.PHOTO)) }
        BarButton(EditIcons.Video, stringResource(R.string.edit_record_video), TestTags.Edit.RECORD_VIDEO) { onAction(EditAction.Capture(CaptureMode.VIDEO)) }
        BarButton(EditIcons.AddPhoto, stringResource(R.string.edit_add_from_device), TestTags.Edit.ADD_FROM_DEVICE) { onAction(EditAction.AddFromDevice) }
        BarButton(
            icon = if (body.favorite) EditIcons.Favorite else EditIcons.FavoriteBorder,
            label = stringResource(if (body.favorite) R.string.edit_unfavorite else R.string.edit_favorite),
            tag = TestTags.Edit.FAVORITE,
        ) { onAction(EditAction.ToggleFavorite) }
        if (state.canPaste) BarButton(EditIcons.Paste, stringResource(R.string.edit_paste), TestTags.Edit.PASTE) { onAction(EditAction.Paste) }
        BarButton(
            icon = if (state.recording == null) EditIcons.Mic else EditIcons.Stop,
            label = stringResource(if (state.recording == null) R.string.voice_record else R.string.voice_stop),
            tag = TestTags.Edit.RECORD_VOICE,
        ) { onAction(EditAction.ToggleRecording) }
        BarButton(EditIcons.Share, stringResource(R.string.edit_share), TestTags.Edit.SHARE) { onAction(EditAction.Share) }
    }
}

@Composable
private fun BarButton(icon: ImageVector, label: String, tag: String, onClick: () -> Unit) {
    FilledTonalIconButton(onClick = onClick, modifier = Modifier.testTag(tag)) {
        Icon(imageVector = icon, contentDescription = label)
    }
}

@Composable
private fun RecordingTile(recording: RecordingUi, onAction: (EditAction) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(EditDimens.TILE_CORNER))
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(EditDimens.ROW_PADDING)
            .testTag(TestTags.Voice.RECORDING_TILE),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(EditDimens.SMALL_SPACING),
    ) {
        Icon(imageVector = EditIcons.Mic, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
        Text(
            text = stringResource(R.string.voice_recording, formatDuration(recording.elapsedMs)),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.weight(1f).testTag(TestTags.Voice.ELAPSED),
        )
        IconButton(onClick = { onAction(EditAction.StopRecording) }, modifier = Modifier.testTag(TestTags.Voice.STOP)) {
            Icon(imageVector = EditIcons.Stop, contentDescription = stringResource(R.string.voice_stop), tint = MaterialTheme.colorScheme.onErrorContainer)
        }
    }
}

@Composable
private fun VoiceNotes(notes: List<EditVoiceNote>, removable: Boolean, playing: String?, onAction: (EditAction) -> Unit) {
    Column(modifier = Modifier.testTag(TestTags.Voice.LIST), verticalArrangement = Arrangement.spacedBy(EditDimens.SMALL_SPACING)) {
        notes.forEachIndexed { position, note ->
            val label = stringResource(R.string.voice_label, (position + 1).toString())
            val isPlaying = playing == note.mediaId
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(EditDimens.TILE_CORNER))
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .padding(EditDimens.ROW_PADDING)
                    .testTag(listItem(TestTags.Voice.TILE, position))
                    .semantics { contentDescription = label },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(EditDimens.SMALL_SPACING),
            ) {
                IconButton(onClick = { onAction(EditAction.ToggleVoice(note.mediaId)) }, modifier = Modifier.testTag(listItem(TestTags.Voice.PLAY, position))) {
                    Icon(
                        imageVector = if (isPlaying) EditIcons.Pause else EditIcons.Play,
                        contentDescription = stringResource(if (isPlaying) R.string.voice_pause else R.string.voice_play),
                    )
                }
                Text(
                    text = formatDuration(note.durationMs ?: 0L),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f).testTag(listItem(TestTags.Voice.DURATION, position)),
                )
                if (removable) {
                    IconButton(onClick = { onAction(EditAction.RemoveVoice(note.mediaId)) }, modifier = Modifier.testTag(listItem(TestTags.Voice.REMOVE, position))) {
                        Icon(imageVector = KodakikoIcons.Close, contentDescription = stringResource(R.string.voice_remove))
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text = text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun WhenSection(time: EditTime, editor: TimeEditorUi?, onAction: (EditAction) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(EditDimens.TINY_SPACING)) {
        SectionLabel(stringResource(R.string.compose_when))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = formatEntryTime(LocalResources.current, time.takenAtUtc, time.utcOffsetMinutes, time.nowUtc, time.deviceOffsetMinutes),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                modifier = Modifier.weight(1f).testTag(TestTags.Compose.WHEN_VALUE),
            )
            IconButton(onClick = { onAction(EditAction.ToggleTimeEditor) }, modifier = Modifier.testTag(TestTags.Compose.CHANGE)) {
                Icon(imageVector = EditIcons.Edit, contentDescription = stringResource(R.string.edit_change_time))
            }
        }
        editor?.let { TimeEditor(it, onAction) }
    }
}

private enum class TimePart { DATE, TIME }

@Composable
private fun TimeEditor(editor: TimeEditorUi, onAction: (EditAction) -> Unit) {
    var date by remember(editor) { mutableStateOf(editor.date) }
    var time by remember(editor) { mutableStateOf(editor.time) }
    var picking by remember { mutableStateOf<TimePart?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(EditDimens.SMALL_SPACING)) {
        ClickableField(
            label = stringResource(R.string.time_editor_date),
            value = date,
            icon = KodakikoIcons.CalendarMonth,
            tag = TestTags.TimeEditor.DATE,
            onClick = { picking = TimePart.DATE },
        )
        ClickableField(
            label = stringResource(R.string.time_editor_time),
            value = time,
            icon = EditIcons.Schedule,
            tag = TestTags.TimeEditor.TIME,
            onClick = { picking = TimePart.TIME },
        )
        if (editor.invalid) {
            Text(
                text = stringResource(R.string.time_editor_invalid),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag(TestTags.TimeEditor.INVALID),
            )
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            OutlinedButton(onClick = { onAction(EditAction.ResetTime) }, modifier = Modifier.testTag(TestTags.TimeEditor.RESET)) {
                Text(text = stringResource(R.string.time_editor_reset))
            }
            Button(onClick = { onAction(EditAction.ApplyTime(date, time)) }, modifier = Modifier.testTag(TestTags.TimeEditor.DONE)) {
                Text(text = stringResource(R.string.time_editor_done))
            }
        }
    }
    when (picking) {
        TimePart.DATE -> EntryDatePicker(
            date = date,
            onPick = {
                date = it
                picking = null
            },
            onDismiss = { picking = null },
        )
        TimePart.TIME -> EntryTimePicker(
            time = time,
            onPick = {
                time = it
                picking = null
            },
            onDismiss = { picking = null },
        )
        null -> Unit
    }
}

@Composable
private fun ClickableField(label: String, value: String, icon: ImageVector, tag: String, onClick: () -> Unit) {
    val interactions = remember { MutableInteractionSource() }
    val currentOnClick by rememberUpdatedState(onClick)
    LaunchedEffect(interactions) {
        interactions.interactions.collect { if (it is PressInteraction.Release) currentOnClick() }
    }
    OutlinedTextField(
        value = value,
        onValueChange = {},
        readOnly = true,
        singleLine = true,
        label = { Text(text = label) },
        trailingIcon = { Icon(imageVector = icon, contentDescription = null) },
        interactionSource = interactions,
        modifier = Modifier.fillMaxWidth().testTag(tag),
    )
}

@Composable
private fun WhereSection(location: EntryLocation, onAction: (EditAction) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(EditDimens.TINY_SPACING)) {
        SectionLabel(stringResource(R.string.compose_where))
        Text(
            text = jsToFixed(location.latitude, EditConstants.COORDINATE_DECIMALS) + EditConstants.COORDINATE_SEPARATOR +
                jsToFixed(location.longitude, EditConstants.COORDINATE_DECIMALS),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.testTag(TestTags.Location.COORDINATES),
        )
        Text(
            text = stringResource(if (location.source == LocationSource.EXIF) R.string.location_source_exif else R.string.location_source_device),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag(TestTags.Location.SOURCE),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(EditDimens.SMALL_SPACING)) {
            FilledTonalIconButton(onClick = { onAction(EditAction.OpenMap) }, modifier = Modifier.testTag(TestTags.Location.OPEN)) {
                Icon(imageVector = EditIcons.Map, contentDescription = stringResource(R.string.location_open))
            }
            FilledTonalIconButton(onClick = { onAction(EditAction.RemoveLocation) }, modifier = Modifier.testTag(TestTags.Location.REMOVE)) {
                Icon(imageVector = EditIcons.LocationOff, contentDescription = stringResource(R.string.location_remove))
            }
        }
    }
}

@Composable
private fun TagsSection(tags: List<String>, onAction: (EditAction) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(EditDimens.SMALL_SPACING)) {
        SectionLabel(stringResource(R.string.compose_tags))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(EditDimens.SMALL_SPACING),
            verticalArrangement = Arrangement.spacedBy(EditDimens.SMALL_SPACING),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            tags.forEachIndexed { position, tag -> TagChip(tag, position, onAction) }
            IconButton(onClick = { onAction(EditAction.ChooseTags) }, modifier = Modifier.testTag(TestTags.Tags.CHOOSE)) {
                Icon(imageVector = EditIcons.Add, contentDescription = stringResource(R.string.tags_choose))
            }
        }
    }
}

@Composable
private fun TagChip(tag: String, position: Int, onAction: (EditAction) -> Unit) {
    val removeLabel = stringResource(R.string.tags_remove_tag, tag)
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(EditDimens.CHIP_CORNER))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(EditDimens.HAIRLINE, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(EditDimens.CHIP_CORNER))
            .padding(start = EditDimens.CHIP_PADDING),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = tag, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag(listItem(TestTags.Tags.CHIP, position)))
        Box(
            modifier = Modifier
                .size(EditDimens.CHIP_REMOVE_TARGET)
                .clip(CircleShape)
                .clickable(role = Role.Button) { onAction(EditAction.RemoveTag(tag)) }
                .semantics { contentDescription = removeLabel }
                .testTag(listItem(TestTags.Tags.REMOVE, position)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(imageVector = KodakikoIcons.Close, contentDescription = null, modifier = Modifier.size(EditDimens.CHIP_ICON), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

internal val TOOLBAR: List<Pair<MarkdownAction, Int>> = listOf(
    MarkdownAction.BOLD to R.string.toolbar_bold,
    MarkdownAction.ITALIC to R.string.toolbar_italic,
    MarkdownAction.STRIKE to R.string.toolbar_strike,
    MarkdownAction.HEADING to R.string.toolbar_heading,
    MarkdownAction.BULLET to R.string.toolbar_bullet,
    MarkdownAction.CHECKLIST to R.string.toolbar_checklist,
    MarkdownAction.QUOTE to R.string.toolbar_quote,
    MarkdownAction.LINK to R.string.toolbar_link,
    MarkdownAction.TIME to R.string.toolbar_time,
)

internal fun toolbarTag(action: MarkdownAction): String = when (action) {
    MarkdownAction.BOLD -> TestTags.Toolbar.BOLD
    MarkdownAction.ITALIC -> TestTags.Toolbar.ITALIC
    MarkdownAction.STRIKE -> TestTags.Toolbar.STRIKE
    MarkdownAction.HEADING -> TestTags.Toolbar.HEADING
    MarkdownAction.BULLET -> TestTags.Toolbar.BULLET
    MarkdownAction.CHECKLIST -> TestTags.Toolbar.CHECKLIST
    MarkdownAction.QUOTE -> TestTags.Toolbar.QUOTE
    MarkdownAction.LINK -> TestTags.Toolbar.LINK
    MarkdownAction.TIME -> TestTags.Toolbar.TIME
}

@Composable
internal fun toolbarIcon(action: MarkdownAction): ImageVector = when (action) {
    MarkdownAction.BOLD -> EditIcons.Bold
    MarkdownAction.ITALIC -> EditIcons.Italic
    MarkdownAction.STRIKE -> EditIcons.Strike
    MarkdownAction.HEADING -> EditIcons.Heading
    MarkdownAction.BULLET -> EditIcons.Bullet
    MarkdownAction.CHECKLIST -> KodakikoIcons.CheckBox
    MarkdownAction.QUOTE -> EditIcons.Quote
    MarkdownAction.LINK -> EditIcons.Link
    MarkdownAction.TIME -> EditIcons.Schedule
}

@Composable
private fun NoteSection(note: String, onAction: (EditAction) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(EditDimens.TINY_SPACING)) {
        SectionLabel(stringResource(R.string.compose_note))
        when {
            note.isBlank() -> AddNote(onAction)
            else -> Row(verticalAlignment = Alignment.Top) {
                MarkdownView(source = note, onOpenLink = { onAction(EditAction.OpenLink(it)) }, modifier = Modifier.weight(1f))
                IconButton(onClick = { onAction(EditAction.EditNote) }, modifier = Modifier.testTag(TestTags.Edit.EDIT_NOTE)) {
                    Icon(imageVector = EditIcons.Edit, contentDescription = stringResource(R.string.edit_edit_note))
                }
            }
        }
    }
}

@Composable
private fun AddNote(onAction: (EditAction) -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(EditDimens.CHIP_CORNER))
            .border(EditDimens.HAIRLINE, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(EditDimens.CHIP_CORNER))
            .clickable(role = Role.Button) { onAction(EditAction.EditNote) }
            .padding(EditDimens.ADD_NOTE_PADDING)
            .testTag(TestTags.Edit.ADD_NOTE),
    ) {
        Text(text = stringResource(R.string.edit_add_note), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
