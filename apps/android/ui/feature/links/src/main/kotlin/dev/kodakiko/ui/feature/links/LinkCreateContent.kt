@file:OptIn(ExperimentalMaterial3Api::class)

package dev.kodakiko.ui.feature.links

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import dev.kodakiko.contract.LinkExpiry
import dev.kodakiko.js.jsTrim
import dev.kodakiko.ui.core.R
import dev.kodakiko.ui.core.TestTags
import dev.kodakiko.ui.core.TestTags.listItem
import dev.kodakiko.ui.core.components.AppBar
import dev.kodakiko.ui.core.components.Banner
import dev.kodakiko.ui.core.components.BannerKind
import dev.kodakiko.ui.core.components.FormButton
import dev.kodakiko.ui.core.components.FormButtonStyle
import dev.kodakiko.ui.core.components.FormSwitch
import dev.kodakiko.ui.core.components.FormTextField
import dev.kodakiko.ui.core.components.material.Scaffold
import dev.kodakiko.ui.core.components.material.padding
import dev.kodakiko.ui.core.format.formatBytes
import dev.kodakiko.ui.core.icons.KodakikoIcons
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

@Composable
private fun expiryLabel(expiry: LinkExpiry): String = stringResource(
    when (expiry) {
        LinkExpiry.ONE_HOUR -> R.string.links_expiry1h
        LinkExpiry.ONE_DAY -> R.string.links_expiry1d
        LinkExpiry.SEVEN_DAYS -> R.string.links_expiry7d
        LinkExpiry.FOURTEEN_DAYS -> R.string.links_expiry14d
        LinkExpiry.THIRTY_DAYS -> R.string.links_expiry30d
        LinkExpiry.NEVER -> R.string.links_expiry_never
        LinkExpiry.ONE_VIEW -> R.string.links_expiry_one_view
    },
)

@Composable
fun LinkCreateContent(state: LinkCreateState, onAction: (LinkCreateAction) -> Unit, modifier: Modifier = Modifier) {
    Scaffold(
        modifier = modifier.testTag(TestTags.Links.CREATE_SCREEN),
        topBar = {
            AppBar(
                title = stringResource(R.string.links_new_title),
                navigateUp = { onAction(if (state.result == null) LinkCreateAction.Cancel else LinkCreateAction.Done) },
                scrollBehavior = it,
            )
        },
    ) { padding ->
        val scrollState = rememberScrollState()
        val creating = state.progress != null
        LaunchedEffect(state.error, creating) {
            if (state.error == null && !creating) return@LaunchedEffect
            val end = snapshotFlow { scrollState.maxValue }.first { it != Int.MAX_VALUE }
            scrollState.animateScrollTo(end)
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(scrollState)
                .padding(LinksDimens.SCREEN_PADDING),
            verticalArrangement = Arrangement.spacedBy(LinksDimens.SECTION_SPACING),
        ) {
            if (state.result == null) LinkForm(state, onAction) else LinkResult(state, onAction)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColumnScope.LinkForm(state: LinkCreateState, onAction: (LinkCreateAction) -> Unit) {
    FormTextField(
        value = state.title,
        onValueChange = { onAction(LinkCreateAction.TitleChanged(it)) },
        label = stringResource(R.string.links_title_field),
        testTag = TestTags.Links.TITLE,
        imeAction = ImeAction.Done,
        capitalize = true,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small)) {
        SmallButton(stringResource(R.string.links_select_all), TestTags.Links.SELECT_ALL) { onAction(LinkCreateAction.SelectAll) }
        SmallButton(stringResource(R.string.links_select_none), TestTags.Links.SELECT_NONE) { onAction(LinkCreateAction.SelectNone) }
    }
    state.sections.orEmpty().forEachIndexed { position, section ->
        Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small)) {
            Text(text = section.heading, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small)) {
                SmallButton(stringResource(R.string.links_select_all), listItem(TestTags.Links.ENTRY_ALL, position)) {
                    onAction(LinkCreateAction.SelectEntry(section.entryId, on = true))
                }
                SmallButton(stringResource(R.string.links_select_none), listItem(TestTags.Links.ENTRY_NONE, position)) {
                    onAction(LinkCreateAction.SelectEntry(section.entryId, on = false))
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(LinksDimens.TILE_SPACING), verticalArrangement = Arrangement.spacedBy(LinksDimens.TILE_SPACING)) {
                section.media.forEach { media ->
                    MediaToggle(section.entryId, media, media.mediaId in state.selected) { onAction(LinkCreateAction.Toggle(media.mediaId)) }
                }
            }
        }
    }
    FormSwitch(
        text = stringResource(R.string.links_include_notes),
        checked = state.includeNotes,
        onCheckedChange = { onAction(LinkCreateAction.ToggleNotes) },
        testTag = TestTags.Links.INCLUDE_NOTES,
    )
    Text(text = stringResource(R.string.links_expiry), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small)) {
        LinkExpiry.entries.forEach { expiry ->
            FilterChip(
                selected = state.expiry == expiry,
                onClick = { onAction(LinkCreateAction.ChooseExpiry(expiry)) },
                label = { Text(text = expiryLabel(expiry)) },
                modifier = Modifier.testTag(TestTags.Links.EXPIRY + LinksConstants.TAG_SEPARATOR + expiry.wire),
            )
        }
    }
    Text(text = stringResource(R.string.links_estimate, formatBytes(state.estimateBytes)), style = MaterialTheme.typography.bodyMedium)
    Banner(text = stringResource(R.string.links_warning), kind = BannerKind.WARNING, testTag = TestTags.Links.WARNING)
    CreateFooter(state, onAction)
}

