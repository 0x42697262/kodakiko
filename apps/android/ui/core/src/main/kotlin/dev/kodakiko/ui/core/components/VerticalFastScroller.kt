// Adapted from https://github.com/mihonapp/mihon
// Copyright 2015 Javier Tomás, 2024 Mihon Open Source Project, Apache-2.0
package dev.kodakiko.ui.core.components

import android.view.ViewConfiguration
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollIndicatorState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridLayoutInfo
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastFirstOrNull
import androidx.compose.ui.util.fastForEach
import androidx.compose.ui.util.fastMap
import androidx.compose.ui.util.fastMaxBy
import dev.kodakiko.ui.core.TestTags
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.sample
import kotlin.math.max
import kotlin.math.roundToInt

@Composable
fun VerticalFastScroller(
    listState: LazyListState,
    modifier: Modifier = Modifier,
    thumbAllowed: () -> Boolean = { true },
    thumbColor: Color = MaterialTheme.colorScheme.primary,
    topContentPadding: Dp = Dp.Hairline,
    bottomContentPadding: Dp = Dp.Hairline,
    endContentPadding: Dp = Dp.Hairline,
    content: @Composable () -> Unit,
) {
    val position = remember(listState) { ListThumbPosition(listState) }
    FastScrollerLayout(
        modifier = modifier,
        content = content,
        scroller = {
            FastScrollerThumb(
                position = position,
                state = listState,
                thumbAllowed = thumbAllowed,
                thumbColor = thumbColor,
                topContentPadding = topContentPadding,
                bottomContentPadding = bottomContentPadding,
                endContentPadding = endContentPadding,
                horizontalPadding = ComponentConstants.LIST_THUMB_HORIZONTAL_PADDING,
            )
        },
    )
}

@Composable
fun VerticalGridFastScroller(
    state: LazyGridState,
    modifier: Modifier = Modifier,
    thumbAllowed: () -> Boolean = { true },
    thumbColor: Color = MaterialTheme.colorScheme.primary,
    topContentPadding: Dp = Dp.Hairline,
    bottomContentPadding: Dp = Dp.Hairline,
    endContentPadding: Dp = Dp.Hairline,
    content: @Composable () -> Unit,
) {
    val position = remember(state) { GridThumbPosition(state) }
    FastScrollerLayout(
        modifier = modifier,
        content = content,
        scroller = {
            FastScrollerThumb(
                position = position,
                state = state,
                thumbAllowed = thumbAllowed,
                thumbColor = thumbColor,
                topContentPadding = topContentPadding,
                bottomContentPadding = bottomContentPadding,
                endContentPadding = endContentPadding,
                horizontalPadding = 0.dp,
            )
        },
    )
}

@Composable
private fun FastScrollerLayout(
    modifier: Modifier,
    content: @Composable () -> Unit,
    scroller: @Composable () -> Unit,
) {
    Layout(
        contents = listOf(content, scroller),
        modifier = modifier,
    ) { (contentMeasurables, scrollerMeasurables), constraints ->
        val contentPlaceables = contentMeasurables.fastMap { it.measure(constraints) }
        val contentHeight = contentPlaceables.fastMaxBy { it.height }?.height ?: 0
        val contentWidth = contentPlaceables.fastMaxBy { it.width }?.width ?: 0

        val scrollerConstraints = Constraints(
            maxWidth = constraints.maxWidth,
            minHeight = contentHeight,
            maxHeight = contentHeight,
        )
        val scrollerPlaceables = scrollerMeasurables.fastMap { it.measure(scrollerConstraints) }
        val scrollerWidth = scrollerPlaceables.fastMaxBy { it.width }?.width ?: 0

        layout(contentWidth, contentHeight) {
            contentPlaceables.fastForEach {
                it.place(0, 0)
            }
            scrollerPlaceables.fastForEach {
                it.placeRelative(contentWidth - scrollerWidth, 0)
            }
        }
    }
}

