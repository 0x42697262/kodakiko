package dev.kodakiko.ui.feature.capture

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import dev.kodakiko.data.db.CameraApp
import dev.kodakiko.platform.device.KeepScreenOn
import dev.kodakiko.ui.core.R
import dev.kodakiko.ui.core.TestTags
import dev.kodakiko.ui.core.TestTags.listItem
import dev.kodakiko.ui.core.components.Banner
import dev.kodakiko.ui.core.components.BannerKind
import dev.kodakiko.ui.core.icons.KodakikoIcons
import dev.kodakiko.ui.core.navigation.CaptureMode

@Composable
fun CaptureContent(state: CaptureState, onAction: (CaptureAction) -> Unit, modifier: Modifier = Modifier) {
    KeepScreenOnEffect(state.keepScreenOn)
    BackHandler {
        onAction(
            when (state.dialog) {
                is CaptureDialog.Discard -> CaptureAction.Keep
                is CaptureDialog.Leftover -> CaptureAction.CancelAdd
                null -> CaptureAction.Close
            },
        )
    }
    Box(modifier = modifier.fillMaxSize().background(Color.Black).testTag(TestTags.Capture.SCREEN)) {
        CompositionLocalProvider(LocalContentColor provides Color.White) {
            when (val dialog = state.dialog) {
                is CaptureDialog.Leftover -> LeftoverPanel(dialog.count, onAction)
                else -> {
                    CaptureLayout(state, onAction)
                    if (dialog is CaptureDialog.Discard) DiscardPanel(dialog.count, onAction)
                }
            }
        }
    }
}

@Composable
private fun KeepScreenOnEffect(on: Boolean) {
    val window = LocalActivity.current?.window
    DisposableEffect(window, on) {
        window?.let { KeepScreenOn.apply(it, on) }
        onDispose { window?.let { KeepScreenOn.apply(it, false) } }
    }
}

private fun CaptureState.cameraReady(): Boolean = cameraApp == CameraApp.KODAKIKO && permission == CameraPermission.GRANTED

@Composable
private fun CaptureLayout(state: CaptureState, onAction: (CaptureAction) -> Unit) {
    val recording = state.recording != null
    Column(modifier = Modifier.fillMaxSize().systemBarsPadding()) {
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            CameraArea(state, onAction)
            IconButton(
                onClick = { onAction(CaptureAction.Close) },
                modifier = Modifier.align(Alignment.TopStart).padding(CaptureDimens.SMALL_SPACING).testTag(TestTags.Capture.CLOSE),
            ) {
                Icon(imageVector = KodakikoIcons.Close, contentDescription = stringResource(R.string.capture_close))
            }
            state.recording?.let { Timer(it.elapsedMs, Modifier.align(Alignment.TopCenter).padding(top = CaptureDimens.SCREEN_PADDING)) }
            if (state.cameraReady()) {
                ModeSwitch(
                    mode = state.mode,
                    enabled = !recording,
                    onAction = onAction,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(CaptureDimens.SCREEN_PADDING),
                )
            }
        }
        if (state.refusals.isNotEmpty()) {
            val resources = LocalResources.current
            Banner(
                text = state.refusals.joinToString(CaptureConstants.REFUSAL_SEPARATOR) { refusalText(resources, it) },
                kind = BannerKind.WARNING,
                testTag = TestTags.Capture.REFUSALS,
                modifier = Modifier.padding(horizontal = CaptureDimens.SMALL_SPACING, vertical = CaptureDimens.TINY_SPACING),
            )
        }
        Controls(state, onAction)
        BottomBar(state, onAction)
    }
}

