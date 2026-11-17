package dev.kodakiko.ui.feature.sharing

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.Role
import dev.kodakiko.ui.core.TestTags
import dev.kodakiko.ui.core.components.Banner
import dev.kodakiko.ui.core.components.BannerKind
import dev.kodakiko.ui.core.components.material.DISABLED_ALPHA
import dev.kodakiko.ui.core.components.material.padding
import androidx.compose.foundation.Image as ComposeImage

internal object SharingIcons {
    val Link: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.sharing_ic_link)
    val Check: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.sharing_ic_check)
}

@Composable
internal fun SheetColumn(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(SharingDimens.SHEET_PADDING),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
        content = content,
    )
}

@Composable
internal fun SharingError(message: SharingMessage?, modifier: Modifier = Modifier) {
    if (message == null) return
    val text = sharingMessageText(LocalContext.current.resources, message)
    Banner(text = text, kind = BannerKind.ERROR, modifier = modifier, testTag = TestTags.Shared.ERROR)
}

@Composable
internal fun ActionRow(
    icon: ImageVector?,
    label: String,
    testTag: String,
    enabled: Boolean,
    color: Color = MaterialTheme.colorScheme.onSurface,
    onClick: () -> Unit,
) {
    val tint = if (enabled) color else color.copy(alpha = DISABLED_ALPHA)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(testTag)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(vertical = MaterialTheme.padding.medium),
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.large),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) Icon(imageVector = icon, contentDescription = null, tint = tint)
        Text(text = label, color = tint, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
internal fun Thumbnail(entryId: String, mediaId: String?, modifier: Modifier = Modifier) {
    val thumbnails = LocalSharingThumbnails.current
    var bitmap by remember(entryId, mediaId) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(thumbnails, entryId, mediaId) {
        if (mediaId != null) bitmap = attempt { thumbnails.load(entryId, mediaId) }.getOrNull()
    }
    Box(modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant)) {
        val image = bitmap
        if (image != null) {
            ComposeImage(bitmap = image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
internal fun DangerButton(text: String, onClick: () -> Unit, testTag: String, busy: Boolean, modifier: Modifier = Modifier) {
    Button(
        onClick = onClick,
        enabled = !busy,
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
        modifier = modifier.fillMaxWidth().testTag(testTag),
    ) {
        if (busy) {
            CircularProgressIndicator(modifier = Modifier.size(SharingDimens.PROGRESS_SIZE), color = LocalContentColor.current, strokeWidth = SharingDimens.PROGRESS_STROKE)
            Spacer(Modifier.width(MaterialTheme.padding.small))
        }
        Text(text = text)
    }
}