@OptIn(FlowPreview::class)
@Composable
private fun FastScrollerThumb(
    position: ThumbPosition,
    state: ScrollableState,
    thumbAllowed: () -> Boolean,
    thumbColor: Color,
    topContentPadding: Dp,
    bottomContentPadding: Dp,
    endContentPadding: Dp,
    horizontalPadding: Dp,
) {
    val dragInteractionSource = remember { MutableInteractionSource() }
    val isThumbDragged by dragInteractionSource.collectIsDraggedAsState()
    var dragOffsetY by remember { mutableFloatStateOf(0f) }
    val scrolled = remember {
        MutableSharedFlow<Unit>(
            extraBufferCapacity = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
    }
    LaunchedEffect(position) {
        snapshotFlow { dragOffsetY }
            .collectLatest { offsetY ->
                if (!isThumbDragged) return@collectLatest
                position.scrollTo(position.proportionAt(offsetY))
                scrolled.tryEmit(Unit)
            }
    }
    val alpha = remember { Animatable(0f) }
    val isThumbVisible by remember { derivedStateOf { alpha.value > 0f } }
    LaunchedEffect(scrolled, alpha) {
        scrolled
            .sample(ComponentConstants.SCROLL_REVEAL_SAMPLE_PERIOD)
            .collectLatest {
                if (thumbAllowed()) {
                    alpha.snapTo(1f)
                    delay(ScrollBarVisibilityDuration)
                    alpha.animateTo(0f, animationSpec = ImmediateFadeOutAnimationSpec)
                } else {
                    alpha.animateTo(0f, animationSpec = ImmediateFadeOutAnimationSpec)
                }
            }
    }

    Box(
        modifier = Modifier
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
                layout(placeable.width, constraints.maxHeight) {
                    val visible = position.update(
                        contentHeight = constraints.maxHeight,
                        topPadding = topContentPadding.toPx(),
                        bottomPadding = bottomContentPadding.toPx(),
                        thumbHeight = ThumbLength.toPx(),
                        isThumbDragged = isThumbDragged,
                    )
                    if (!visible) return@layout
                    if (position.reveal) scrolled.tryEmit(Unit)
                    val offsetY = if (isThumbDragged) dragOffsetY else position.thumbOffsetY
                    placeable.place(0, offsetY.roundToInt())
                }
            }
            .testTag(TestTags.FastScroller.THUMB)
            .then(
                if (isThumbVisible && !state.isScrollInProgress) {
                    Modifier.draggable(
                        interactionSource = dragInteractionSource,
                        orientation = Orientation.Vertical,
                        onDragStarted = { dragOffsetY = position.thumbOffsetY },
                        state = rememberDraggableState { delta ->
                            dragOffsetY = (dragOffsetY + delta).coerceIn(
                                position.trackTop,
                                position.trackTop + position.trackHeight,
                            )
                        },
                    )
                } else {
                    Modifier
                },
            )
            .then(
                if (isThumbVisible && !isThumbDragged && !state.isScrollInProgress) {
                    Modifier.systemGestureExclusion()
                } else {
                    Modifier
                },
            )
            .height(ThumbLength)
            .padding(horizontal = horizontalPadding)
            .padding(end = endContentPadding)
            .width(ThumbThickness)
            .graphicsLayer { this.alpha = alpha.value }
            .background(color = thumbColor, shape = ThumbShape),
    )
}

private abstract class ThumbPosition {
    var trackTop = 0f
        private set
    var trackHeight = 0f
        private set
    var thumbOffsetY = 0f
        private set
    var reveal = false
        private set
    protected var scrollRange = 0
        private set
    private var lastScrollOffset = -1

    protected abstract val indicator: ScrollIndicatorState?
    protected abstract val afterContentPadding: Int
    protected abstract val isScrollInProgress: Boolean

    protected abstract suspend fun scrollToOffset(offset: Int)

