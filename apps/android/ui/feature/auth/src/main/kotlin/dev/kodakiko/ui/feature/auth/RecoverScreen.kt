package dev.kodakiko.ui.feature.auth

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kodakiko.ui.core.R
import dev.kodakiko.ui.core.TestTags
import dev.kodakiko.ui.core.components.ErrorBanner
import dev.kodakiko.ui.core.components.FormButton
import dev.kodakiko.ui.core.components.FormTextField
import dev.kodakiko.ui.core.screens.FormScreen
import dev.zacsweers.metrox.viewmodel.metroViewModel

@Composable
fun RecoverScreen(viewModel: RecoverViewModel = metroViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    AuthEvents(viewModel.events)
    RecoverContent(
        state = state,
        onUsernameChange = viewModel::onUsernameChange,
        onWordsChange = viewModel::onWordsChange,
        onNewPasswordChange = viewModel::onNewPasswordChange,
        onConfirmChange = viewModel::onConfirmChange,
        onSubmit = viewModel::onSubmit,
    )
}

@Composable
fun RecoverContent(
    state: RecoverState,
    onUsernameChange: (String) -> Unit,
    onWordsChange: (String) -> Unit,
    onNewPasswordChange: (String) -> Unit,
    onConfirmChange: (String) -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FormScreen(
        modifier = modifier.testTag(TestTags.Recover.SCREEN),
        headingText = stringResource(R.string.recover_title),
        headingTestTag = TestTags.Recover.TITLE,
    ) {
        state.error?.let { ErrorBanner(message = it) }
        if (state.showUsername) {
            FormTextField(
                value = state.username,
                onValueChange = onUsernameChange,
                label = stringResource(R.string.recover_username),
                testTag = TestTags.Recover.USERNAME,
            )
        }
        FormTextField(
            value = state.words,
            onValueChange = onWordsChange,
            label = stringResource(R.string.recover_words),
            testTag = TestTags.Recover.WORDS,
            singleLine = false,
        )
        FormTextField(
            value = state.newPassword,
            onValueChange = onNewPasswordChange,
            label = stringResource(R.string.recover_new_password),
            testTag = TestTags.Recover.NEW_PASSWORD,
            password = true,
        )
        PasswordHints(state.hints)
        FormTextField(
            value = state.confirm,
            onValueChange = onConfirmChange,
            label = stringResource(R.string.recover_confirm),
            testTag = TestTags.Recover.CONFIRM,
            password = true,
            imeAction = ImeAction.Done,
            onImeAction = onSubmit,
        )
        MismatchHint(state.mismatch, stringResource(R.string.password_mismatch))
        FormButton(text = stringResource(R.string.recover_submit), onClick = onSubmit, testTag = TestTags.Recover.SUBMIT, busy = state.busy)
    }
}
