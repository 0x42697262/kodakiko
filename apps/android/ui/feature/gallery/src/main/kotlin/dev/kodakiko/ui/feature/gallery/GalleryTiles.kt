// Adapted from https://github.com/mihonapp/mihon
// Copyright 2015 Javier Tomás, 2024 Mihon Open Source Project, Apache-2.0
package dev.kodakiko.ui.feature.gallery

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import dev.kodakiko.data.db.GalleryBadges
import dev.kodakiko.data.db.GalleryDisplayMode
import dev.kodakiko.ui.core.R
import dev.kodakiko.ui.core.TestTags
import dev.kodakiko.ui.core.TestTags.listItem
import dev.kodakiko.ui.core.components.Badge
import dev.kodakiko.ui.core.components.BadgeGroup
import dev.kodakiko.ui.core.components.material.padding
import dev.kodakiko.ui.core.icons.KodakikoIcons
import kotlinx.coroutines.CancellationException

@Composable
internal fun GalleryThumbnail(entryId: String, mediaId: String?, unreadable: Boolean, modifier: Modifier = Modifier) {
    Box(modifier = modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
        when {
            unreadable -> UnreadableThumbnail()
            mediaId == null -> ThumbnailPlaceholder()
            else -> LoadedThumbnail(entryId, mediaId)
        }
    }
}

