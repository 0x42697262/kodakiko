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
import dev.kodakiko.ui.core.components.FormSwitch
import dev.kodakiko.ui.core.components.FormTextField
import dev.kodakiko.ui.core.screens.FormScreen
import dev.zacsweers.metrox.viewmodel.metroViewModel

@Composable
fun LocalSetupScreen(viewModel: LocalSetupViewModel = metroViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    AuthEvents(viewModel.events)
    LocalSetupContent(
        state = state,
        onUnderstoodChange = viewModel::onUnderstoodChange,
        onUsernameChange = viewModel::onUsernameChange,
        onPasswordChange = viewModel::onPasswordChange,
        onConfirmChange = viewModel::onConfirmChange,
        onCreate = viewModel::onCreate,
    )
}

@Composable
fun LocalSetupContent(
    state: LocalSetupState,
    onUnderstoodChange: (Boolean) -> Unit,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onConfirmChange: (String) -> Unit,
    onCreate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FormScreen(
        modifier = modifier.testTag(TestTags.LocalSetup.SCREEN),
        headingText = stringResource(R.string.local_setup_title),
        headingTestTag = TestTags.LocalSetup.TITLE,
    ) {
        Banner(text = stringResource(R.string.local_setup_warning), kind = BannerKind.WARNING, testTag = TestTags.LocalSetup.WARNING)
        FormSwitch(
            text = stringResource(R.string.local_setup_understand),
            checked = state.understood,
            onCheckedChange = onUnderstoodChange,
            testTag = TestTags.LocalSetup.UNDERSTAND,
        )
        state.error?.let { ErrorBanner(message = it) }
        FormTextField(
            value = state.username,
            onValueChange = onUsernameChange,
            label = stringResource(R.string.sign_up_username),
            testTag = TestTags.LocalSetup.USERNAME,
        )
        FormTextField(
            value = state.password,
            onValueChange = onPasswordChange,
            label = stringResource(R.string.sign_up_password),
            testTag = TestTags.LocalSetup.PASSWORD,
            password = true,
        )
        PasswordHints(state.hints)
        FormTextField(
            value = state.confirm,
            onValueChange = onConfirmChange,
            label = stringResource(R.string.sign_up_confirm),
            testTag = TestTags.LocalSetup.CONFIRM,
            password = true,
            imeAction = ImeAction.Done,
            onImeAction = onCreate,
            errorText = if (state.mismatch) stringResource(R.string.settings_password_mismatch) else null,
            errorTestTag = TestTags.Password.MISMATCH,
        )
        FormButton(
            text = stringResource(R.string.local_setup_create),
            onClick = onCreate,
            testTag = TestTags.LocalSetup.CREATE,
            enabled = state.understood,
            busy = state.busy,
        )
    }
}
