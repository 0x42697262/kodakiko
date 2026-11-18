package dev.kodakiko.ui.feature.tags

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource

internal object TagsIcons {
    val Add: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.tags_ic_add)
    val Delete: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.tags_ic_delete)
    val DragHandle: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.tags_ic_drag_handle)
    val Edit: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.tags_ic_edit)
}