@Composable
private fun LoadedThumbnail(entryId: String, mediaId: String) {
    val thumbnails = LocalGalleryThumbnails.current
    val image by produceState<ImageBitmap?>(null, entryId, mediaId, thumbnails) {
        value = try {
            thumbnails.load(entryId, mediaId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }
    val bitmap = image
    if (bitmap == null) {
        ThumbnailPlaceholder()
    } else {
        Image(bitmap = bitmap, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    }
}

@Composable
private fun ThumbnailPlaceholder() {
    Box(modifier = Modifier.fillMaxSize().testTag(TestTags.Gallery.THUMBNAIL_PLACEHOLDER), contentAlignment = Alignment.Center) {
        Icon(
            imageVector = GalleryIcons.Image,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = GalleryDimens.PLACEHOLDER_ALPHA),
            modifier = Modifier.size(GalleryDimens.PLACEHOLDER_ICON),
        )
    }
}

@Composable
private fun UnreadableThumbnail() {
    Column(
        modifier = Modifier.fillMaxSize().testTag(TestTags.Gallery.TILE_UNREADABLE),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = KodakikoIcons.Lock,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(GalleryDimens.LOCK_ICON),
        )
        Text(
            text = stringResource(R.string.gallery_cant_open),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

@Composable
internal fun TileBadges(tile: GalleryTile, badges: GalleryBadges, modifier: Modifier = Modifier) {
    val pending = badges.pending && tile.pending
    val favorite = badges.favorite && tile.favorite
    val video = badges.video && tile.hasVideo
    val note = badges.note && tile.hasNote
    val count = badges.photoCount && tile.extraMedia > 0
    if (!(pending || favorite || video || note || count)) return
    val notSynced = stringResource(R.string.gallery_not_synced)
    BadgeGroup(modifier = modifier) {
        if (pending) {
            Badge(
                imageVector = GalleryIcons.Pending,
                color = MaterialTheme.colorScheme.tertiary,
                iconColor = MaterialTheme.colorScheme.onTertiary,
                modifier = Modifier
                    .testTag(listItem(TestTags.Gallery.TILE_PENDING, tile.position))
                    .semantics { contentDescription = notSynced },
            )
        }
        if (favorite) Badge(imageVector = GalleryIcons.Favorite)
        if (video) Badge(imageVector = GalleryIcons.Video)
        if (note) Badge(imageVector = GalleryIcons.Notes)
        if (count) Badge(text = stringResource(R.string.gallery_more_media, tile.extraMedia))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun GalleryTileItem(
    tile: GalleryTile,
    display: GalleryDisplayMode,
    badges: GalleryBadges,
    selecting: Boolean,
    selected: Boolean,
    onAction: (GalleryAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(GalleryDimens.TILE_CORNER)
    Box(
        modifier = modifier
            .testTag(listItem(TestTags.Gallery.TILE, tile.position))
            .semantics { this.selected = selected }
            .clip(shape)
            .combinedClickable(
                onClick = { onAction(GalleryAction.TileClick(tile.entryId)) },
                onLongClick = { onAction(GalleryAction.TileLongClick(tile.entryId)) },
            ),
    ) {
        when (display) {
            GalleryDisplayMode.LIST -> ListTile(tile, badges, selecting, selected)
            GalleryDisplayMode.COMPACT_GRID -> CompactTile(tile, badges, selecting, selected)
            GalleryDisplayMode.PHOTO_GRID -> CoverBox(tile, selecting, selected, Modifier.fillMaxWidth())
            GalleryDisplayMode.COMFORTABLE_GRID -> ComfortableTile(tile, badges, selecting, selected)
        }
    }
}

@Composable
private fun CoverBox(
    tile: GalleryTile,
    selecting: Boolean,
    selected: Boolean,
    modifier: Modifier = Modifier,
    overlay: @Composable BoxScope.() -> Unit = {},
) {
    val shape = RoundedCornerShape(GalleryDimens.TILE_CORNER)
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(shape)
            .then(if (selected) Modifier.border(GalleryDimens.SELECTED_BORDER, MaterialTheme.colorScheme.primary, shape) else Modifier),
    ) {
        GalleryThumbnail(tile.entryId, tile.coverMediaId, tile.unreadable, Modifier.fillMaxSize())
        overlay()
        if (selecting) {
            Checkbox(
                checked = selected,
                onCheckedChange = null,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(GalleryDimens.CHECKBOX_PADDING)
                    .testTag(listItem(TestTags.Gallery.TILE_CHECKBOX, tile.position)),
            )
        }
    }
}

@Composable
private fun ComfortableTile(tile: GalleryTile, badges: GalleryBadges, selecting: Boolean, selected: Boolean) {
    Column(modifier = Modifier.fillMaxWidth()) {
        CoverBox(tile, selecting, selected, Modifier.fillMaxWidth()) {
            TileBadges(tile, badges, Modifier.align(Alignment.TopEnd).padding(GalleryDimens.BADGE_OFFSET))
        }
        Text(
            text = tile.time,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            modifier = Modifier.padding(top = GalleryDimens.CAPTION_PADDING, start = GalleryDimens.CAPTION_PADDING),
        )
        if (tile.note.isNotEmpty()) {
            Text(
                text = tile.note,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = GalleryDimens.CAPTION_PADDING),
            )
        }
    }
}

@Composable
private fun CompactTile(tile: GalleryTile, badges: GalleryBadges, selecting: Boolean, selected: Boolean) {
    val caption = if (tile.note.isEmpty()) tile.time else tile.time + GalleryConstants.COMPACT_SEPARATOR + tile.note
    CoverBox(tile, selecting, selected, Modifier.fillMaxWidth()) {
        TileBadges(tile, badges, Modifier.align(Alignment.TopEnd).padding(GalleryDimens.BADGE_OFFSET))
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = GalleryDimens.COMPACT_SHADE_ALPHA)))),
        ) {
            Text(
                text = caption,
                style = MaterialTheme.typography.labelMedium,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(GalleryDimens.CAPTION_PADDING),
            )
        }
    }
}

@Composable
private fun ListTile(tile: GalleryTile, badges: GalleryBadges, selecting: Boolean, selected: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = MaterialTheme.padding.extraSmall),
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.medium),
    ) {
        CoverBox(tile, selecting, selected, Modifier.size(GalleryDimens.LIST_THUMB))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(GalleryDimens.BADGE_SPACING)) {
            Text(
                text = tile.localDate + GalleryConstants.DATE_FIELD_SEPARATOR + tile.time,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
            )
            if (tile.note.isNotEmpty()) {
                Text(
                    text = tile.note,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = GalleryConstants.LIST_NOTE_LINES,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (tile.tags.isNotEmpty()) {
                Text(
                    text = tile.tags.joinToString(GalleryConstants.TAG_SEPARATOR) { GalleryConstants.TAG_PREFIX + it },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            TileBadges(tile, badges)
        }
    }
}
