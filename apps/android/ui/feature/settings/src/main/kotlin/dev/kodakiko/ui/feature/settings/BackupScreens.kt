@file:OptIn(ExperimentalMaterial3Api::class)

package dev.kodakiko.ui.feature.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kodakiko.platform.picker.DocumentPicking
import dev.kodakiko.service.backup.ExportPhase
import dev.kodakiko.service.backup.ExportProgress
import dev.kodakiko.ui.core.R
import dev.kodakiko.ui.core.TestTags
import dev.kodakiko.ui.core.components.AppBar
import dev.kodakiko.ui.core.components.Banner
import dev.kodakiko.ui.core.components.BannerKind
import dev.kodakiko.ui.core.components.FormButton
import dev.kodakiko.ui.core.components.FormButtonStyle
import dev.kodakiko.ui.core.components.FormTextField
import dev.kodakiko.ui.core.format.formatBytes
import dev.zacsweers.metrox.viewmodel.metroViewModel

@Composable
fun ExportScreen(viewModel: ExportViewModel = metroViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val folders = DocumentPicking.openFolder()
    val folderPicker = rememberLauncherForActivityResult(folders.contract) { folder -> viewModel.onAction(ExportAction.FolderChosen(folder)) }
    SettingsEventsEffect(viewModel.events, onPickFolder = { folderPicker.launch(folders.input) })
    ExportContent(state = state, onAction = viewModel::onAction)
}

@Composable
fun ImportScreen(viewModel: ImportViewModel = metroViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val documents = DocumentPicking.openMany()
    val filePicker = rememberLauncherForActivityResult(documents.contract) { files -> viewModel.onAction(ImportAction.FilesChosen(files)) }
    SettingsEventsEffect(viewModel.events, onPickFiles = { filePicker.launch(documents.input) })
    ImportContent(state = state, onAction = viewModel::onAction)
}

@Composable
internal fun FormPage(
    title: String,
    screenTag: String,
    onBack: () -> Unit,
    backTag: String = TestTags.Settings.BACK,
    content: @Composable ColumnScope.() -> Unit,
) {
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground) {
        BackupPage(title = title, screenTag = screenTag, onBack = onBack, backTag = backTag, content = content)
    }
}

@Composable
internal fun BackupPage(
    title: String,
    screenTag: String,
    onBack: () -> Unit,
    backTag: String = TestTags.Settings.BACK,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scroll = rememberScrollState()
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .testTag(screenTag),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .windowInsetsPadding(WindowInsets.navigationBars),
        ) {
            AppBar(
                title = title,
                navigateUp = onBack,
                navigateUpTestTag = backTag,
                modifier = Modifier
                    .zIndex(1f)
                    .offset { IntOffset(0, scroll.value) }
                    .background(MaterialTheme.colorScheme.background),
            )
            Column(
                modifier = Modifier.padding(SettingsDimens.SCREEN_PADDING),
                verticalArrangement = Arrangement.spacedBy(SettingsDimens.SECTION_SPACING),
                content = content,
            )
        }
    }
}

@Composable
private fun phaseLabel(phase: ExportPhase): String = stringResource(
    when (phase) {
        ExportPhase.PLANNING -> R.string.settings_phase_planning
        ExportPhase.DOWNLOADING -> R.string.settings_phase_downloading
        ExportPhase.WRITING -> R.string.settings_phase_writing
        ExportPhase.DONE -> R.string.settings_phase_done
    },
)

@Composable
private fun progressText(progress: ExportProgress): String = stringResource(
    R.string.settings_export_progress,
    phaseLabel(progress.phase),
    progress.entriesDone,
    progress.entriesTotal,
    progress.partNumber,
    progress.partsTotal,
    formatBytes(progress.bytesWritten),
)

