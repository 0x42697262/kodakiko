package dev.kodakiko.ui.feature.gallery

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource

internal object GalleryIcons {
    val Camera: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.gallery_ic_photo_camera)
    val Delete: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.gallery_ic_delete)
    val DeleteForever: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.gallery_ic_delete_forever)
    val Favorite: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.gallery_ic_favorite)
    val FavoriteBorder: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.gallery_ic_favorite_border)
    val FilterList: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.gallery_ic_filter_list)
    val FlipToBack: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.gallery_ic_flip_to_back)
    val Image: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.gallery_ic_image)
    val Label: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.gallery_ic_label)
    val LabelOff: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.gallery_ic_label_off)
    val Notes: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.gallery_ic_notes)
    val Pending: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.gallery_ic_cloud_upload)
    val Restore: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.gallery_ic_restore_from_trash)
    val SelectAll: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.gallery_ic_select_all)
    val Share: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.gallery_ic_share)
    val Shuffle: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.gallery_ic_shuffle)
    val Video: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.gallery_ic_videocam)
}