@Composable
private fun BoxScope.CameraArea(state: CaptureState, onAction: (CaptureAction) -> Unit) {
    Box(modifier = Modifier.matchParentSize().testTag(TestTags.Capture.CAMERA), contentAlignment = Alignment.Center) {
        when {
            state.cameraApp == CameraApp.SYSTEM -> PhoneCameraButtons(onAction)
            state.permission != CameraPermission.GRANTED -> PermissionGate(state.permission, onAction)
            else -> LocalCameraPreview.current.Content(state.facing, Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun CenteredColumn(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.padding(CaptureDimens.SCREEN_PADDING).widthIn(max = CaptureDimens.PANEL_MAX_WIDTH),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(CaptureDimens.SCREEN_PADDING),
    ) {
        content()
    }
}

@Composable
private fun PermissionGate(permission: CameraPermission, onAction: (CaptureAction) -> Unit) {
    CenteredColumn {
        Text(
            text = stringResource(R.string.capture_camera_permission_body),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.testTag(TestTags.Capture.CAMERA_PERMISSION_BODY),
        )
        if (permission == CameraPermission.BLOCKED) {
            Button(onClick = { onAction(CaptureAction.OpenSettings) }, modifier = Modifier.testTag(TestTags.Capture.OPEN_SETTINGS)) {
                Text(text = stringResource(R.string.capture_open_settings))
            }
        } else {
            Button(onClick = { onAction(CaptureAction.AllowCamera) }, modifier = Modifier.testTag(TestTags.Capture.ALLOW_CAMERA)) {
                Text(text = stringResource(R.string.capture_allow_camera))
            }
        }
    }
}

@Composable
private fun PhoneCameraButtons(onAction: (CaptureAction) -> Unit) {
    CenteredColumn {
        Button(
            onClick = { onAction(CaptureAction.PhoneCamera(video = false)) },
            modifier = Modifier.fillMaxWidth().testTag(TestTags.Capture.SYSTEM_PHOTO),
        ) {
            Icon(imageVector = CaptureIcons.Camera, contentDescription = null)
            Spacer(modifier = Modifier.size(ButtonDefaults.IconSpacing))
            Text(text = stringResource(R.string.capture_system_photo))
        }
        FilledTonalButton(
            onClick = { onAction(CaptureAction.PhoneCamera(video = true)) },
            modifier = Modifier.fillMaxWidth().testTag(TestTags.Capture.SYSTEM_VIDEO),
        ) {
            Icon(imageVector = CaptureIcons.Video, contentDescription = null)
            Spacer(modifier = Modifier.size(ButtonDefaults.IconSpacing))
            Text(text = stringResource(R.string.capture_system_video))
        }
    }
}

@Composable
private fun Timer(elapsedMs: Long, modifier: Modifier = Modifier) {
    Text(
        text = formatDuration(elapsedMs),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onError,
        modifier = modifier
            .background(MaterialTheme.colorScheme.error, CircleShape)
            .padding(horizontal = CaptureDimens.TIMER_PADDING_HORIZONTAL, vertical = CaptureDimens.TIMER_PADDING_VERTICAL)
            .testTag(TestTags.Capture.TIMER),
    )
}

@Composable
private fun ModeSwitch(mode: CaptureMode, enabled: Boolean, onAction: (CaptureAction) -> Unit, modifier: Modifier = Modifier) {
    val modes = CaptureMode.entries
    SingleChoiceSegmentedButtonRow(modifier = modifier) {
        modes.forEachIndexed { index, option ->
            SegmentedButton(
                selected = mode == option,
                onClick = { onAction(CaptureAction.SetMode(option)) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = modes.size),
                enabled = enabled,
                colors = modeColors(),
                modifier = Modifier.testTag(if (option == CaptureMode.PHOTO) TestTags.Capture.MODE_PHOTO else TestTags.Capture.MODE_VIDEO),
                label = { Text(text = stringResource(if (option == CaptureMode.PHOTO) R.string.capture_mode_photo else R.string.capture_mode_video)) },
            )
        }
    }
}

@Composable
private fun modeColors() = SegmentedButtonDefaults.colors(
    activeContainerColor = Color.White,
    activeContentColor = Color.Black,
    activeBorderColor = Color.White,
    inactiveContainerColor = Color.Black.copy(alpha = CaptureConstants.CONTROLS_SCRIM_ALPHA),
    inactiveContentColor = Color.White,
    inactiveBorderColor = Color.White,
    disabledActiveContainerColor = Color.White.copy(alpha = CaptureConstants.DISABLED_CONTENT_ALPHA),
    disabledActiveContentColor = Color.Black,
    disabledActiveBorderColor = Color.White.copy(alpha = CaptureConstants.DISABLED_CONTENT_ALPHA),
    disabledInactiveContainerColor = Color.Black.copy(alpha = CaptureConstants.CONTROLS_SCRIM_ALPHA),
    disabledInactiveContentColor = Color.White.copy(alpha = CaptureConstants.DISABLED_CONTENT_ALPHA),
    disabledInactiveBorderColor = Color.White.copy(alpha = CaptureConstants.DISABLED_CONTENT_ALPHA),
)

@Composable
private fun actionColors() = ButtonDefaults.buttonColors(
    disabledContainerColor = Color.White.copy(alpha = CaptureConstants.DISABLED_CONTAINER_ALPHA),
    disabledContentColor = Color.White.copy(alpha = CaptureConstants.DISABLED_CONTENT_ALPHA),
)

private val TileColor = Color.White.copy(alpha = CaptureConstants.TILE_ALPHA)

@Composable
private fun Controls(state: CaptureState, onAction: (CaptureAction) -> Unit) {
    val recording = state.recording != null
    val ready = state.cameraReady()
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = CaptureDimens.SCREEN_PADDING, vertical = CaptureDimens.SMALL_SPACING),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SideButton(
            onClick = { onAction(CaptureAction.PickFromGallery) },
            enabled = !recording,
            tag = TestTags.Capture.PICK_FROM_GALLERY,
        ) {
            Icon(imageVector = KodakikoIcons.PhotoLibrary, contentDescription = stringResource(R.string.capture_pick_from_gallery))
        }
        if (ready) {
            Shutter(recording = recording, mode = state.mode, onAction = onAction)
            SideButton(
                onClick = { onAction(CaptureAction.Flip) },
                enabled = !recording,
                tag = TestTags.Capture.FLIP,
            ) {
                Icon(imageVector = CaptureIcons.Flip, contentDescription = stringResource(R.string.capture_flip))
            }
        }
    }
}

@Composable
private fun SideButton(onClick: () -> Unit, enabled: Boolean, tag: String, icon: @Composable () -> Unit) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        colors = IconButtonDefaults.iconButtonColors(contentColor = Color.White, disabledContentColor = Color.White.copy(alpha = CaptureConstants.CONTROLS_SCRIM_ALPHA)),
        modifier = Modifier.size(CaptureDimens.SIDE_BUTTON).testTag(tag),
    ) {
        icon()
    }
}