@Composable
private fun ExportContent(state: ExportState, onAction: (ExportAction) -> Unit) {
    BackupPage(title = stringResource(R.string.settings_row_export), screenTag = TestTags.Settings.EXPORT_SCREEN, onBack = { onAction(ExportAction.Back) }) {
        BodyText(text = stringResource(if (state.encrypt) R.string.export_explain_encrypted else R.string.settings_export_explain))
        BodyText(text = stringResource(R.string.settings_export_keep_open))
        val progress = state.progress
        if (state.running && progress != null) BodyText(text = progressText(progress), testTag = TestTags.Export.PROGRESS)
        state.summary?.let { summary ->
            Banner(
                text = pluralStringResource(R.plurals.settings_export_done, summary.parts, summary.parts, formatBytes(summary.bytes)),
                kind = BannerKind.INFO,
                testTag = TestTags.Export.SUMMARY,
            )
        }
        if (state.skipped > 0) BodyText(text = pluralStringResource(R.plurals.settings_export_skipped, state.skipped, state.skipped), testTag = TestTags.Export.SKIPPED)
        state.message?.let { message ->
            Banner(
                text = stringResource(if (message == ExportMessage.CANCELLED) R.string.settings_export_cancelled else R.string.settings_export_failed),
                kind = BannerKind.WARNING,
                testTag = TestTags.Export.MESSAGE,
            )
        }
        if (state.running) {
            FormButton(
                text = stringResource(R.string.settings_export_cancel),
                onClick = { onAction(ExportAction.Cancel) },
                testTag = TestTags.Export.CANCEL,
                style = FormButtonStyle.SECONDARY,
            )
        } else {
            ExportOptions(state, onAction)
            FormButton(text = stringResource(R.string.settings_export_start), onClick = { onAction(ExportAction.Start) }, testTag = TestTags.Export.START)
        }
    }
}

@Composable
private fun ExportOptions(state: ExportState, onAction: (ExportAction) -> Unit) {
    HorizontalDivider()
    ToggleRow(
        title = stringResource(R.string.export_encrypt),
        summary = stringResource(R.string.export_encrypt_summary),
        checked = state.encrypt,
        onCheckedChange = { onAction(ExportAction.SetEncrypt(it)) },
        testTag = TestTags.Export.ENCRYPT,
    )
    if (!state.encrypt) return
    FormTextField(
        value = state.passphrase,
        onValueChange = { onAction(ExportAction.SetPassphrase(it)) },
        label = stringResource(R.string.export_passphrase),
        testTag = TestTags.Export.PASSPHRASE,
        password = true,
    )
    val problem = state.problem.takeIf { state.passphrase.isNotEmpty() }
    FormTextField(
        value = state.confirm,
        onValueChange = { onAction(ExportAction.SetConfirm(it)) },
        label = stringResource(R.string.export_passphrase_confirm),
        testTag = TestTags.Export.PASSPHRASE_CONFIRM,
        password = true,
        imeAction = ImeAction.Done,
        errorText = problem?.let {
            stringResource(if (it == ExportPassphraseProblem.SHORT) R.string.export_passphrase_short else R.string.export_passphrase_mismatch)
        },
    )
}

@Composable
private fun ImportContent(state: ImportState, onAction: (ImportAction) -> Unit) {
    BackupPage(title = stringResource(R.string.import_title), screenTag = TestTags.Import.SCREEN, onBack = { onAction(ImportAction.Back) }) {
        BodyText(text = stringResource(R.string.import_explain))
        FormTextField(
            value = state.passphrase,
            onValueChange = { onAction(ImportAction.SetPassphrase(it)) },
            label = stringResource(R.string.import_passphrase),
            testTag = TestTags.Import.PASSPHRASE,
            password = true,
            imeAction = ImeAction.Done,
        )
        state.progress?.let { BodyText(text = stringResource(R.string.import_progress, it.done, it.total), testTag = TestTags.Import.PROGRESS) }
        state.summary?.let { summary ->
            Banner(
                text = stringResource(R.string.import_done, summary.entries, summary.media, summary.duplicates),
                kind = BannerKind.INFO,
                testTag = TestTags.Import.SUMMARY,
            )
            if (summary.failed.isNotEmpty()) {
                BodyText(text = pluralStringResource(R.plurals.import_failed, summary.failed.size, summary.failed.size), testTag = TestTags.Import.FAILED)
            }
        }
        state.error?.let { error ->
            Banner(
                text = stringResource(if (error == ImportError.WRONG_PASSPHRASE) R.string.import_passphrase_wrong else R.string.import_error),
                kind = BannerKind.ERROR,
                testTag = TestTags.Import.ERROR,
            )
        }
        FormButton(text = stringResource(R.string.import_choose), onClick = { onAction(ImportAction.Start) }, testTag = TestTags.Import.START, busy = state.busy)
    }
}
