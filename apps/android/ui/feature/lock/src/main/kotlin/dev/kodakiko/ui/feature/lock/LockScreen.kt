package dev.kodakiko.ui.feature.lock

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import dev.kodakiko.ui.core.components.material.AlertDialog
import dev.kodakiko.ui.core.components.material.DialogButton
import dev.kodakiko.ui.core.components.material.padding
import dev.kodakiko.ui.core.icons.KodakikoIcons
import dev.kodakiko.ui.core.navigation.LocalRouteNavigator
import dev.kodakiko.ui.core.screens.FormScreen
import dev.kodakiko.ui.core.util.secondaryItemAlpha
import dev.zacsweers.metrox.viewmodel.metroViewModel

@Composable
fun LockScreen(viewModel: LockViewModel = metroViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigator = LocalRouteNavigator.current
    LaunchedEffect(viewModel, navigator) {
        viewModel.events.collect { event ->
            when (event) {
                is LockEvent.Push -> navigator.push(event.route)
            }
        }
    }
    LockContent(
        state = state,
        onUnlockWithDevice = viewModel::onUnlockWithDevice,
        onUnlockWithPassword = viewModel::onUnlockWithPassword,
        onPasswordChange = viewModel::onPasswordChange,
        onForgot = viewModel::onForgot,
        onSignOut = viewModel::onSignOut,
        onSignOutConfirm = viewModel::onSignOutConfirm,
        onDismissDialog = viewModel::onDismissDialog,
    )
}

@Composable
fun LockContent(
    state: LockState,
    onUnlockWithDevice: () -> Unit,
    onUnlockWithPassword: () -> Unit,
    onPasswordChange: (String) -> Unit,
    onForgot: () -> Unit,
    onSignOut: () -> Unit,
    onSignOutConfirm: () -> Unit,
    onDismissDialog: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FormScreen(
        modifier = modifier.testTag(TestTags.Lock.SCREEN),
        icon = KodakikoIcons.Lock,
        headingText = stringResource(R.string.lock_title),
        headingTestTag = TestTags.Lock.TITLE,
    ) {
        state.username?.let { username ->
            Text(
                text = stringResource(R.string.lock_locked_as, username),
                modifier = Modifier.testTag(TestTags.Lock.LOCKED_AS).secondaryItemAlpha(),
                style = MaterialTheme.typography.titleSmall,
            )
        }
        if (state.unreadable) {
            Banner(text = stringResource(R.string.lock_unreadable), kind = BannerKind.ERROR, testTag = TestTags.Lock.UNREADABLE)
        } else {
            UnlockActions(state = state, onUnlockWithDevice = onUnlockWithDevice, onUnlockWithPassword = onUnlockWithPassword, onPasswordChange = onPasswordChange)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
        ) {
            FormButton(
                text = stringResource(R.string.lock_forgot),
                onClick = onForgot,
                testTag = TestTags.Lock.FORGOT,
                modifier = Modifier.weight(1f),
                style = FormButtonStyle.TEXT,
            )
            FormButton(
                text = stringResource(R.string.lock_sign_out),
                onClick = onSignOut,
                testTag = TestTags.Lock.SIGN_OUT,
                modifier = Modifier.weight(1f),
                style = FormButtonStyle.TEXT,
            )
        }
    }
    if (state.dialog == LockDialog.SignOut) {
        AlertDialog(
            onDismissRequest = onDismissDialog,
            confirmButton = DialogButton(text = stringResource(R.string.lock_sign_out), onClick = onSignOutConfirm, testTag = TestTags.Lock.SIGN_OUT_CONFIRM),
            modifier = Modifier.testTag(TestTags.Lock.SIGN_OUT_SHEET),
            dismissButton = DialogButton(text = stringResource(R.string.lock_cancel), onClick = onDismissDialog, testTag = TestTags.Lock.CANCEL),
            text = stringResource(R.string.lock_sign_out_confirm),
            destructive = true,
        )
    }
}

@Composable
private fun UnlockActions(
    state: LockState,
    onUnlockWithDevice: () -> Unit,
    onUnlockWithPassword: () -> Unit,
    onPasswordChange: (String) -> Unit,
) {
    if (state.updatedNotice) {
        Banner(text = stringResource(R.string.lock_updated), kind = BannerKind.INFO, testTag = TestTags.Lock.UPDATED)
    }
    state.error?.let { ErrorBanner(message = it) }
    FormButton(
        text = stringResource(R.string.lock_unlock_device),
        onClick = onUnlockWithDevice,
        testTag = TestTags.Lock.UNLOCK_DEVICE,
        busy = state.busy && !state.passwordShown,
        enabled = !state.busy,
    )
    if (state.passwordShown) {
        FormTextField(
            value = state.password,
            onValueChange = onPasswordChange,
            label = stringResource(R.string.lock_password),
            testTag = TestTags.Lock.PASSWORD,
            password = true,
            imeAction = ImeAction.Done,
            onImeAction = onUnlockWithPassword,
            submitOnEnter = true,
            errorText = if (state.wrongPassword) stringResource(R.string.lock_wrong_password) else null,
            errorTestTag = TestTags.Lock.WRONG_PASSWORD,
        )
    }
    FormButton(
        text = stringResource(R.string.lock_unlock_password),
        onClick = onUnlockWithPassword,
        testTag = TestTags.Lock.UNLOCK_PASSWORD,
        style = FormButtonStyle.SECONDARY,
        busy = state.busy && state.passwordShown,
    )
}
