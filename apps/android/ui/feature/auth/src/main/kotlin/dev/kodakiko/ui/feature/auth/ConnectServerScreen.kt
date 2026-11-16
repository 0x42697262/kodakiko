package dev.kodakiko.ui.feature.auth

import androidx.compose.material3.ExperimentalMaterial3Api
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
import dev.kodakiko.ui.core.components.AppBar
import dev.kodakiko.ui.core.components.Banner
import dev.kodakiko.ui.core.components.BannerKind
import dev.kodakiko.ui.core.components.ErrorBanner
import dev.kodakiko.ui.core.components.FormButton
import dev.kodakiko.ui.core.components.FormTextField
import dev.kodakiko.ui.core.navigation.LocalRouteNavigator
import dev.kodakiko.ui.core.screens.FormScreen
import dev.zacsweers.metrox.viewmodel.metroViewModel

@Composable
fun ConnectServerScreen(viewModel: ConnectServerViewModel = metroViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigator = LocalRouteNavigator.current
    ConnectServerContent(
        state = state,
        onServerChange = viewModel::onServerChange,
        onInviteChange = viewModel::onInviteChange,
        onUsernameChange = viewModel::onUsernameChange,
        onPasswordChange = viewModel::onPasswordChange,
        onConnect = viewModel::onConnect,
        onBack = { navigator.pop() },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectServerContent(
    state: ConnectServerState,
    onServerChange: (String) -> Unit,
    onInviteChange: (String) -> Unit,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onConnect: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FormScreen(
        modifier = modifier.testTag(TestTags.Connect.SCREEN),
        topBar = {
            AppBar(
                title = stringResource(R.string.connect_title),
                navigateUp = onBack,
                navigateUpTestTag = TestTags.Connect.BACK,
            )
        },
    ) {
        if (state.done) Banner(text = stringResource(R.string.connect_done), kind = BannerKind.INFO, testTag = TestTags.Connect.DONE)
        state.error?.let { ErrorBanner(message = it) }
        FormTextField(
            value = state.server,
            onValueChange = onServerChange,
            label = stringResource(R.string.connect_server),
            testTag = TestTags.Connect.SERVER,
            keyboardType = KeyboardType.Uri,
        )
        FormTextField(
            value = state.invite,
            onValueChange = onInviteChange,
            label = stringResource(R.string.connect_invite),
            testTag = TestTags.Connect.INVITE,
            keyboardType = KeyboardType.Uri,
        )
        FormTextField(
            value = state.username,
            onValueChange = onUsernameChange,
            label = stringResource(R.string.sign_up_username),
            testTag = TestTags.Connect.USERNAME,
        )
        FormTextField(
            value = state.password,
            onValueChange = onPasswordChange,
            label = stringResource(R.string.settings_delete_password),
            testTag = TestTags.Connect.PASSWORD,
            password = true,
            imeAction = ImeAction.Done,
            onImeAction = onConnect,
        )
        FormButton(
            text = stringResource(R.string.connect_action),
            onClick = onConnect,
            testTag = TestTags.Connect.ACTION,
            enabled = state.canConnect,
            busy = state.busy,
        )
    }
}
