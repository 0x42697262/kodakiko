package dev.kodakiko.ui.core.components.material

import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag

@Immutable
data class SnackbarAction(
    val label: String,
    val testTag: String,
    val onClick: () -> Unit,
)

@Immutable
data class SnackbarMessage(
    val text: String,
    val testTag: String,
    val action: SnackbarAction? = null,
)

private class MessageVisuals(val source: SnackbarMessage) : SnackbarVisuals {
    override val actionLabel: String? get() = source.action?.label
    override val duration: SnackbarDuration get() = SnackbarDuration.Short
    override val withDismissAction: Boolean get() = false
    override val message: String get() = source.text
}

suspend fun SnackbarHostState.showMessage(message: SnackbarMessage): SnackbarResult {
    currentSnackbarData?.dismiss()
    return showSnackbar(MessageVisuals(message))
}

@Composable
fun KodakikoSnackbarHost(
    hostState: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    SnackbarHost(hostState = hostState, modifier = modifier) { data ->
        val message = (data.visuals as? MessageVisuals)?.source
        val action = message?.action
        Snackbar(
            action = action?.let {
                {
                    TextButton(
                        onClick = {
                            it.onClick()
                            data.performAction()
                        },
                        modifier = Modifier.testTag(it.testTag),
                    ) {
                        Text(it.label)
                    }
                }
            },
        ) {
            Text(
                text = data.visuals.message,
                modifier = if (message == null) Modifier else Modifier.testTag(message.testTag),
            )
        }
    }
}
