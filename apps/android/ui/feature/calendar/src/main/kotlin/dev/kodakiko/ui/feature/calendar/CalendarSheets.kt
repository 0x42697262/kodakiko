package dev.kodakiko.ui.feature.calendar

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import dev.kodakiko.ui.core.TestTags
import dev.kodakiko.ui.core.TestTags.listItem
import dev.kodakiko.ui.core.components.AdaptiveSheet
import java.time.Month
import java.time.YearMonth
import java.time.format.TextStyle
import dev.kodakiko.ui.core.R as CoreR

@Composable
internal fun JumpSheet(jump: CalendarJump, onAction: (CalendarAction) -> Unit) {
    val shown = remember(jump.text) { runCatching { YearMonth.parse(jump.text) }.getOrNull() ?: YearMonth.now() }
    var year by remember { mutableIntStateOf(shown.year) }
    AdaptiveSheet(
        onDismissRequest = { onAction(CalendarAction.DismissJump) },
        modifier = Modifier.testTag(TestTags.Calendar.JUMP_SHEET),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(CalendarDimens.SHEET_PADDING),
            verticalArrangement = Arrangement.spacedBy(CalendarDimens.SHEET_SPACING),
        ) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { year -= 1 }, modifier = Modifier.testTag(TestTags.Calendar.JUMP_PREVIOUS_YEAR)) {
                    Icon(imageVector = CalendarIcons.ChevronLeft, contentDescription = stringResource(CoreR.string.calendar_jump_previous_year))
                }
                Text(
                    text = year.toString(),
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f).testTag(TestTags.Calendar.JUMP_YEAR),
                )
                IconButton(onClick = { year += 1 }, modifier = Modifier.testTag(TestTags.Calendar.JUMP_NEXT_YEAR)) {
                    Icon(imageVector = CalendarIcons.ChevronRight, contentDescription = stringResource(CoreR.string.calendar_jump_next_year))
                }
            }
            Month.entries.chunked(CalendarConstants.JUMP_MONTHS_PER_ROW).forEach { row ->
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(CalendarDimens.SHEET_SPACING)) {
                    row.forEach { month ->
                        val target = YearMonth.of(year, month)
                        MonthButton(
                            label = month.getDisplayName(TextStyle.SHORT, CalendarConstants.LABEL_LOCALE),
                            selected = target == shown,
                            tag = listItem(TestTags.Calendar.JUMP_MONTH, month.ordinal),
                            modifier = Modifier.weight(1f),
                        ) {
                            onAction(CalendarAction.JumpText(target.toString()))
                            onAction(CalendarAction.SubmitJump)
                        }
                    }
                }
            }
            jump.error?.let { problem ->
                Text(
                    text = stringResource(jumpErrorText(problem)),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag(TestTags.Calendar.JUMP_INVALID),
                )
            }
        }
    }
}

@Composable
private fun MonthButton(label: String, selected: Boolean, tag: String, modifier: Modifier, onClick: () -> Unit) {
    if (selected) {
        FilledTonalButton(onClick = onClick, modifier = modifier.testTag(tag)) { Text(text = label, maxLines = 1) }
    } else {
        OutlinedButton(onClick = onClick, modifier = modifier.testTag(tag)) { Text(text = label, maxLines = 1) }
    }
}

private fun jumpErrorText(error: CalendarJumpError): Int = when (error) {
    CalendarJumpError.INVALID -> CoreR.string.calendar_jump_invalid
    CalendarJumpError.EMPTY -> CoreR.string.calendar_jump_empty
}
