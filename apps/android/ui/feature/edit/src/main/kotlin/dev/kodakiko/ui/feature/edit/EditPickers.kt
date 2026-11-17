@file:OptIn(ExperimentalMaterial3Api::class)

package dev.kodakiko.ui.feature.edit

import android.text.format.DateFormat
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import dev.kodakiko.ui.core.EditTags
import dev.kodakiko.ui.core.R
import dev.kodakiko.ui.core.testTagsAsResourceIdRoot
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset

private fun parseDate(date: String): LocalDate? = try {
    LocalDate.parse(date)
} catch (_: DateTimeException) {
    null
}

private fun parseTime(time: String): LocalTime? = try {
    LocalTime.parse(time)
} catch (_: DateTimeException) {
    null
}

@Composable
internal fun EntryDatePicker(date: String, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val initial = parseDate(date)?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli()
    val state = rememberDatePickerState(initialSelectedDateMillis = initial)
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    val millis = state.selectedDateMillis
                    if (millis == null) onDismiss() else onPick(Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate().toString())
                },
                modifier = Modifier.testTag(EditTags.PICKER_OK),
            ) {
                Text(text = stringResource(R.string.edit_picker_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag(EditTags.PICKER_CANCEL)) {
                Text(text = stringResource(R.string.edit_picker_cancel))
            }
        },
        modifier = Modifier.testTagsAsResourceIdRoot().testTag(EditTags.DATE_PICKER),
    ) {
        DatePicker(state = state)
    }
}

@Composable
internal fun EntryTimePicker(time: String, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val parsed = parseTime(time)
    val state = rememberTimePickerState(
        initialHour = parsed?.hour ?: 0,
        initialMinute = parsed?.minute ?: 0,
        is24Hour = DateFormat.is24HourFormat(LocalContext.current),
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { TimePicker(state = state) },
        confirmButton = {
            TextButton(onClick = { onPick(LocalTime.of(state.hour, state.minute).toString()) }, modifier = Modifier.testTag(EditTags.PICKER_OK)) {
                Text(text = stringResource(R.string.edit_picker_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag(EditTags.PICKER_CANCEL)) {
                Text(text = stringResource(R.string.edit_picker_cancel))
            }
        },
        modifier = Modifier.testTagsAsResourceIdRoot().testTag(EditTags.TIME_PICKER),
    )
}