    fun update(
        contentHeight: Int,
        topPadding: Float,
        bottomPadding: Float,
        thumbHeight: Float,
        isThumbDragged: Boolean,
    ): Boolean {
        reveal = false
        val indicator = indicator ?: return false
        val contentSize = indicator.contentSize
        val viewportSize = indicator.viewportSize
        if (contentSize == Int.MAX_VALUE || viewportSize == Int.MAX_VALUE || contentSize <= viewportSize) {
            return false
        }
        val scrollOffset = indicator.scrollOffset
        if (scrollOffset == Int.MAX_VALUE) return false

        scrollRange = contentSize - viewportSize
        trackTop = topPadding
        trackHeight = (contentHeight - topPadding - bottomPadding - afterContentPadding - thumbHeight)
            .coerceAtLeast(0f)
        if (!isThumbDragged) {
            thumbOffsetY = trackTop + trackHeight * (scrollOffset.toFloat() / scrollRange).coerceIn(0f, 1f)
            reveal = scrollOffset != lastScrollOffset && isScrollInProgress
        }
        lastScrollOffset = scrollOffset
        return true
    }

    fun proportionAt(offsetY: Float): Float {
        if (trackHeight == 0f) return 0f
        return ((offsetY - trackTop) / trackHeight).coerceIn(0f, 1f)
    }

    suspend fun scrollTo(proportion: Float) = scrollToOffset((proportion * scrollRange).roundToInt())
}

private class ListThumbPosition(private val state: LazyListState) : ThumbPosition() {
    override val indicator = MeasuredListScrollIndicatorState(state)
    override val afterContentPadding get() = state.layoutInfo.afterContentPadding
    override val isScrollInProgress get() = state.isScrollInProgress

    override suspend fun scrollToOffset(offset: Int) {
        if (state.layoutInfo.totalItemsCount == 0) return
        val startOffset = if (state.layoutInfo.reverseLayout) scrollRange - offset else offset
        val (index, itemOffset) = indicator.itemAt(startOffset)
        state.scrollToItem(index = index, scrollOffset = itemOffset)
    }
}

private class MeasuredListScrollIndicatorState(private val state: LazyListState) : ScrollIndicatorState {
    private var sizes = IntArray(0)
    private var types = arrayOfNulls<Any>(0)
    private val typeSizes = HashMap<Any?, IntArray>()
    private var measuredSum = 0L
    private var measuredCount = 0
    private var estimatedSize = 0

    private var lastLayoutInfo: LazyListLayoutInfo? = null
    private var _scrollOffset = 0
    private var _contentSize = 0
    private var _viewportSize = 0

    override val scrollOffset: Int get() = sync().let { _scrollOffset }
    override val contentSize: Int get() = sync().let { _contentSize }
    override val viewportSize: Int get() = sync().let { _viewportSize }

    fun itemAt(offset: Int): Pair<Int, Int> {
        sync()
        var start = 0
        for (i in sizes.indices) {
            val size = sizeAt(i)
            if (start + size > offset || i == sizes.lastIndex) return i to (offset - start).coerceAtLeast(0)
            start += size
        }
        return 0 to 0
    }

    private fun sizeAt(index: Int) = sizes[index].takeIf { it != 0 } ?: estimatedSize

    private fun sync() {
        val layoutInfo = state.layoutInfo
        if (layoutInfo === lastLayoutInfo) return
        lastLayoutInfo = layoutInfo

        resize(layoutInfo.totalItemsCount)
        val spacing = layoutInfo.mainAxisItemSpacing
        layoutInfo.visibleItemsInfo.fastForEach { record(it.index, it.size + spacing, it.contentType) }
        estimatedSize = typeSizes.values.maxByOrNull { it[1] }?.takeIf { it[1] > 1 }?.let { it[0] / it[1] } ?: 0

        _viewportSize = layoutInfo.viewportSize.height
        val first = layoutInfo.visibleItemsInfo.fastFirstOrNull { !it.isStickyHeader }
            ?: layoutInfo.visibleItemsInfo.firstOrNull()
        if (first == null || (estimatedSize == 0 && measuredCount < sizes.size)) {
            _contentSize = 0
            _scrollOffset = 0
            return
        }
        val itemsSize = measuredSum + (sizes.size - measuredCount).toLong() * estimatedSize - spacing
        _contentSize = (layoutInfo.beforeContentPadding + itemsSize + layoutInfo.afterContentPadding)
            .coerceAtMost(Int.MAX_VALUE - 1L)
            .toInt()
        var start = 0
        for (i in 0..<first.index) start += sizeAt(i)
        val offset = start - first.offset
        _scrollOffset = if (layoutInfo.reverseLayout) _contentSize - _viewportSize - offset else offset
    }