@Composable
private fun CreateFooter(state: LinkCreateState, onAction: (LinkCreateAction) -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(LinksDimens.SECTION_SPACING),
    ) {
        state.error?.let { error ->
            Banner(
                text = stringResource(if (error == LinkError.OFFLINE) R.string.links_offline else R.string.links_failed),
                kind = BannerKind.ERROR,
                testTag = TestTags.Links.ERROR,
            )
        }
        state.progress?.let { progress ->
            Text(
                text = linkProgressText(LocalContext.current.resources, progress),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag(TestTags.Links.PROGRESS),
            )
        }
        FormButton(
            text = stringResource(R.string.links_create),
            onClick = { onAction(LinkCreateAction.Create) },
            testTag = TestTags.Links.CREATE,
            enabled = state.selected.isNotEmpty() && jsTrim(state.title).isNotEmpty(),
            busy = state.progress != null,
        )
        FormButton(
            text = stringResource(R.string.links_cancel),
            onClick = { onAction(LinkCreateAction.Cancel) },
            testTag = TestTags.Links.CANCEL,
            style = FormButtonStyle.SECONDARY,
        )
    }
}

@Composable
private fun LinkResult(state: LinkCreateState, onAction: (LinkCreateAction) -> Unit) {
    val result = state.result ?: return
    Text(text = stringResource(R.string.links_ready), style = MaterialTheme.typography.titleLarge)
    SelectionContainer {
        Text(
            text = result.url,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier
                .fillMaxWidth()
                .border(LinksDimens.URL_BORDER, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(LinksDimens.TILE_RADIUS))
                .padding(LinksDimens.URL_PADDING)
                .testTag(TestTags.Links.URL),
        )
    }
    linkSkippedLines(LocalContext.current.resources, result).forEach { line ->
        Text(text = line, style = MaterialTheme.typography.bodyMedium)
    }
    if (state.copied) Banner(text = stringResource(R.string.links_copied), kind = BannerKind.INFO, testTag = TestTags.Links.COPIED)
    FormButton(text = stringResource(R.string.links_copy), onClick = { onAction(LinkCreateAction.Copy) }, testTag = TestTags.Links.COPY)
    FormButton(
        text = stringResource(R.string.links_share),
        onClick = { onAction(LinkCreateAction.Share) },
        testTag = TestTags.Links.SHARE,
        style = FormButtonStyle.SECONDARY,
    )
    FormButton(
        text = stringResource(R.string.links_done),
        onClick = { onAction(LinkCreateAction.Done) },
        testTag = TestTags.Links.DONE,
        style = FormButtonStyle.TEXT,
    )
}

@Composable
private fun SmallButton(text: String, testTag: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.testTag(testTag)) {
        Text(text = text)
    }
}

@Composable
private fun MediaToggle(entryId: String, media: LinkMedia, selected: Boolean, onToggle: () -> Unit) {
    val thumbnails = LocalLinkThumbnails.current
    var bitmap by remember(entryId, media.mediaId) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(thumbnails, entryId, media.mediaId) {
        bitmap = try {
            thumbnails.load(entryId, media.mediaId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }
    val shape = RoundedCornerShape(LinksDimens.TILE_RADIUS)
    Box(
        modifier = Modifier
            .size(LinksDimens.TILE)
            .clip(shape)
            .border(LinksDimens.TILE_BORDER, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, shape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .testTag(listItem(TestTags.Links.MEDIA_TOGGLE, media.position))
            .toggleable(value = selected, role = Role.Checkbox, onValueChange = { onToggle() }),
    ) {
        val image = bitmap
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().alpha(if (selected) 1f else LinksConstants.UNSELECTED_ALPHA),
            )
        }
        Icon(
            imageVector = if (selected) KodakikoIcons.CheckBox else KodakikoIcons.CheckBoxOutlineBlank,
            contentDescription = null,
            tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.align(Alignment.TopEnd).padding(LinksDimens.CHECK_PADDING).size(LinksDimens.CHECK_SIZE),
        )
    }
}
