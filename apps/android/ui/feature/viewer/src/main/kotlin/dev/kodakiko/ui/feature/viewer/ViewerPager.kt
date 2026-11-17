package dev.kodakiko.ui.feature.viewer

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.min
import dev.kodakiko.contract.MediaKind
import dev.kodakiko.ui.core.R
import dev.kodakiko.ui.core.TestTags
import dev.kodakiko.ui.core.TestTags.listItem
import kotlinx.coroutines.CancellationException
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.min

@Composable
internal fun ViewerPager(entryId: String, pages: List<ViewerPage>, page: Int, zoomed: Boolean, video: InlineVideoUi?, onAction: (ViewerAction) -> Unit) {
    val windowHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val height = min(maxWidth * ViewerConstants.PAGE_ASPECT, windowHeight * ViewerConstants.PAGE_MAX_SCREEN_SHARE)
        key(pages.map { it.mediaId }) {
            PagerBody(entryId, pages, page, zoomed, video, height, onAction)
        }
    }
}

@Composable
private fun PagerBody(entryId: String, pages: List<ViewerPage>, page: Int, zoomed: Boolean, video: InlineVideoUi?, height: Dp, onAction: (ViewerAction) -> Unit) {
    val pagerState = rememberPagerState(initialPage = page.coerceIn(0, pages.lastIndex)) { pages.size }
    val currentPage by rememberUpdatedState(page)
    val currentOnAction by rememberUpdatedState(onAction)
    LaunchedEffect(pagerState, page) {
        if (page in pages.indices && pagerState.currentPage != page) pagerState.scrollToPage(page)
    }
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.collect { settled ->
            if (settled != currentPage) currentOnAction(ViewerAction.PageChanged(settled))
        }
    }
    HorizontalPager(
        state = pagerState,
        beyondViewportPageCount = ViewerConstants.ORIGINAL_WINDOW,
        userScrollEnabled = !zoomed,
        key = { pages[it].mediaId },
        modifier = Modifier.fillMaxWidth().height(height),
    ) { index ->
        val item = pages[index]
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(ViewerDimens.TILE_CORNER))
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .testTag(listItem(TestTags.Viewer.PAGE, index)),
        ) {
            when (item.kind) {
                MediaKind.PHOTO -> PhotoPage(
                    entryId = entryId,
                    page = item,
                    position = index,
                    active = index == page,
                    near = abs(index - page) <= ViewerConstants.ORIGINAL_WINDOW,
                    onZoomChange = { onAction(ViewerAction.ZoomChanged(it)) },
                )
                MediaKind.VIDEO -> VideoPage(entryId, item, index, video?.takeIf { it.position == index }, onAction)
                MediaKind.AUDIO -> Unit
            }
            item.caption?.let { caption ->
                Text(
                    text = caption,
                    maxLines = ViewerConstants.CAPTION_MAX_LINES,
                    overflow = TextOverflow.Ellipsis,
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = ViewerConstants.CAPTION_SCRIM_ALPHA))
                        .padding(horizontal = ViewerDimens.CAPTION_PADDING_HORIZONTAL, vertical = ViewerDimens.CAPTION_PADDING_VERTICAL)
                        .then(if (index == page) Modifier.testTag(TestTags.Viewer.CAPTION) else Modifier),
                )
            }
        }
    }
}

