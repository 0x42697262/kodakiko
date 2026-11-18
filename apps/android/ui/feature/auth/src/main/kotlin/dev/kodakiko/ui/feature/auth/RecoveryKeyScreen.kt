package dev.kodakiko.ui.feature.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kodakiko.ui.core.R
import dev.kodakiko.ui.core.TestTags
import dev.kodakiko.ui.core.components.Banner
import dev.kodakiko.ui.core.components.BannerKind
import dev.kodakiko.ui.core.components.ErrorBanner
import dev.kodakiko.ui.core.components.FormButton
import dev.kodakiko.ui.core.components.FormTextField
import dev.kodakiko.ui.core.components.material.padding
import dev.kodakiko.ui.core.icons.KodakikoIcons
import dev.kodakiko.ui.core.screens.FormScreen
import dev.kodakiko.ui.core.util.secondaryItemAlpha
import dev.zacsweers.metrox.viewmodel.metroViewModel

@Composable
fun RecoveryKeyScreen(viewModel: RecoveryKeyViewModel = metroViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    AuthEvents(viewModel.events)
    RecoveryKeyContent(
        state = state,
        onWrittenDown = viewModel::onWrittenDown,
        onTypedChange = viewModel::onTypedChange,
        onConfirm = viewModel::onConfirm,
    )
}

@Composable
fun RecoveryKeyContent(
    state: RecoveryKeyState,
    onWrittenDown: () -> Unit,
    onTypedChange: (Int, String) -> Unit,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val words = state.words
    FormScreen(
        modifier = modifier.testTag(TestTags.RecoveryKey.SCREEN),
        icon = if (words == null) null else KodakikoIcons.Lock,
    ) {
        when {
            words == null -> Unit
            state.confirming -> ConfirmStep(state = state, onTypedChange = onTypedChange, onConfirm = onConfirm)
            else -> WordsStep(words = words, onWrittenDown = onWrittenDown)
        }
    }
}

@Composable
private fun WordsStep(words: List<String>, onWrittenDown: () -> Unit) {
    Text(
        text = stringResource(R.string.recovery_key_title),
        modifier = Modifier.testTag(TestTags.RecoveryKey.TITLE),
        style = MaterialTheme.typography.headlineLarge,
    )
    Text(
        text = stringResource(R.string.recovery_key_body),
        modifier = Modifier.testTag(TestTags.RecoveryKey.BODY),
        style = MaterialTheme.typography.bodyLarge,
    )
    WordGrid(words)
    FormButton(text = stringResource(R.string.recovery_key_done), onClick = onWrittenDown, testTag = TestTags.RecoveryKey.DONE)
}

@Composable
private fun WordGrid(words: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall)) {
        words.chunked(AuthConstants.RECOVERY_GRID_COLUMNS).forEachIndexed { row, pair ->
            Row(modifier = Modifier.fillMaxWidth()) {
                pair.forEachIndexed { column, word ->
                    WordCell(position = row * AuthConstants.RECOVERY_GRID_COLUMNS + column + 1, word = word, modifier = Modifier.weight(1f))
                }
                if (pair.size < AuthConstants.RECOVERY_GRID_COLUMNS) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun WordCell(position: Int, word: String, modifier: Modifier = Modifier) {
    Row(modifier = modifier.padding(vertical = MaterialTheme.padding.extraSmall)) {
        Text(
            text = position.toString(),
            modifier = Modifier
                .widthIn(min = AuthUiConstants.WORD_NUMBER_WIDTH)
                .secondaryItemAlpha(),
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            text = word,
            modifier = Modifier.testTag(TestTags.listItem(TestTags.RecoveryKey.WORD, position)),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun ConfirmStep(state: RecoveryKeyState, onTypedChange: (Int, String) -> Unit, onConfirm: () -> Unit) {
    Text(
        text = stringResource(R.string.recovery_key_confirm_title),
        modifier = Modifier.testTag(TestTags.RecoveryKey.CONFIRM_TITLE),
        style = MaterialTheme.typography.headlineLarge,
    )
    if (state.mismatch) {
        Banner(text = stringResource(R.string.recovery_key_confirm_wrong), kind = BannerKind.ERROR, testTag = TestTags.RecoveryKey.CONFIRM_WRONG)
    }
    state.error?.let { ErrorBanner(message = it) }
    state.positions.forEachIndexed { index, position ->
        val label = stringResource(R.string.recovery_key_confirm_word, position)
        Text(
            text = label,
            modifier = Modifier.testTag(TestTags.listItem(TestTags.RecoveryKey.CONFIRM_LABEL, index)),
            style = MaterialTheme.typography.titleMedium,
        )
        val last = index == state.positions.lastIndex
        FormTextField(
            value = state.typed.getOrElse(index) { "" },
            onValueChange = { onTypedChange(index, it) },
            label = null,
            testTag = TestTags.listItem(TestTags.RecoveryKey.CONFIRM_FIELD, index),
            imeAction = if (last) ImeAction.Done else ImeAction.Next,
            onImeAction = if (last) onConfirm else null,
        )
    }
    FormButton(
        text = stringResource(R.string.recovery_key_confirm_submit),
        onClick = onConfirm,
        testTag = TestTags.RecoveryKey.CONFIRM_SUBMIT,
        busy = state.busy,
    )
}
