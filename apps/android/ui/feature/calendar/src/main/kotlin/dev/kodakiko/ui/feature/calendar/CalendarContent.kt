@file:OptIn(ExperimentalMaterial3Api::class)

package dev.kodakiko.ui.feature.calendar

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import dev.kodakiko.domain.CalendarCell
import dev.kodakiko.domain.addMonths
import dev.kodakiko.domain.monthGrid
import dev.kodakiko.domain.yearMonthOf
import dev.kodakiko.ui.core.TestTags
import dev.kodakiko.ui.core.TestTags.listItem
import dev.kodakiko.ui.core.components.AppBar
import dev.kodakiko.ui.core.components.material.PullRefresh
import dev.kodakiko.ui.core.components.material.Scaffold
import kotlinx.coroutines.CancellationException
import dev.kodakiko.ui.core.R as CoreR

@Composable
fun CalendarContent(state: CalendarState, onAction: (CalendarAction) -> Unit, modifier: Modifier = Modifier) {
    Scaffold(
        modifier = modifier.testTag(TestTags.Calendar.SCREEN),
        topBar = {
            AppBar(
                titleContent = {
                    Text(
                        text = CalendarLabels.monthTitle(state.month),
                        maxLines = 1,
                        modifier = Modifier.testTag(TestTags.Calendar.MONTH_TITLE).semantics { heading() },
                    )
                },
                actions = {
                    IconButton(onClick = { onAction(CalendarAction.Previous) }, modifier = Modifier.testTag(TestTags.Calendar.PREVIOUS)) {
                        Icon(imageVector = CalendarIcons.ChevronLeft, contentDescription = stringResource(CoreR.string.calendar_previous))
                    }
                    IconButton(
                        onClick = { onAction(CalendarAction.Next) },
                        enabled = state.canGoNext,
                        modifier = Modifier.testTag(TestTags.Calendar.NEXT),
                    ) {
                        Icon(imageVector = CalendarIcons.ChevronRight, contentDescription = stringResource(CoreR.string.calendar_next))
                    }
                },
            )
        },
    ) { padding ->
        PullRefresh(
            refreshing = state.refreshing,
            enabled = state.pullToSync,
            onRefresh = { onAction(CalendarAction.PullToSync) },
            modifier = Modifier.padding(padding).testTag(TestTags.PULL_TO_SYNC),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = CalendarDimens.CONTENT_PADDING),
                verticalArrangement = Arrangement.spacedBy(CalendarDimens.CONTENT_PADDING),
            ) {
                CalendarButtons(onAction)
                MonthPager(state, onAction)
            }
        }
    }
    state.jump?.let { JumpSheet(it, onAction) }
}

@Composable
private fun CalendarButtons(onAction: (CalendarAction) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(CalendarDimens.BUTTON_SPACING, Alignment.CenterHorizontally),
    ) {
        CalendarButton(CalendarIcons.Today, stringResource(CoreR.string.calendar_today), TestTags.Calendar.TODAY) { onAction(CalendarAction.Today) }
        CalendarButton(CalendarIcons.Event, stringResource(CoreR.string.calendar_jump), TestTags.Calendar.JUMP) { onAction(CalendarAction.OpenJump) }
    }
}

@Composable
private fun CalendarButton(icon: ImageVector, label: String, tag: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.testTag(tag)) {
        Icon(imageVector = icon, contentDescription = null)
        Text(text = label, modifier = Modifier.padding(start = CalendarDimens.BUTTON_SPACING))
    }
}

@Composable
private fun MonthPager(state: CalendarState, onAction: (CalendarAction) -> Unit) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val gaps = CalendarDimens.CELL_GAP * (CalendarConstants.DAYS_PER_WEEK - 1)
        val cellSize = (maxWidth - gaps) / CalendarConstants.DAYS_PER_WEEK
        val pageHeight = (cellSize + CalendarDimens.CELL_GAP) * CalendarConstants.WEEKS_PER_PAGE
        val pages = remember(state.months, state.month, state.today) { pagerPages(state.months + state.month, yearMonthOf(state.today)) }
        val target = pages.indexOf(state.month)
        val pagerState = rememberPagerState(initialPage = target) { pages.size }
        val currentPages by rememberUpdatedState(pages)
        val currentMonth by rememberUpdatedState(state.month)
        val currentOnAction by rememberUpdatedState(onAction)
        LaunchedEffect(pagerState, target, pages) {
            if (pagerState.currentPage != target) pagerState.animateScrollToPage(target)
        }
        LaunchedEffect(pagerState) {
            snapshotFlow { pagerState.settledPage to pagerState.isScrollInProgress }.collect { (page, scrolling) ->
                if (scrolling) return@collect
                val month = currentPages.getOrNull(page)
                if (month != null && month != currentMonth) currentOnAction(CalendarAction.Settled(month))
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(CalendarDimens.CELL_GAP)) {
            WeekdayRow(cellSize)
            HorizontalPager(
                state = pagerState,
                key = { pages[it] },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(pageHeight)
                    .testTag(TestTags.Calendar.PAGER),
            ) { page ->
                MonthPage(pages[page], state.today, state.days, cellSize, onAction)
            }
        }
    }
}