@Composable
private fun rememberThumbnail(entryId: String, mediaId: String): ImageBitmap? {
    val images = LocalViewerImages.current
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

@Composable
private fun PhotoPage(entryId: String, page: ViewerPage, position: Int, active: Boolean, near: Boolean, onZoomChange: (Boolean) -> Unit) {
    val images = LocalViewerImages.current
    val thumbnail = rememberThumbnail(entryId, page.mediaId)
    var attempt by remember(page.mediaId) { mutableIntStateOf(0) }
    var progress by remember(page.mediaId) { mutableStateOf<DownloadProgress?>(null) }
    val original by produceState<OriginalImage?>(null, entryId, page.mediaId, near, attempt, images) {
        value = null
        progress = null
        if (!near) return@produceState
        value = try {
            images.original(entryId, page.mediaId) { progress = it }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            OriginalImage.Failed
        }
    }
    Box(modifier = Modifier.fillMaxSize()) {
        ZoomablePhoto((original as? OriginalImage.Loaded)?.bitmap ?: thumbnail, page, position, active, onZoomChange)
        val downloading = progress
        when {
            original == OriginalImage.Missing -> MediaLabel(stringResource(R.string.viewer_showing_preview), TestTags.Viewer.SHOWING_PREVIEW)
            original == null && downloading != null -> MediaLabel(downloadingText(downloading), TestTags.Viewer.DOWNLOADING)
            original == OriginalImage.Failed -> Column(
                modifier = Modifier.align(Alignment.BottomStart).padding(ViewerDimens.SMALL_SPACING),
                verticalArrangement = Arrangement.spacedBy(ViewerDimens.TINY_SPACING),
            ) {
                MediaLabelText(stringResource(R.string.viewer_download_failed), TestTags.Viewer.DOWNLOAD_FAILED)
                FilledTonalButton(onClick = { attempt += 1 }, modifier = Modifier.testTag(TestTags.Viewer.RETRY)) {
                    Text(text = stringResource(R.string.viewer_retry))
                }
            }
        }
    }
}

@Composable
private fun downloadingText(progress: DownloadProgress): String {
    if (progress.total <= 0L) return stringResource(R.string.viewer_downloading)
    val percent = floor(progress.received.toDouble() / progress.total * ViewerConstants.PERCENT).toInt()
    return stringResource(R.string.viewer_download_progress, percent)
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.MediaLabel(text: String, tag: String) {
    Box(modifier = Modifier.align(Alignment.BottomStart).padding(ViewerDimens.SMALL_SPACING)) {
        MediaLabelText(text, tag)
    }
}

@Composable
private fun MediaLabelText(text: String, tag: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.inverseOnSurface,
        modifier = Modifier
            .background(MaterialTheme.colorScheme.inverseSurface, RoundedCornerShape(ViewerDimens.LABEL_CORNER))
            .padding(horizontal = ViewerDimens.SMALL_SPACING, vertical = ViewerDimens.TINY_SPACING)
            .testTag(tag),
    )
}

@Composable
private fun ZoomablePhoto(bitmap: ImageBitmap?, page: ViewerPage, position: Int, active: Boolean, onZoomChange: (Boolean) -> Unit) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize().clipToBounds()) {
        val viewportWidth = constraints.maxWidth.toFloat()
        val viewportHeight = constraints.maxHeight.toFloat()
        val zoom = remember(viewportWidth, viewportHeight, page.width, page.height) {
            PhotoZoomState(viewportWidth, viewportHeight, page.width.toFloat(), page.height.toFloat())
        }
        val currentOnZoomChange by rememberUpdatedState(onZoomChange)
        val reporter = remember(zoom) { ZoomReporter(zoom) { currentOnZoomChange(it) } }
        LaunchedEffect(zoom, active) {
            if (!active && zoom.zoomed) {
                zoom.reset()
                reporter.report()
            }
        }
        val label = stringResource(if (zoom.zoomed) R.string.viewer_photo_zoomed_label else R.string.viewer_photo_label, position + 1)
        val hint = stringResource(R.string.viewer_zoom_hint)
        val center = Offset(viewportWidth / ViewerConstants.HALF.toFloat(), viewportHeight / ViewerConstants.HALF.toFloat())
        Box(
            modifier = Modifier
                .fillMaxSize()
                .testTag(listItem(TestTags.Viewer.ZOOMABLE, position))
                .semantics {
                    contentDescription = label
                    customActions = listOf(
                        CustomAccessibilityAction(hint) {
                            zoom.doubleTap(center)
                            reporter.report()
                            true
                        },
                    )
                }
                .pointerInput(zoom) {
                    detectTapGestures(
                        onDoubleTap = { position ->
                            zoom.doubleTap(position)
                            reporter.report()
                        },
                    )
                }
                .pointerInput(zoom) { zoomGestures(zoom, reporter) },
            contentAlignment = Alignment.Center,
        ) {
            if (bitmap != null) {
                val density = LocalDensity.current
                val ratio = fittedRatio(viewportWidth, viewportHeight, page)
                val imageModifier = if (ratio > 0f) {
                    with(density) { Modifier.size((page.width * ratio).toDp(), (page.height * ratio).toDp()) }
                } else {
                    Modifier.fillMaxSize()
                }
                Image(
                    bitmap = bitmap,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = imageModifier.graphicsLayer {
                        scaleX = zoom.scale
                        scaleY = zoom.scale
                        translationX = zoom.offset.x
                        translationY = zoom.offset.y
                    },
                )
            }
        }
    }
}

