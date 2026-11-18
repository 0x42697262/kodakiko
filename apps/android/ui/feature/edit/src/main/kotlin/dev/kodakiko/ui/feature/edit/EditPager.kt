package dev.kodakiko.ui.feature.edit

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import dev.kodakiko.contract.MediaKind
import dev.kodakiko.ui.core.R
import dev.kodakiko.ui.core.TestTags
import dev.kodakiko.ui.core.TestTags.listItem
import kotlinx.coroutines.CancellationException

@Composable
internal fun MediaPager(entryId: String, pages: List<EditPage>, page: Int, onAction: (EditAction) -> Unit) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val height = maxWidth * EditConstants.PAGE_ASPECT
        key(pages.map { it.mediaId }) {
            PagerBody(entryId, pages, page, onAction, Modifier.fillMaxWidth().height(height))
        }
    }
}

@Composable
private fun PagerBody(entryId: String, pages: List<EditPage>, page: Int, onAction: (EditAction) -> Unit, modifier: Modifier) {
    val pagerState = rememberPagerState(initialPage = page.coerceIn(0, pages.lastIndex)) { pages.size }
    val currentPage by rememberUpdatedState(page)
    val currentOnAction by rememberUpdatedState(onAction)
    LaunchedEffect(pagerState, page) {
        if (page in pages.indices && pagerState.currentPage != page) pagerState.animateScrollToPage(page)
    }
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.collect { settled ->
            if (settled != currentPage) currentOnAction(EditAction.PageChanged(settled))
        }
    }
    HorizontalPager(
        state = pagerState,
        key = { pages[it].mediaId },
        modifier = modifier.testTag(TestTags.Edit.PAGER),
    ) { index ->
        MediaPage(entryId, pages[index], index, onAction)
    }
}

@Composable
private fun MediaPage(entryId: String, item: EditPage, position: Int, onAction: (EditAction) -> Unit) {
    val thumbnail = rememberThumbnail(entryId, item.mediaId)
    val options = stringResource(R.string.edit_media_options)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(EditDimens.TILE_CORNER))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(role = Role.Button, onClickLabel = options) { onAction(EditAction.OpenMediaMenu(item.mediaId)) }
            .testTag(listItem(TestTags.Edit.PAGE, position)),
    ) {
        thumbnail?.let { Image(bitmap = it, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
        if (item.kind == MediaKind.VIDEO) {
            val play = stringResource(R.string.viewer_play)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(role = Role.Button) { onAction(EditAction.OpenPlayer(item.mediaId)) }
                    .semantics { contentDescription = play }
                    .testTag(listItem(TestTags.Edit.PLAY, position)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(imageVector = EditIcons.Play, contentDescription = null, tint = Color.White, modifier = Modifier.size(EditDimens.PLAY_ICON))
            }
        }
        item.caption?.let { caption ->
            Text(
                text = caption,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = EditConstants.CAPTION_SCRIM_ALPHA))
                    .padding(horizontal = EditDimens.CAPTION_PADDING_HORIZONTAL, vertical = EditDimens.CAPTION_PADDING_VERTICAL)
                    .testTag(listItem(TestTags.Edit.CAPTION_TEXT, position)),
            )
        }
        FilledTonalIconButton(
            onClick = { onAction(EditAction.OpenMediaMenu(item.mediaId)) },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(EditDimens.SMALL_SPACING)
                .testTag(listItem(TestTags.Edit.MEDIA_MENU, position)),
        ) {
            Icon(imageVector = EditIcons.Tune, contentDescription = options)
        }
    }
}

@Composable
private fun rememberThumbnail(entryId: String, mediaId: String): ImageBitmap? {
    val images = LocalEditImages.current
    val thumbnail by produceState<ImageBitmap?>(null, entryId, mediaId, images) {
        value = try {
            images.thumbnail(entryId, mediaId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }
    return thumbnail
}