    private fun resize(count: Int) {
        if (count == sizes.size) return
        for (i in count..<sizes.size) forget(i)
        sizes = sizes.copyOf(count)
        types = types.copyOf(count)
    }

    private fun record(index: Int, size: Int, type: Any?) {
        if (index !in sizes.indices || (sizes[index] == size && types[index] == type)) return
        forget(index)
        sizes[index] = size
        types[index] = type
        typeSizes.getOrPut(type) { IntArray(ComponentConstants.TYPE_SIZE_SLOTS) }.let {
            it[0] += size
            it[1]++
        }
        measuredSum += size
        measuredCount++
    }

    private fun forget(index: Int) {
        val size = sizes[index]
        if (size == 0) return
        typeSizes[types[index]]?.let {
            it[0] -= size
            it[1]--
        }
        measuredSum -= size
        measuredCount--
        sizes[index] = 0
    }
}

private val LazyListItemInfo.isStickyHeader: Boolean
    get() = (key as? String)?.startsWith(Scroller.STICKY_HEADER_KEY_PREFIX) == true
private class GridThumbPosition(private val state: LazyGridState) : ThumbPosition() {
    override val indicator get() = state.scrollIndicatorState
    override val afterContentPadding get() = state.layoutInfo.afterContentPadding
    override val isScrollInProgress get() = state.isScrollInProgress
    override suspend fun scrollToOffset(offset: Int) {
        val layoutInfo = state.layoutInfo
        if (layoutInfo.totalItemsCount == 0) return
        val reference = layoutInfo.visibleItemsInfo.fastFirstOrNull { it.row != -1 } ?: return
        val lineSize = layoutInfo.averageLineSize()
        if (lineSize <= 0) return
        val startOffset = if (layoutInfo.reverseLayout) scrollRange - offset else offset
        val line = startOffset / lineSize
        val index = (reference.index + (line - reference.row) * layoutInfo.maxSpan)
            .coerceIn(0, layoutInfo.totalItemsCount - 1)
        state.scrollToItem(index = index, scrollOffset = startOffset - line * lineSize)
    }
}

private fun LazyGridLayoutInfo.averageLineSize(): Int {
    var totalSize = 0
    var lines = 0
    var row = -1
    var rowSize = 0
    visibleItemsInfo.fastForEach { item ->
        if (item.row == -1) return@fastForEach
        if (item.row != row) {
            if (row != -1) {
                totalSize += rowSize
                lines++
            }
            row = item.row
            rowSize = 0
        }
        rowSize = max(rowSize, item.size.height)
    }
    if (row != -1) {
        totalSize += rowSize
        lines++
    }
    return if (lines == 0) 0 else totalSize / lines + mainAxisItemSpacing
}

object Scroller {
    const val STICKY_HEADER_KEY_PREFIX = ComponentConstants.STICKY_HEADER_KEY_PREFIX
}

private val ThumbLength = ComponentConstants.THUMB_LENGTH
private val ThumbThickness = ComponentConstants.THUMB_THICKNESS
private val ThumbShape = RoundedCornerShape(ComponentConstants.THUMB_CORNER)
private val ScrollBarVisibilityDuration = ComponentConstants.SCROLL_BAR_VISIBILITY_DURATION
private val ImmediateFadeOutAnimationSpec = tween<Float>(
    durationMillis = ViewConfiguration.getScrollBarFadeDuration(),
)
