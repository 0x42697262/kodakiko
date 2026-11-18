package dev.kodakiko.ui.feature.auth

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kodakiko.ui.core.R
import dev.kodakiko.ui.core.TestTags
import dev.kodakiko.ui.core.components.ErrorBanner
import dev.kodakiko.ui.core.components.FormButton
import dev.kodakiko.ui.core.components.FormTextField
import dev.kodakiko.ui.core.screens.FormScreen
import dev.zacsweers.metrox.viewmodel.metroViewModel

@Composable
fun SignUpScreen(viewModel: SignUpViewModel = metroViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    AuthEvents(viewModel.events)
    SignUpContent(
        state = state,
        onInviteChange = viewModel::onInviteChange,
        onUsernameChange = viewModel::onUsernameChange,
        onPasswordChange = viewModel::onPasswordChange,
        onConfirmChange = viewModel::onConfirmChange,
        onSubmit = viewModel::onSubmit,
    )
}

@Composable
fun SignUpContent(
    state: SignUpState,
    onInviteChange: (String) -> Unit,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onConfirmChange: (String) -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FormScreen(
        modifier = modifier.testTag(TestTags.SignUp.SCREEN),
        headingText = stringResource(R.string.sign_up_title),
        headingTestTag = TestTags.SignUp.TITLE,
    ) {
        state.error?.let { ErrorBanner(message = it) }
        if (state.asksForInvite) {
            FormTextField(
                value = state.invite,
                onValueChange = onInviteChange,
                label = stringResource(R.string.sign_up_invite),
                testTag = TestTags.SignUp.INVITE,
                keyboardType = KeyboardType.Uri,
            )
        }
        FormTextField(
            value = state.username,
            onValueChange = onUsernameChange,
            label = stringResource(R.string.sign_up_username),
            testTag = TestTags.SignUp.USERNAME,
        )
        FormTextField(
            value = state.password,
            onValueChange = onPasswordChange,
            label = stringResource(R.string.sign_up_password),
            testTag = TestTags.SignUp.PASSWORD,
            password = true,
        )
        PasswordHints(state.hints)
        FormTextField(
            value = state.confirm,
            onValueChange = onConfirmChange,
            label = stringResource(R.string.sign_up_confirm),
            testTag = TestTags.SignUp.CONFIRM,
            password = true,
            imeAction = ImeAction.Done,
            onImeAction = onSubmit,
        )
        MismatchHint(state.mismatch, stringResource(R.string.password_mismatch))
        FormButton(
            text = stringResource(R.string.sign_up_submit),
            onClick = onSubmit,
            testTag = TestTags.SignUp.SUBMIT,
            busy = state.busy,
        )
    }
}
