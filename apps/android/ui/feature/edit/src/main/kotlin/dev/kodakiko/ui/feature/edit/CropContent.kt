package dev.kodakiko.ui.feature.edit

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import dev.kodakiko.service.entries.CropCorner
import dev.kodakiko.service.entries.CropRect
import dev.kodakiko.service.entries.dragCorner
import dev.kodakiko.service.entries.moveCrop
import dev.kodakiko.ui.core.R
import dev.kodakiko.ui.core.TestTags
import dev.kodakiko.ui.core.components.Banner
import dev.kodakiko.ui.core.components.BannerKind
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt

@Composable
fun CropContent(state: CropState, onAction: (CropAction) -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.systemBars)
            .padding(EditDimens.SCREEN_PADDING)
            .testTag(TestTags.Crop.SHEET),
        verticalArrangement = Arrangement.spacedBy(EditDimens.SECTION_SPACING),
    ) {
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            state.image?.let { CropFrame(it, state.rect, onAction) }
        }
        if (state.failed) Banner(text = stringResource(R.string.crop_failed), kind = BannerKind.ERROR, testTag = TestTags.Crop.FAILED)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(EditDimens.SECTION_SPACING, Alignment.End)) {
            OutlinedButton(onClick = { onAction(CropAction.Cancel) }, enabled = !state.busy, modifier = Modifier.testTag(TestTags.Crop.CANCEL)) {
                Text(text = stringResource(R.string.crop_cancel))
            }
            Button(onClick = { onAction(CropAction.Apply) }, enabled = !state.busy && state.image != null, modifier = Modifier.testTag(TestTags.Crop.APPLY)) {
                if (state.busy) {
                    CircularProgressIndicator(
                        color = LocalContentColor.current,
                        strokeWidth = EditDimens.SPINNER_STROKE,
                        modifier = Modifier.padding(end = EditDimens.SMALL_SPACING).size(EditDimens.SPINNER),
                    )
                }
                Text(text = stringResource(R.string.crop_apply))
            }
        }
    }
}

private sealed interface CropTarget {
    data class Corner(val corner: CropCorner) : CropTarget

    data object Move : CropTarget
}

private fun cornerPoint(rect: CropRect, corner: CropCorner): Pair<Double, Double> = when (corner) {
    CropCorner.TOP_LEFT -> rect.x to rect.y
    CropCorner.TOP_RIGHT -> rect.x + rect.width to rect.y
    CropCorner.BOTTOM_LEFT -> rect.x to rect.y + rect.height
    CropCorner.BOTTOM_RIGHT -> rect.x + rect.width to rect.y + rect.height
}

private fun cornerTag(corner: CropCorner): String = when (corner) {
    CropCorner.TOP_LEFT -> TestTags.Crop.TOP_LEFT
    CropCorner.TOP_RIGHT -> TestTags.Crop.TOP_RIGHT
    CropCorner.BOTTOM_LEFT -> TestTags.Crop.BOTTOM_LEFT
    CropCorner.BOTTOM_RIGHT -> TestTags.Crop.BOTTOM_RIGHT
}

private fun Rect.at(x: Double, y: Double): Offset = Offset(left + (x * width).toFloat(), top + (y * height).toFloat())

private fun targetAt(position: Offset, rect: CropRect, frame: Rect, reach: Float): CropTarget? {
    val nearest = CropCorner.entries
        .map { corner ->
            val (x, y) = cornerPoint(rect, corner)
            val point = frame.at(x, y)
            corner to hypot(position.x - point.x, position.y - point.y)
        }
        .filter { it.second <= reach }
        .minByOrNull { it.second }
    if (nearest != null) return CropTarget.Corner(nearest.first)
    val area = Rect(frame.at(rect.x, rect.y), frame.at(rect.x + rect.width, rect.y + rect.height))
    return if (area.contains(position)) CropTarget.Move else null
}

private fun dragged(start: CropRect, target: CropTarget, dx: Double, dy: Double): CropRect = when (target) {
    CropTarget.Move -> moveCrop(start, dx, dy)
    is CropTarget.Corner -> {
        val (x, y) = cornerPoint(start, target.corner)
        dragCorner(start, target.corner, x + dx, y + dy)
    }
}

