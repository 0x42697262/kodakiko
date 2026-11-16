package dev.kodakiko.ui.feature.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.zIndex
import dev.kodakiko.contract.EntryLocation
import dev.kodakiko.contract.LocationSource
import dev.kodakiko.js.jsToFixed
import dev.kodakiko.ui.core.R
import dev.kodakiko.ui.core.TestTags
import dev.kodakiko.ui.core.TestTags.listItem
import dev.kodakiko.ui.core.components.ActionButton
import dev.kodakiko.ui.core.components.Banner
import dev.kodakiko.ui.core.components.BannerKind
import dev.kodakiko.ui.core.format.formatDuration
import dev.kodakiko.ui.core.format.formatEntryTime
import dev.kodakiko.ui.core.icons.KodakikoIcons

@Composable
fun EntryViewerContent(state: ViewerState, onAction: (ViewerAction) -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .testTag(TestTags.Viewer.SCREEN),
    ) {
        val entry = state.entry
        val scroll = rememberScrollState()
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars)
                .verticalScroll(scroll)
                .padding(horizontal = ViewerDimens.SCREEN_PADDING, vertical = ViewerDimens.SMALL_SPACING),
            verticalArrangement = Arrangement.spacedBy(ViewerDimens.SECTION_SPACING),
        ) {
            ViewerHeader(
                entry = entry,
                onAction = onAction,
                modifier = Modifier
                    .zIndex(1f)
                    .offset { IntOffset(0, scroll.value) }
                    .background(MaterialTheme.colorScheme.background),
            )
            if (entry != null) EntryParts(state, entry, onAction)
        }
        if (entry != null) ViewerSheets(state, entry, onAction)
    }
}

@Composable
private fun ViewerHeader(entry: ViewerEntry?, onAction: (ViewerAction) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { onAction(ViewerAction.Back) }, modifier = Modifier.testTag(TestTags.Viewer.BACK)) {
            Icon(imageVector = KodakikoIcons.ArrowBack, contentDescription = stringResource(R.string.viewer_back))
        }
        Spacer(modifier = Modifier.weight(1f))
        if (entry != null && !entry.trashed && !entry.unreadable) {
            if (entry.owner) {
                IconButton(onClick = { onAction(ViewerAction.Edit) }, modifier = Modifier.testTag(TestTags.Viewer.EDIT)) {
                    Icon(imageVector = ViewerIcons.Edit, contentDescription = stringResource(R.string.viewer_edit))
                }
            }
            IconButton(onClick = { onAction(ViewerAction.OpenMore) }, modifier = Modifier.testTag(TestTags.Viewer.MORE)) {
                Icon(imageVector = KodakikoIcons.MoreVert, contentDescription = stringResource(R.string.viewer_more))
            }
        }
    }
}

@Composable
private fun EntryParts(state: ViewerState, entry: ViewerEntry, onAction: (ViewerAction) -> Unit) {
    val sharedBy = entry.sharedBy
    if (!entry.owner && sharedBy != null) {
        Text(
            text = stringResource(R.string.shared_shared_by, sharedBy),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag(TestTags.Viewer.SHARED_BY),
        )
    }
    if (entry.trashed && entry.owner) TrashedBlock(onAction)
    if (entry.unreadable) UnreadableBlock(entry.owner, onAction)
    entry.body?.let { body -> BodyParts(state, entry, body, onAction) }
}

@Composable
private fun TrashedBlock(onAction: (ViewerAction) -> Unit) {
    Banner(text = stringResource(R.string.viewer_in_trash), kind = BannerKind.INFO, testTag = TestTags.Viewer.IN_TRASH)
    Row(horizontalArrangement = Arrangement.spacedBy(ViewerDimens.SMALL_SPACING)) {
        Button(onClick = { onAction(ViewerAction.Restore) }, modifier = Modifier.testTag(TestTags.Viewer.RESTORE)) {
            Icon(imageVector = ViewerIcons.Restore, contentDescription = null)
            Text(text = stringResource(R.string.viewer_restore), modifier = Modifier.padding(start = ViewerDimens.SMALL_SPACING))
        }
        DeletePermanentlyButton(onAction)
    }
}

@Composable
private fun UnreadableBlock(owner: Boolean, onAction: (ViewerAction) -> Unit) {
    Text(
        text = stringResource(R.string.viewer_unreadable),
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.testTag(TestTags.Viewer.UNREADABLE),
    )
    if (owner) DeletePermanentlyButton(onAction)
}

@Composable
private fun DeletePermanentlyButton(onAction: (ViewerAction) -> Unit) {
    Button(
        onClick = { onAction(ViewerAction.AskDeletePermanently) },
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
        modifier = Modifier.testTag(TestTags.Viewer.DELETE_PERMANENTLY),
    ) {
        Icon(imageVector = ViewerIcons.DeleteForever, contentDescription = null)
        Text(text = stringResource(R.string.viewer_delete_permanently), modifier = Modifier.padding(start = ViewerDimens.SMALL_SPACING))
    }
}

