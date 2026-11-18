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
import dev.kodakiko.ui.core.components.Banner
import dev.kodakiko.ui.core.components.BannerKind
import dev.kodakiko.ui.core.components.ErrorBanner
import dev.kodakiko.ui.core.components.FormButton
import dev.kodakiko.ui.core.components.FormButtonStyle
import dev.kodakiko.ui.core.components.FormTextField
import dev.kodakiko.ui.core.screens.FormScreen
import dev.zacsweers.metrox.viewmodel.metroViewModel

@Composable
fun SignInScreen(viewModel: SignInViewModel = metroViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    AuthEvents(viewModel.events)
    SignInContent(
        state = state,
        onUsernameChange = viewModel::onUsernameChange,
        onPasswordChange = viewModel::onPasswordChange,
        onSubmit = viewModel::onSubmit,
        onForgot = viewModel::onForgot,
    )
}

@Composable
fun SignInContent(
    state: SignInState,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onForgot: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FormScreen(
        modifier = modifier.testTag(TestTags.SignIn.SCREEN),
        headingText = stringResource(R.string.sign_in_title),
        headingTestTag = TestTags.SignIn.TITLE,
    ) {
        state.error?.let { ErrorBanner(message = it) }
        state.deletionScheduled?.let { date ->
            Banner(
                text = stringResource(R.string.account_deletion_scheduled, date),
                kind = BannerKind.INFO,
                testTag = TestTags.SignIn.DELETION_SCHEDULED,
            )
        }
        FormTextField(
            value = state.username,
            onValueChange = onUsernameChange,
            label = stringResource(R.string.sign_in_username),
            testTag = TestTags.SignIn.USERNAME,
        )
        FormTextField(
            value = state.password,
            onValueChange = onPasswordChange,
            label = stringResource(R.string.sign_in_password),
            testTag = TestTags.SignIn.PASSWORD,
            password = true,
            imeAction = ImeAction.Done,
            onImeAction = onSubmit,
        )
        FormButton(text = stringResource(R.string.sign_in_submit), onClick = onSubmit, testTag = TestTags.SignIn.SUBMIT, busy = state.busy)
        FormButton(
            text = stringResource(R.string.sign_in_forgot),
            onClick = onForgot,
            testTag = TestTags.SignIn.FORGOT,
            style = FormButtonStyle.TEXT,
        )
    }
}