private suspend fun PointerInputScope.cropGestures(frame: Rect, reach: Float, rect: () -> CropRect, onChange: (CropRect) -> Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val start = rect()
        val target = targetAt(down.position, start, frame, reach) ?: return@awaitEachGesture
        down.consume()
        while (true) {
            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
            if (!change.pressed) break
            val delta = change.position - down.position
            onChange(dragged(start, target, (delta.x / frame.width).toDouble(), (delta.y / frame.height).toDouble()))
            change.consume()
        }
    }
}

@Composable
private fun CropFrame(image: CropImage, rect: CropRect, onAction: (CropAction) -> Unit) {
    val density = LocalDensity.current
    val currentRect by rememberUpdatedState(rect)
    val currentOnAction by rememberUpdatedState(onAction)
    val shade = Color.Black.copy(alpha = EditConstants.SHADE_ALPHA)
    val line = MaterialTheme.colorScheme.onPrimary
    val handleColor = MaterialTheme.colorScheme.primary
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val margin = with(density) { EditDimens.CROP_MARGIN.toPx() }
        val boxWidth = constraints.maxWidth.toFloat()
        val boxHeight = constraints.maxHeight.toFloat()
        val scale = min((boxWidth - margin * EditConstants.HALF) / image.width, (boxHeight - margin * EditConstants.HALF) / image.height)
        val frameSize = Size(image.width * scale, image.height * scale)
        val frame = Rect(Offset((boxWidth - frameSize.width) / EditConstants.HALF, (boxHeight - frameSize.height) / EditConstants.HALF), frameSize)
        val reach = with(density) { EditDimens.HANDLE_TARGET.toPx() } / EditConstants.HALF
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(frame, reach) { cropGestures(frame, reach, { currentRect }) { currentOnAction(CropAction.RectChanged(it)) } },
        ) {
            val area = Rect(frame.at(rect.x, rect.y), frame.at(rect.x + rect.width, rect.y + rect.height))
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawImage(
                    image = image.bitmap,
                    dstOffset = IntOffset(frame.left.roundToInt(), frame.top.roundToInt()),
                    dstSize = IntSize(frame.width.roundToInt(), frame.height.roundToInt()),
                )
                drawRect(shade, Offset(frame.left, frame.top), Size(frame.width, area.top - frame.top))
                drawRect(shade, Offset(frame.left, area.bottom), Size(frame.width, frame.bottom - area.bottom))
                drawRect(shade, Offset(frame.left, area.top), Size(area.left - frame.left, area.height))
                drawRect(shade, Offset(area.right, area.top), Size(frame.right - area.right, area.height))
                drawRect(line, area.topLeft, area.size, style = Stroke(width = EditDimens.CROP_LINE.toPx()))
            }
            Box(
                modifier = Modifier
                    .offset { IntOffset(area.left.roundToInt(), area.top.roundToInt()) }
                    .size(with(density) { area.width.toDp() }, with(density) { area.height.toDp() })
                    .testTag(TestTags.Crop.AREA),
            )
            CropCorner.entries.forEach { corner ->
                val (x, y) = cornerPoint(rect, corner)
                Handle(frame.at(x, y), corner, handleColor, EditDimens.HANDLE)
            }
        }
    }
}

@Composable
private fun Handle(center: Offset, corner: CropCorner, color: Color, size: Dp) {
    val density = LocalDensity.current
    val half = with(density) { size.toPx() } / EditConstants.HALF
    val label = stringResource(R.string.crop_handle, cornerName(corner))
    Box(
        modifier = Modifier
            .offset { IntOffset((center.x - half).roundToInt(), (center.y - half).roundToInt()) }
            .size(size)
            .clip(CircleShape)
            .background(color)
            .semantics { contentDescription = label }
            .testTag(cornerTag(corner)),
    )
}

private fun cornerName(corner: CropCorner): String = cornerTag(corner).substringAfter(EditConstants.TAG_OPTION_SEPARATOR)
