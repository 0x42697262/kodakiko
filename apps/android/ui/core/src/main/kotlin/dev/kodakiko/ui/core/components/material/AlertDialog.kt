package dev.kodakiko.ui.core.components.material

import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import dev.kodakiko.ui.core.testTagsAsResourceIdRoot
import dev.kodakiko.ui.core.util.optionalTestTag
import androidx.compose.material3.AlertDialog as MaterialAlertDialog

@Immutable
data class DialogButton(
    val text: String,
    val onClick: () -> Unit,
    val testTag: String? = null,
    val enabled: Boolean = true,
)

@Composable
fun AlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: DialogButton,
    modifier: Modifier = Modifier,
    dismissButton: DialogButton? = null,
    title: String? = null,
    text: String? = null,
    destructive: Boolean = false,
) {
    MaterialAlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = {
            DialogTextButton(
                button = confirmButton,
                destructive = destructive,
            )
        },
        modifier = modifier.testTagsAsResourceIdRoot(),
        dismissButton = dismissButton?.let { { DialogTextButton(button = it, destructive = false) } },
        title = title?.let { { Text(it) } },
        text = text?.let { { Text(it) } },
    )
}

@Composable
private fun DialogTextButton(
    button: DialogButton,
    destructive: Boolean,
) {
    TextButton(
        onClick = button.onClick,
        modifier = Modifier.optionalTestTag(button.testTag),
        enabled = button.enabled,
        colors = if (destructive) {
            ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
        } else {
            ButtonDefaults.textButtonColors()
        },
    ) {
        Text(button.text)
    }
}
