package dev.kodakiko.ui.feature.calendar

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import dev.kodakiko.ui.core.TestTags
import dev.kodakiko.ui.core.components.AdaptiveSheet
import dev.kodakiko.ui.core.R as CoreR

@Composable
internal fun JumpSheet(jump: CalendarJump, onAction: (CalendarAction) -> Unit) {
    AdaptiveSheet(
        onDismissRequest = { onAction(CalendarAction.DismissJump) },
        modifier = Modifier.testTag(TestTags.Calendar.JUMP_SHEET),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(CalendarDimens.SHEET_PADDING),
            verticalArrangement = Arrangement.spacedBy(CalendarDimens.SHEET_SPACING),
        ) {
            val error = jump.error
            OutlinedTextField(
                value = jump.text,
                onValueChange = { onAction(CalendarAction.JumpText(it)) },
                label = { Text(text = stringResource(CoreR.string.calendar_jump_field)) },
                singleLine = true,
                isError = error != null,
                supportingText = error?.let { problem ->
                    { Text(text = stringResource(jumpErrorText(problem)), modifier = Modifier.testTag(TestTags.Calendar.JUMP_INVALID)) }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { onAction(CalendarAction.SubmitJump) }),
                modifier = Modifier.fillMaxWidth().testTag(TestTags.Calendar.JUMP_FIELD),
            )
            Button(onClick = { onAction(CalendarAction.SubmitJump) }, modifier = Modifier.fillMaxWidth().testTag(TestTags.Calendar.JUMP_SUBMIT)) {
                Text(text = stringResource(CoreR.string.calendar_jump_submit))
            }
        }
    }
}

private fun jumpErrorText(error: CalendarJumpError): Int = when (error) {
    CalendarJumpError.INVALID -> CoreR.string.calendar_jump_invalid
    CalendarJumpError.EMPTY -> CoreR.string.calendar_jump_empty
}
