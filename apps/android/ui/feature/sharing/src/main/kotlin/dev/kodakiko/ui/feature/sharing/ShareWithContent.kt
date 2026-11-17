@file:OptIn(ExperimentalMaterial3Api::class)

package dev.kodakiko.ui.feature.sharing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import dev.kodakiko.contract.ShareDto
import dev.kodakiko.js.jsTrim
import dev.kodakiko.ui.core.R
import dev.kodakiko.ui.core.TestTags
import dev.kodakiko.ui.core.TestTags.listItem
import dev.kodakiko.ui.core.components.AdaptiveSheet
import dev.kodakiko.ui.core.components.AppBar
import dev.kodakiko.ui.core.components.FormButton
import dev.kodakiko.ui.core.components.FormButtonStyle
import dev.kodakiko.ui.core.components.FormTextField
import dev.kodakiko.ui.core.components.ListGroupHeader
import dev.kodakiko.ui.core.components.material.Scaffold
import dev.kodakiko.ui.core.components.material.padding

@Composable
fun ShareWithContent(state: ShareWithState, onAction: (ShareWithAction) -> Unit, modifier: Modifier = Modifier) {
    Scaffold(
        modifier = modifier.testTag(TestTags.Share.SCREEN),
        topBar = {
            AppBar(
                title = stringResource(R.string.shared_share_title),
                navigateUp = { onAction(ShareWithAction.Back) },
                navigateUpTestTag = TestTags.Shared.BACK,
                scrollBehavior = it,
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(vertical = SharingDimens.SCREEN_PADDING),
            verticalArrangement = Arrangement.spacedBy(SharingDimens.SECTION_SPACING),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = SharingDimens.SCREEN_PADDING),
                verticalArrangement = Arrangement.spacedBy(SharingDimens.SECTION_SPACING),
            ) {
                FormTextField(
                    value = state.username,
                    onValueChange = { onAction(ShareWithAction.UsernameChanged(it)) },
                    label = stringResource(R.string.shared_username_field),
                    testTag = TestTags.Share.USERNAME,
                    imeAction = ImeAction.Search,
                    onImeAction = { onAction(ShareWithAction.Find) },
                )
                FormButton(
                    text = stringResource(R.string.shared_find),
                    onClick = { onAction(ShareWithAction.Find) },
                    testTag = TestTags.Share.FIND,
                    enabled = jsTrim(state.username).isNotEmpty(),
                    busy = state.busy && state.found == null,
                )
                if (state.stopping == null) SharingError(state.error)
                state.found?.let { FoundCard(it, state.busy, onAction) }
            }
            Recipients(state.recipients, onAction)
        }
    }
    state.stopping?.let { StopSheet(it, state, onAction) }
}

@Composable
private fun FoundCard(found: FoundRecipient, busy: Boolean, onAction: (ShareWithAction) -> Unit) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(SharingDimens.CODE_PADDING), verticalArrangement = Arrangement.spacedBy(SharingDimens.SECTION_SPACING)) {
            Text(text = found.username, style = MaterialTheme.typography.titleMedium)
            Text(
                text = found.fingerprint,
                style = MaterialTheme.typography.titleLarge,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.testTag(TestTags.Share.SECURITY_CODE),
            )
            Text(text = stringResource(R.string.shared_security_code_help, found.username), style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small)) {
                FormButton(
                    text = stringResource(R.string.shared_share),
                    onClick = { onAction(ShareWithAction.ConfirmShare) },
                    testTag = TestTags.Share.CONFIRM,
                    busy = busy,
                    modifier = Modifier.weight(1f),
                )
                FormButton(
                    text = stringResource(R.string.shared_cancel),
                    onClick = { onAction(ShareWithAction.CancelShare) },
                    testTag = TestTags.Share.CANCEL,
                    style = FormButtonStyle.SECONDARY,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun Recipients(recipients: List<ShareDto>, onAction: (ShareWithAction) -> Unit) {
    Column {
        ListGroupHeader(text = stringResource(R.string.shared_recipients))
        recipients.forEachIndexed { position, recipient ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(listItem(TestTags.Share.RECIPIENT_ROW, position))
                    .padding(horizontal = SharingDimens.SCREEN_PADDING, vertical = MaterialTheme.padding.extraSmall),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = recipient.recipientUsername, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = { onAction(ShareWithAction.Remove(recipient)) }, modifier = Modifier.testTag(listItem(TestTags.Share.REMOVE, position))) {
                    Text(text = stringResource(R.string.shared_remove))
                }
            }
        }
    }
}

@Composable
private fun StopSheet(stopping: ShareDto, state: ShareWithState, onAction: (ShareWithAction) -> Unit) {
    AdaptiveSheet(onDismissRequest = { onAction(ShareWithAction.DismissStop) }, modifier = Modifier.testTag(TestTags.Collection.SHEET)) {
        SheetColumn {
            ConfirmPanel(
                text = stringResource(R.string.shared_stop_confirm, stopping.recipientUsername),
                action = stringResource(R.string.shared_stop_action),
                testTag = TestTags.Share.STOP_CONFIRM,
                busy = state.busy,
                error = state.error,
                onConfirm = { onAction(ShareWithAction.ConfirmStop) },
            )
        }
    }
}