@Composable
private fun Shutter(recording: Boolean, mode: CaptureMode, onAction: (CaptureAction) -> Unit) {
    val label = stringResource(R.string.capture_shutter_label)
    val currentOnAction by rememberUpdatedState(onAction)
    val recordingColor = MaterialTheme.colorScheme.error
    val innerColor = when {
        recording -> recordingColor
        mode == CaptureMode.VIDEO -> recordingColor
        else -> Color.White
    }
    Box(
        modifier = Modifier
            .size(CaptureDimens.SHUTTER)
            .testTag(TestTags.Capture.SHUTTER)
            .semantics(mergeDescendants = true) {
                contentDescription = label
                role = Role.Button
                onClick {
                    currentOnAction(CaptureAction.ShutterDown)
                    currentOnAction(CaptureAction.ShutterUp)
                    true
                }
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown()
                    currentOnAction(CaptureAction.ShutterDown)
                    try {
                        waitForUpOrCancellation()
                    } finally {
                        currentOnAction(CaptureAction.ShutterUp)
                    }
                }
            }
            .border(CaptureDimens.SHUTTER_RING, Color.White, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        val innerSize = if (recording) CaptureDimens.SHUTTER_INNER_RECORDING else CaptureDimens.SHUTTER_INNER
        val shape = if (recording) RoundedCornerShape(CaptureDimens.SHUTTER_RECORDING_CORNER) else CircleShape
        Box(modifier = Modifier.size(innerSize).background(innerColor, shape))
    }
}

@Composable
private fun BottomBar(state: CaptureState, onAction: (CaptureAction) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(CaptureDimens.SMALL_SPACING),
        horizontalArrangement = Arrangement.spacedBy(CaptureDimens.SMALL_SPACING),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Tray(state, onAction, Modifier.weight(1f))
        if (state.addToEntry) {
            Button(
                onClick = { onAction(CaptureAction.AddToEntry) },
                enabled = state.canAddToEntry && !state.adding,
                colors = actionColors(),
                modifier = Modifier.testTag(TestTags.Capture.ADD_TO_ENTRY),
            ) {
                if (state.adding) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(CaptureDimens.BUTTON_PROGRESS),
                        strokeWidth = CaptureDimens.BUTTON_PROGRESS_STROKE,
                        color = LocalContentColor.current,
                    )
                } else {
                    Icon(imageVector = CaptureIcons.Check, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                }
                Spacer(modifier = Modifier.size(ButtonDefaults.IconSpacing))
                Text(text = stringResource(R.string.capture_add_to_entry))
            }
        } else {
            Button(
                onClick = { onAction(CaptureAction.Next) },
                enabled = state.canNext,
                colors = actionColors(),
                modifier = Modifier.testTag(TestTags.Capture.NEXT),
            ) {
                Text(text = stringResource(R.string.capture_next))
                Spacer(modifier = Modifier.size(ButtonDefaults.IconSpacing))
                Icon(imageVector = CaptureIcons.ArrowForward, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
            }
        }
    }
}

