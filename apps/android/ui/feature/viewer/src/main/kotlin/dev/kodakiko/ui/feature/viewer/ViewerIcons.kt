package dev.kodakiko.ui.feature.viewer

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource

internal object ViewerIcons {
    val AddPhoto: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.viewer_ic_add_photo)
    val Delete: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.viewer_ic_delete)
    val DeleteForever: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.viewer_ic_delete_forever)
    val Edit: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.viewer_ic_edit)
    val Favorite: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.viewer_ic_favorite)
    val FavoriteBorder: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.viewer_ic_favorite_border)
    val Map: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.viewer_ic_map)
    val OfflinePin: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.viewer_ic_offline_pin)
    val Pause: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.viewer_ic_pause)
    val Play: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.viewer_ic_play_arrow)
    val Restore: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.viewer_ic_restore_from_trash)
    val Share: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.viewer_ic_share)
    val VolumeOff: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.viewer_ic_volume_off)
    val VolumeUp: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.viewer_ic_volume_up)
}
