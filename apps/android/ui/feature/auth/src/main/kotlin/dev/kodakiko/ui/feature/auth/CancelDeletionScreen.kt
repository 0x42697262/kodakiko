package dev.kodakiko.ui.feature.auth

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kodakiko.ui.core.R
import dev.kodakiko.ui.core.TestTags
import dev.kodakiko.ui.core.components.ErrorBanner
import dev.kodakiko.ui.core.components.FormButton
import dev.kodakiko.ui.core.components.FormButtonStyle
import dev.kodakiko.ui.core.icons.KodakikoIcons
import dev.kodakiko.ui.core.screens.FormScreen
import dev.zacsweers.metrox.viewmodel.metroViewModel

@Composable
fun CancelDeletionScreen(viewModel: CancelDeletionViewModel = metroViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    AuthEvents(viewModel.events)
    CancelDeletionContent(state = state, onKeep = viewModel::onKeep, onSignOut = viewModel::onSignOut)
}

@Composable
fun CancelDeletionContent(
    state: CancelDeletionState,
    onKeep: () -> Unit,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FormScreen(
        modifier = modifier.testTag(TestTags.CancelDeletion.SCREEN),
        icon = KodakikoIcons.Info,
        headingText = stringResource(R.string.cancel_deletion_title),
        headingTestTag = TestTags.CancelDeletion.TITLE,
    ) {
        state.error?.let { ErrorBanner(message = it) }
        Text(
            text = stringResource(R.string.cancel_deletion_body, state.date),
            modifier = Modifier.testTag(TestTags.CancelDeletion.BODY),
            style = MaterialTheme.typography.bodyLarge,
        )
        FormButton(
            text = stringResource(R.string.cancel_deletion_submit),
            onClick = onKeep,
            testTag = TestTags.CancelDeletion.SUBMIT,
            busy = state.busy,
        )
        FormButton(
            text = stringResource(R.string.cancel_deletion_sign_out),
            onClick = onSignOut,
            testTag = TestTags.CancelDeletion.SIGN_OUT,
            style = FormButtonStyle.SECONDARY,
        )
    }
}