private fun fittedRatio(viewportWidth: Float, viewportHeight: Float, page: ViewerPage): Float =
    if (page.width > 0L && page.height > 0L) min(viewportWidth / page.width, viewportHeight / page.height) else 0f

private class ZoomReporter(private val zoom: PhotoZoomState, private val onChange: (Boolean) -> Unit) {
    private var reported = false

    fun report() {
        val zoomed = zoom.zoomed
        if (zoomed == reported) return
        reported = zoomed
        onChange(zoomed)
    }
}

private suspend fun PointerInputScope.zoomGestures(zoom: PhotoZoomState, reporter: ZoomReporter) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        var pinched = false
        var dragging = false
        var travelled = Offset.Zero
        do {
            val event = awaitPointerEvent()
            if (event.changes.any { it.isConsumed }) break
            val pressed = event.changes.count { it.pressed }
            val zoomChange = event.calculateZoom()
            if (pressed > 1 && zoomChange != 1f) {
                zoom.pinch(zoomChange, event.calculateCentroid(useCurrent = true))
                pinched = true
            }
            val pan = event.calculatePan()
            travelled += pan
            if (!dragging && travelled.getDistance() > viewConfiguration.touchSlop) dragging = true
            val panning = zoom.zoomed && dragging
            if (panning && pan != Offset.Zero) zoom.pan(pan)
            if (pinched || panning) event.changes.forEach { it.consume() }
        } while (event.changes.any { it.pressed })
        if (pinched) zoom.endPinch()
        reporter.report()
    }
}

@Composable
private fun VideoPage(entryId: String, page: ViewerPage, position: Int, inline: InlineVideoUi?, onAction: (ViewerAction) -> Unit) {
    val thumbnail = rememberThumbnail(entryId, page.mediaId)
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        thumbnail?.let { Image(bitmap = it, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
        if (inline != null) {
            val tag = listItem(TestTags.Viewer.INLINE_VIDEO, position)
            Box(modifier = Modifier.fillMaxSize().testTag(tag).clickable { onAction(ViewerAction.OpenPlayer(page.mediaId)) }) {
                LocalVideoSurfaces.current.Inline(inline.video, inline.muted, Modifier.fillMaxSize())
                IconButton(
                    onClick = { onAction(ViewerAction.ToggleMute) },
                    colors = IconButtonDefaults.filledTonalIconButtonColors(),
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(ViewerDimens.SMALL_SPACING)
                        .testTag(tag + TestTags.Viewer.INLINE_VIDEO_MUTE_SUFFIX),
                ) {
                    Icon(
                        imageVector = if (inline.muted) ViewerIcons.VolumeOff else ViewerIcons.VolumeUp,
                        contentDescription = stringResource(if (inline.muted) R.string.video_unmute else R.string.video_mute),
                    )
                }
            }
        }
        if (inline == null) {
            Button(onClick = { onAction(ViewerAction.OpenPlayer(page.mediaId)) }, modifier = Modifier.testTag(TestTags.Viewer.PLAY)) {
                Icon(imageVector = ViewerIcons.Play, contentDescription = null)
                Text(text = stringResource(R.string.viewer_play), modifier = Modifier.padding(start = ViewerDimens.SMALL_SPACING))
            }
        }
    }
}
