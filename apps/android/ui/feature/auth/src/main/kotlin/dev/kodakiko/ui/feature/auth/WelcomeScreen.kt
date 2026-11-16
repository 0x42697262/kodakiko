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
import dev.kodakiko.ui.core.components.FormButtonStyle
import dev.kodakiko.ui.core.components.FormTextField
import dev.kodakiko.ui.core.icons.KodakikoIcons
import dev.kodakiko.ui.core.screens.FormScreen
import dev.zacsweers.metrox.viewmodel.metroViewModel

@Composable
fun WelcomeScreen(viewModel: WelcomeViewModel = metroViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    AuthEvents(viewModel.events)
    WelcomeContent(
        state = state,
        onServerChange = viewModel::onServerChange,
        onContinue = viewModel::onContinue,
        onUseWithoutServer = viewModel::onUseWithoutServer,
    )
}

@Composable
fun WelcomeContent(
    state: WelcomeState,
    onServerChange: (String) -> Unit,
    onContinue: () -> Unit,
    onUseWithoutServer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FormScreen(
        modifier = modifier.testTag(TestTags.Welcome.SCREEN),
        icon = KodakikoIcons.PhotoLibrary,
        headingText = stringResource(R.string.welcome_title),
        headingTestTag = TestTags.Welcome.TITLE,
        subtitleText = stringResource(R.string.welcome_subtitle),
        subtitleTestTag = TestTags.Welcome.SUBTITLE,
    ) {
        state.error?.let { ErrorBanner(message = it) }
        FormTextField(
            value = state.server,
            onValueChange = onServerChange,
            label = stringResource(R.string.welcome_server_label),
            testTag = TestTags.Welcome.SERVER_LABEL,
            keyboardType = KeyboardType.Uri,
            imeAction = ImeAction.Go,
            onImeAction = onContinue,
        )
        FormButton(
            text = stringResource(R.string.welcome_continue),
            onClick = onContinue,
            testTag = TestTags.Welcome.CONTINUE,
            busy = state.busy,
        )
        FormButton(
            text = stringResource(R.string.welcome_local),
            onClick = onUseWithoutServer,
            testTag = TestTags.Welcome.LOCAL,
            style = FormButtonStyle.SECONDARY,
        )
    }
}
