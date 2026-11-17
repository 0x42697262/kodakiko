package dev.kodakiko.ui.feature.capture

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource

internal object CaptureIcons {
    val ArrowForward: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.capture_ic_arrow_forward)
    val Camera: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.capture_ic_photo_camera)
    val Check: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.capture_ic_check)
    val Flip: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.capture_ic_flip_camera)
    val Video: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.capture_ic_videocam)
}
