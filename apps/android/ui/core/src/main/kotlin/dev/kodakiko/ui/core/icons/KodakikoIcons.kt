package dev.kodakiko.ui.core.icons

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import dev.kodakiko.ui.core.R

object KodakikoIcons {
    val ArrowBack: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_arrow_back)
    val ArrowDownward: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_arrow_downward)
    val ArrowUpward: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_arrow_upward)
    val CheckBox: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_check_box)
    val CheckBoxOutlineBlank: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_check_box_outline_blank)
    val Close: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_close)
    val DisabledByDefault: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_disabled_by_default)
    val MoreVert: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_more_vert)
    val Search: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_search)
}
