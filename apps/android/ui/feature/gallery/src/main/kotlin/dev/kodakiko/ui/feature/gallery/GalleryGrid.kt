// Adapted from https://github.com/mihonapp/mihon
// Copyright 2015 Javier Tomás, 2024 Mihon Open Source Project, Apache-2.0
package dev.kodakiko.ui.feature.gallery

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.kodakiko.data.db.GalleryDisplayMode
import dev.kodakiko.index.GALLERY_COLUMNS_AUTO
import dev.kodakiko.index.gridColumns
import dev.kodakiko.ui.core.TestTags
import dev.kodakiko.ui.core.TestTags.listItem
import dev.kodakiko.ui.core.components.ListGroupHeader
import dev.kodakiko.ui.core.components.VerticalGridFastScroller
import dev.kodakiko.ui.core.format.formatGroupHeader

@Composable
internal fun GalleryGrid(
    grid: GalleryGrid,
    onAction: (GalleryAction) -> Unit,
    modifier: Modifier = Modifier,
    reportScroll: Boolean = false,
    bottomPadding: Dp = 0.dp,
    header: (LazyGridScope.() -> Unit)? = null,
    footer: (@Composable () -> Unit)? = null,
) {
    val state = rememberLazyGridState()
    val density = LocalDensity.current
    val currentOnAction by rememberUpdatedState(onAction)
    val connection = remember(state, density) { ScrollReporter(state, density) { currentOnAction(GalleryAction.Scrolled(it)) } }
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val display = grid.display
        val width = (maxWidth - GalleryDimens.GRID_PADDING - GalleryDimens.GRID_PADDING).value.toDouble()
        val columns = if (display.mode == GalleryDisplayMode.LIST) {
            GalleryConstants.LIST_COLUMNS
        } else {
            val chosen = if (landscape) display.columnsLandscape else display.columnsPortrait
            if (chosen == GALLERY_COLUMNS_AUTO) gridColumns(width) else chosen
        }
        VerticalGridFastScroller(state = state, modifier = Modifier.fillMaxSize()) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                state = state,
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (reportScroll) Modifier.nestedScroll(connection) else Modifier),
                contentPadding = PaddingValues(
                    start = GalleryDimens.GRID_PADDING,
                    top = GalleryDimens.GRID_PADDING,
                    end = GalleryDimens.GRID_PADDING,
                    bottom = GalleryDimens.GRID_PADDING + bottomPadding,
                ),
                horizontalArrangement = Arrangement.spacedBy(GalleryDimens.TILE_GAP),
                verticalArrangement = Arrangement.spacedBy(GalleryDimens.TILE_GAP),
            ) {
                header?.invoke(this)
                gridSections(grid, onAction)
                if (footer != null) {
                    item(key = GalleryConstants.KEY_FOOTER, span = { GridItemSpan(maxLineSpan) }) { footer() }
                }
            }
        }
    }
}

private fun LazyGridScope.gridSections(grid: GalleryGrid, onAction: (GalleryAction) -> Unit) {
    val selected = grid.selection.ids.toSet()
    grid.sections.forEachIndexed { sectionIndex, section ->
        if (section.key.isNotEmpty()) {
            item(key = GalleryConstants.KEY_HEADER + sectionIndex, span = { GridItemSpan(maxLineSpan) }) {
                ListGroupHeader(
                    text = formatGroupHeader(LocalResources.current, section.key, grid.today),
                    modifier = Modifier.testTag(listItem(TestTags.Gallery.DAY_HEADER, sectionIndex)),
                )
            }
        }
        items(section.tiles, key = { GalleryConstants.KEY_TILE + it.position }) { tile ->
            GalleryTileItem(
                tile = tile,
                display = grid.display.mode,
                badges = grid.display.badges,
                selecting = grid.selection.active,
                selected = tile.entryId in selected,
                onAction = onAction,
            )
        }
    }
}

private class ScrollReporter(
    private val state: LazyGridState,
    private val density: Density,
    private val report: (Float) -> Unit,
) : NestedScrollConnection {
    private var total = 0f

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
        if (consumed.y == 0f) return Offset.Zero
        total -= consumed.y
        if (state.firstVisibleItemIndex == 0 && state.firstVisibleItemScrollOffset == 0) total = 0f
        report(with(density) { total.toDp().value })
        return Offset.Zero
    }
}