private fun pagerPages(months: Collection<String>, thisMonth: String): List<String> {
    val first = (months + CalendarConstants.PAGER_FIRST_MONTH).minOrNull() ?: CalendarConstants.PAGER_FIRST_MONTH
    val past = generateSequence(first) { addMonths(it, CalendarConstants.NEXT_MONTH) }.takeWhile { it <= thisMonth }.toList()
    return past + months.filter { it > thisMonth }.distinct().sorted()
}

@Composable
private fun WeekdayRow(cellSize: Dp) {
    val names = remember { CalendarLabels.weekdayNames(CalendarConstants.WEEK_START) }
    Row(horizontalArrangement = Arrangement.spacedBy(CalendarDimens.CELL_GAP)) {
        names.forEachIndexed { position, name ->
            Text(
                text = name,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(cellSize).testTag(listItem(TestTags.Calendar.WEEKDAY, position)),
            )
        }
    }
}

@Composable
private fun MonthPage(yearMonth: String, today: String, days: Map<String, CalendarDay>, cellSize: Dp, onAction: (CalendarAction) -> Unit) {
    val weeks = remember(yearMonth, today) { monthGrid(yearMonth, today, CalendarConstants.WEEK_START) }
    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(CalendarDimens.CELL_GAP)) {
        for (week in weeks) {
            Row(horizontalArrangement = Arrangement.spacedBy(CalendarDimens.CELL_GAP)) {
                for (cell in week) DayCell(cell, if (cell.inMonth) days[cell.localDate] else null, cellSize, onAction)
            }
        }
    }
}

@Composable
private fun DayCell(cell: CalendarCell, day: CalendarDay?, cellSize: Dp, onAction: (CalendarAction) -> Unit) {
    if (!cell.inMonth) {
        Spacer(modifier = Modifier.size(cellSize))
        return
    }
    val shape = RoundedCornerShape(CalendarDimens.CELL_CORNER)
    val colors = MaterialTheme.colorScheme
    var modifier = Modifier
        .size(cellSize)
        .clip(shape)
        .background(colors.surfaceContainerHigh)
    if (cell.isToday) modifier = modifier.border(BorderStroke(CalendarDimens.TODAY_RING, colors.primary), shape)
    val label = CalendarLabels.cellLabel(LocalResources.current, cell.localDate, day?.count ?: 0)
    modifier = modifier
        .testTag(TestTags.calendarDay(cell.localDate))
        .semantics { contentDescription = label }
        .clickable(enabled = day != null, role = Role.Button) { onAction(CalendarAction.OpenDay(cell.localDate)) }
    val coverMediaId = day?.coverMediaId
    Box(modifier = modifier) {
        if (day != null && coverMediaId != null) DayCover(day.coverEntryId, coverMediaId)
        val onCover = day != null && coverMediaId != null
        Text(
            text = CalendarLabels.dayNumber(cell.localDate),
            style = MaterialTheme.typography.labelMedium.copy(
                shadow = if (onCover) Shadow(color = Color.Black, blurRadius = CalendarDimens.DAY_NUMBER_SHADOW_BLUR) else null,
            ),
            color = if (onCover) Color.White else colors.onSurface,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(CalendarDimens.DAY_NUMBER_PADDING)
                .testTag(TestTags.Calendar.DAY_NUMBER),
        )
    }
}

@Composable
private fun DayCover(entryId: String, mediaId: String) {
    val thumbnails = LocalCalendarThumbnails.current
    val image by produceState<ImageBitmap?>(null, entryId, mediaId, thumbnails) {
        value = try {
            thumbnails.load(entryId, mediaId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }
    image?.let { bitmap ->
        Image(bitmap = bitmap, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    }
}
