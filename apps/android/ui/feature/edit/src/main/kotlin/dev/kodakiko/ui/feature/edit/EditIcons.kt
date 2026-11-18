package dev.kodakiko.ui.feature.edit

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import dev.kodakiko.ui.core.R as CoreR

internal object EditIcons {
    val Add: ImageVector @Composable get() = ImageVector.vectorResource(CoreR.drawable.ic_edit_add)
    val Tune: ImageVector @Composable get() = ImageVector.vectorResource(CoreR.drawable.ic_edit_tune)
    val Visibility: ImageVector @Composable get() = ImageVector.vectorResource(CoreR.drawable.ic_edit_visibility)
    val AddPhoto: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.edit_ic_add_photo)
    val Bold: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.edit_ic_format_bold)
    val Bullet: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.edit_ic_format_list_bulleted)
    val Camera: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.edit_ic_photo_camera)
    val Caption: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.edit_ic_closed_caption)
    val Check: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.edit_ic_check)
    val Crop: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.edit_ic_crop)
    val Delete: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.edit_ic_delete)
    val Download: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.edit_ic_download)
    val Edit: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.edit_ic_edit)
    val Favorite: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.edit_ic_favorite)
    val FavoriteBorder: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.edit_ic_favorite_border)
    val Heading: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.edit_ic_title)
    val Italic: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.edit_ic_format_italic)
    val Label: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.edit_ic_label)
    val Link: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.edit_ic_link)
    val LocationOff: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.edit_ic_location_off)
    val Map: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.edit_ic_map)
    val Mic: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.edit_ic_mic)
    val Paste: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.edit_ic_content_paste)
    val Pause: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.edit_ic_pause)
    val Play: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.edit_ic_play_arrow)
    val Quote: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.edit_ic_format_quote)
    val Schedule: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.edit_ic_schedule)
    val Share: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.edit_ic_share)
    val Star: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.edit_ic_star)
    val Stop: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.edit_ic_stop)
    val Strike: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.edit_ic_strikethrough)
    val Video: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.edit_ic_videocam)
}