@Composable
private fun BodyParts(state: ViewerState, entry: ViewerEntry, body: ViewerBody, onAction: (ViewerAction) -> Unit) {
    if (body.pages.isNotEmpty()) {
        ViewerPager(state.entryId, body.pages, state.page, state.zoomed, state.video, onAction)
        body.pages.getOrNull(state.page)?.caption?.let { caption ->
            Text(
                text = caption,
                maxLines = 1,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.fillMaxWidth().testTag(TestTags.Viewer.CAPTION),
            )
        }
        Text(
            text = stringResource(R.string.viewer_page_indicator, state.page + 1, body.pages.size),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().testTag(TestTags.Viewer.PAGE_INDICATOR),
        )
    }
    Text(
        text = formatEntryTime(LocalResources.current, body.time.takenAtUtc, body.time.utcOffsetMinutes, body.time.nowUtc, body.time.deviceOffsetMinutes),
        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        modifier = Modifier.testTag(TestTags.Viewer.TIME),
    )
    body.location?.let { LocationRow(it) }
    ActionRow(entry, body, onAction)
    if (body.voiceNotes.isNotEmpty()) VoiceNotes(body.voiceNotes, state.voicePlaying, onAction)
    if (body.tags.isNotEmpty()) TagChips(body.tags, onAction)
    MarkdownView(source = body.note, onOpenLink = { onAction(ViewerAction.OpenLink(it)) })
}

@Composable
private fun LocationRow(location: EntryLocation) {
    Column(verticalArrangement = Arrangement.spacedBy(ViewerDimens.TINY_SPACING)) {
        Text(
            text = jsToFixed(location.latitude, ViewerConstants.COORDINATE_DECIMALS) + ViewerConstants.COORDINATE_SEPARATOR +
                jsToFixed(location.longitude, ViewerConstants.COORDINATE_DECIMALS),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.testTag(TestTags.Location.COORDINATES),
        )
        Text(
            text = stringResource(if (location.source == LocationSource.EXIF) R.string.location_source_exif else R.string.location_source_device),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag(TestTags.Location.SOURCE),
        )
    }
}

@Composable
private fun ActionRow(entry: ViewerEntry, body: ViewerBody, onAction: (ViewerAction) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        if (body.location != null) {
            RowAction(ViewerIcons.Map, stringResource(R.string.viewer_action_map), TestTags.Location.OPEN) { onAction(ViewerAction.OpenMap) }
        }
        if (entry.owner && !entry.trashed) {
            RowAction(
                icon = if (body.favorite) ViewerIcons.Favorite else ViewerIcons.FavoriteBorder,
                label = stringResource(if (body.favorite) R.string.viewer_action_favorited else R.string.viewer_action_favorite),
                tag = TestTags.Viewer.FAVORITE,
            ) { onAction(ViewerAction.ToggleFavorite) }
        }
        RowAction(KodakikoIcons.Info, stringResource(R.string.viewer_action_details), TestTags.Viewer.DETAILS_ACTION) { onAction(ViewerAction.OpenDetails) }
        if (!entry.trashed) {
            RowAction(ViewerIcons.Share, stringResource(R.string.viewer_action_share), TestTags.Viewer.SHARE_ACTION) { onAction(ViewerAction.Share) }
        }
    }
}

@Composable
private fun RowAction(icon: ImageVector, label: String, tag: String, onClick: () -> Unit) {
    ActionButton(title = label, icon = icon, onClick = onClick, modifier = Modifier.testTag(tag))
}

@Composable
private fun VoiceNotes(notes: List<VoiceNote>, playing: String?, onAction: (ViewerAction) -> Unit) {
    Column(modifier = Modifier.testTag(TestTags.Voice.LIST), verticalArrangement = Arrangement.spacedBy(ViewerDimens.SMALL_SPACING)) {
        notes.forEachIndexed { position, note ->
            val label = stringResource(R.string.voice_label, position + 1)
            val isPlaying = playing == note.mediaId
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(ViewerDimens.TILE_CORNER))
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .padding(ViewerDimens.ROW_PADDING)
                    .testTag(listItem(TestTags.Voice.TILE, position))
                    .semantics { contentDescription = label },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ViewerDimens.SECTION_SPACING),
            ) {
                IconButton(onClick = { onAction(ViewerAction.ToggleVoice(note.mediaId)) }, modifier = Modifier.testTag(listItem(TestTags.Voice.PLAY, position))) {
                    Icon(
                        imageVector = if (isPlaying) ViewerIcons.Pause else ViewerIcons.Play,
                        contentDescription = stringResource(if (isPlaying) R.string.voice_pause else R.string.voice_play),
                    )
                }
                Text(
                    text = formatDuration(note.durationMs ?: 0L),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f).testTag(listItem(TestTags.Voice.DURATION, position)),
                )
            }
        }
    }
}

@Composable
private fun TagChips(tags: List<String>, onAction: (ViewerAction) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(ViewerDimens.SMALL_SPACING),
    ) {
        tags.forEachIndexed { position, tag ->
            AssistChip(
                onClick = { onAction(ViewerAction.ShowTag(tag)) },
                label = { Text(text = tag) },
                modifier = Modifier.testTag(listItem(TestTags.Viewer.TAG_CHIP, position)),
            )
        }
    }
}
