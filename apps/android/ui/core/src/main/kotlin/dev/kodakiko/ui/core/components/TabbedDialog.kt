// Adapted from https://github.com/mihonapp/mihon
// Copyright 2015 Javier Tomás, 2024 Mihon Open Source Project, Apache-2.0
package dev.kodakiko.ui.core.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import dev.kodakiko.ui.core.R
import dev.kodakiko.ui.core.components.material.TabText
import dev.kodakiko.ui.core.icons.KodakikoIcons
import dev.kodakiko.ui.core.util.optionalTestTag
import kotlinx.coroutines.launch

@Immutable
data class TabbedDialogTab(
    val title: String,
    val testTag: String? = null,
)

@Composable
fun TabbedDialog(
    onDismissRequest: () -> Unit,
    tabs: List<TabbedDialogTab>,
    modifier: Modifier = Modifier,
    tabOverflowMenuContent: (@Composable ColumnScope.(() -> Unit) -> Unit)? = null,
    pagerState: PagerState = rememberPagerState { tabs.size },
    content: @Composable (Int) -> Unit,
) {
    AdaptiveSheet(
        modifier = modifier,
        onDismissRequest = onDismissRequest,
    ) {
        val scope = rememberCoroutineScope()

        Column {
            Row {
                PrimaryTabRow(
                    modifier = Modifier.weight(1f),
                    selectedTabIndex = pagerState.currentPage,
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    tabs.forEachIndexed { index, tab ->
                        Tab(
                            selected = pagerState.currentPage == index,
                            onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                            text = { TabText(text = tab.title) },
                            modifier = Modifier.optionalTestTag(tab.testTag),
                            unselectedContentColor = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }

                tabOverflowMenuContent?.let { MoreMenu(it) }
            }

            HorizontalPager(
                modifier = Modifier.animateContentSize(),
                state = pagerState,
                verticalAlignment = Alignment.Top,
                pageContent = { page ->
                    Box(
                        modifier = if (page == pagerState.settledPage) Modifier else Modifier.clearAndSetSemantics {},
                    ) {
                        content(page)
                    }
                },
            )
        }
    }
}

@Composable
private fun MoreMenu(
    content: @Composable ColumnScope.(() -> Unit) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = Modifier.wrapContentSize(Alignment.TopStart)) {
        IconButton(onClick = { expanded = true }) {
            Icon(
                imageVector = KodakikoIcons.MoreVert,
                contentDescription = stringResource(R.string.gallery_more),
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            content { expanded = false }
        }
    }
}
