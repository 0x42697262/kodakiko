package dev.kodakiko.ui.feature.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import dev.kodakiko.ui.core.components.FormButton
import dev.kodakiko.ui.core.components.FormHint
import dev.kodakiko.ui.core.components.FormTextField
import dev.kodakiko.ui.core.format.passwordRuleTag
import dev.kodakiko.ui.core.format.passwordRuleText
import dev.zacsweers.metrox.viewmodel.metroViewModel

@Composable
fun PasswordScreen(viewModel: PasswordViewModel = metroViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SettingsEventsEffect(viewModel.events)
    PasswordContent(state = state, onAction = viewModel::onAction)
}

@Composable
fun PasswordContent(state: PasswordState, onAction: (PasswordAction) -> Unit) {
    FormPage(title = stringResource(R.string.settings_password_title), screenTag = TestTags.Settings.PASSWORD_SCREEN, onBack = { onAction(PasswordAction.Back) }) {
        FormTextField(
            value = state.current,
            onValueChange = { onAction(PasswordAction.SetCurrent(it)) },
            label = stringResource(R.string.settings_current_password),
            testTag = TestTags.Settings.CURRENT_PASSWORD,
            password = true,
        )
        FormTextField(
            value = state.next,
            onValueChange = { onAction(PasswordAction.SetNext(it)) },
            label = stringResource(R.string.settings_new_password),
            testTag = TestTags.Settings.NEW_PASSWORD,
            password = true,
        )
        state.hints.forEach { rule -> FormHint(text = stringResource(passwordRuleText(rule)), testTag = passwordRuleTag(rule)) }
        FormTextField(
            value = state.confirm,
            onValueChange = { onAction(PasswordAction.SetConfirm(it)) },
            label = stringResource(R.string.settings_confirm_password),
            testTag = TestTags.Settings.CONFIRM_PASSWORD,
            password = true,
            imeAction = ImeAction.Done,
            onImeAction = { onAction(PasswordAction.Submit) },
            errorText = if (state.mismatch) stringResource(R.string.settings_password_mismatch) else null,
            errorTestTag = TestTags.Settings.PASSWORD_MISMATCH,
        )
        if (state.busy) BodyText(text = stringResource(R.string.settings_password_securing), testTag = TestTags.Settings.PASSWORD_SECURING)
        state.message?.let { message ->
            Banner(
                text = stringResource(
                    when (message) {
                        PasswordMessage.CHANGED -> R.string.settings_password_changed
                        PasswordMessage.WRONG_PASSWORD -> R.string.settings_password_wrong
                        PasswordMessage.FAILED -> R.string.admin_failed
                    },
                ),
                kind = if (message == PasswordMessage.CHANGED) BannerKind.INFO else BannerKind.ERROR,
                testTag = TestTags.Settings.PASSWORD_RESULT,
            )
        }
        FormButton(
            text = stringResource(R.string.settings_password_submit),
            onClick = { onAction(PasswordAction.Submit) },
            testTag = TestTags.Settings.PASSWORD_SUBMIT,
            enabled = state.canSubmit,
            busy = state.busy,
        )
    }
}

@Composable
fun DeleteAccountScreen(viewModel: DeleteAccountViewModel = metroViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SettingsEventsEffect(viewModel.events)
    DeleteAccountContent(state = state, onAction = viewModel::onAction)
}

@Composable
fun DeleteAccountContent(state: DeleteAccountState, onAction: (DeleteAccountAction) -> Unit) {
    val title = stringResource(if (state.local) R.string.settings_erase else R.string.settings_delete_title)
    FormPage(title = title, screenTag = TestTags.Settings.DELETE_SCREEN, onBack = { onAction(DeleteAccountAction.Back) }) {
        BodyText(text = if (state.local) stringResource(R.string.settings_erase_text) else stringResource(R.string.settings_delete_text, state.graceDays))
        FormTextField(
            value = state.typed,
            onValueChange = { onAction(DeleteAccountAction.SetTyped(it)) },
            label = stringResource(R.string.settings_delete_type_username),
            testTag = TestTags.Settings.DELETE_USERNAME,
        )
        FormTextField(
            value = state.password,
            onValueChange = { onAction(DeleteAccountAction.SetPassword(it)) },
            label = stringResource(R.string.settings_delete_password),
            testTag = TestTags.Settings.DELETE_PASSWORD,
            password = true,
            imeAction = ImeAction.Done,
        )
        state.error?.let { error ->
            Banner(
                text = stringResource(if (error == DeleteAccountError.WRONG_PASSWORD) R.string.settings_password_wrong else R.string.admin_failed),
                kind = BannerKind.ERROR,
                testTag = TestTags.Settings.ERROR,
            )
        }
        DangerButton(
            text = stringResource(if (state.local) R.string.settings_erase_action else R.string.settings_delete_action),
            onClick = { onAction(DeleteAccountAction.Confirm) },
            testTag = TestTags.Settings.DELETE_CONFIRM,
            enabled = state.canConfirm,
            busy = state.busy,
        )
    }
}

@Composable
internal fun DangerButton(text: String, onClick: () -> Unit, testTag: String, enabled: Boolean = true, busy: Boolean = false, fill: Boolean = true) {
    Button(
        onClick = onClick,
        enabled = enabled && !busy,
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
        modifier = (if (fill) Modifier.fillMaxWidth() else Modifier).testTag(testTag),
    ) {
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(SettingsDimens.BUTTON_PROGRESS_SIZE),
                color = LocalContentColor.current,
                strokeWidth = SettingsDimens.BUTTON_PROGRESS_STROKE,
            )
            Spacer(Modifier.width(SettingsDimens.ITEM_SPACING))
        }
        Text(text = text)
    }
}