@Composable
private fun Tray(state: CaptureState, onAction: (CaptureAction) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(CaptureDimens.SMALL_SPACING),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        state.items.forEachIndexed { position, item -> TrayTile(position, item, onAction) }
        repeat(state.pending) { position ->
            Box(
                modifier = Modifier.size(CaptureDimens.TRAY_TILE).clip(RoundedCornerShape(CaptureDimens.TRAY_CORNER)).background(TileColor),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(CaptureDimens.TRAY_SPINNER).testTag(listItem(TestTags.Capture.IMPORTING, position)),
                    strokeWidth = CaptureDimens.BUTTON_PROGRESS_STROKE,
                    color = Color.White,
                )
            }
        }
    }
}

@Composable
private fun TrayTile(position: Int, item: CaptureTrayItem, onAction: (CaptureAction) -> Unit) {
    Box(
        modifier = Modifier
            .size(CaptureDimens.TRAY_TILE)
            .clip(RoundedCornerShape(CaptureDimens.TRAY_CORNER))
            .background(TileColor)
            .testTag(listItem(TestTags.Capture.TRAY_ITEM, position)),
    ) {
        item.thumbnail?.let { Image(bitmap = it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(CaptureDimens.TINY_SPACING)
                .size(CaptureDimens.REMOVE_BUTTON)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surface)
                .clickable(role = Role.Button) { onAction(CaptureAction.Remove(item.mediaId)) }
                .testTag(listItem(TestTags.Capture.REMOVE_ITEM, position)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = KodakikoIcons.Close,
                contentDescription = stringResource(R.string.capture_remove_item, position + 1),
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(CaptureDimens.REMOVE_ICON),
            )
        }
    }
}

@Composable
private fun BoxScope.Panel(text: String, tag: String, buttons: @Composable () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .align(Alignment.Center)
            .padding(CaptureDimens.SCREEN_PADDING)
            .widthIn(max = CaptureDimens.PANEL_MAX_WIDTH)
            .fillMaxWidth()
            .testTag(tag),
    ) {
        Column(modifier = Modifier.padding(CaptureDimens.SCREEN_PADDING), verticalArrangement = Arrangement.spacedBy(CaptureDimens.SCREEN_PADDING)) {
            Text(text = text, style = MaterialTheme.typography.titleMedium)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(CaptureDimens.SMALL_SPACING, Alignment.End)) {
                buttons()
            }
        }
    }
}

@Composable
private fun DiscardButton(onAction: (CaptureAction) -> Unit) {
    Button(
        onClick = { onAction(CaptureAction.Discard) },
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
        modifier = Modifier.testTag(TestTags.Capture.DISCARD),
    ) {
        Text(text = stringResource(R.string.capture_discard))
    }
}

@Composable
private fun BoxScope.DiscardPanel(count: Int, onAction: (CaptureAction) -> Unit) {
    Box(
        modifier = Modifier
            .matchParentSize()
            .background(Color.Black.copy(alpha = CaptureConstants.CONTROLS_SCRIM_ALPHA))
            .clickable(interactionSource = null, indication = null) { onAction(CaptureAction.Keep) },
    )
    Panel(text = pluralStringResource(R.plurals.capture_discard_title, count, count), tag = TestTags.Capture.DISCARD_PANEL) {
        TextButton(onClick = { onAction(CaptureAction.Keep) }, modifier = Modifier.testTag(TestTags.Capture.KEEP)) {
            Text(text = stringResource(R.string.capture_keep))
        }
        DiscardButton(onAction)
    }
}

@Composable
private fun BoxScope.LeftoverPanel(count: Int, onAction: (CaptureAction) -> Unit) {
    Panel(text = pluralStringResource(R.plurals.capture_discard_in_progress, count, count), tag = TestTags.Capture.DISCARD_PANEL) {
        TextButton(onClick = { onAction(CaptureAction.CancelAdd) }, modifier = Modifier.testTag(TestTags.Capture.CANCEL_ADD)) {
            Text(text = stringResource(R.string.capture_cancel_add))
        }
        DiscardButton(onAction)
    }
}
