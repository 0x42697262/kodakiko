package dev.kodakiko.ui.feature.settings

import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.kodakiko.service.backup.ExportCancelledException
import dev.kodakiko.service.backup.ExportPhase
import dev.kodakiko.service.backup.ExportProgress
import dev.kodakiko.service.backup.ExportSummary
import dev.kodakiko.service.backup.RestorePassphraseException
import dev.kodakiko.service.backup.RestoreProgress
import dev.kodakiko.service.backup.RestoreSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class ExportPassphraseProblem { SHORT, MISMATCH }

enum class ExportMessage { CANCELLED, FAILED }

private val PLANNING = ExportProgress(phase = ExportPhase.PLANNING, entriesDone = 0, entriesTotal = 0, bytesWritten = 0, partNumber = 0, partsTotal = 0)

@Immutable
data class ExportState(
    val encrypt: Boolean,
    val passphrase: String,
    val confirm: String,
    val progress: ExportProgress?,
    val summary: ExportSummary?,
    val message: ExportMessage?,
    val running: Boolean,
) {
    val problem: ExportPassphraseProblem?
        get() = when {
            !encrypt -> null
            passphrase.length < SettingsConstants.MIN_PASSPHRASE_CHARS -> ExportPassphraseProblem.SHORT
            passphrase != confirm -> ExportPassphraseProblem.MISMATCH
            else -> null
        }

    val skipped: Int
        get() = summary?.let { it.skippedMissing + it.skippedTooLarge + it.skippedUnreadable } ?: 0

    override fun toString(): String = ExportState::class.java.simpleName
}

sealed interface ExportAction {
    data class SetEncrypt(val on: Boolean) : ExportAction

    data class SetPassphrase(val value: String) : ExportAction

    data class SetConfirm(val value: String) : ExportAction

    data object Start : ExportAction

    data class FolderChosen(val folder: Uri?) : ExportAction

    data object Cancel : ExportAction

    data object Back : ExportAction
}

class ExportViewModel(private val deps: SettingsDeps) : ViewModel() {
    private val mutableState = MutableStateFlow(
        ExportState(encrypt = false, passphrase = "", confirm = "", progress = null, summary = null, message = null, running = false),
    )
    private val channel = Channel<SettingsEvent>(Channel.BUFFERED)

    val state: StateFlow<ExportState> = mutableState.asStateFlow()

    val events: Flow<SettingsEvent> = channel.receiveAsFlow()

    fun onAction(action: ExportAction) {
        when (action) {
            is ExportAction.SetEncrypt -> mutableState.update { it.copy(encrypt = action.on) }
            is ExportAction.SetPassphrase -> mutableState.update { it.copy(passphrase = action.value) }
            is ExportAction.SetConfirm -> mutableState.update { it.copy(confirm = action.value) }
            ExportAction.Start -> start()
            is ExportAction.FolderChosen -> action.folder?.let(::run)
            ExportAction.Cancel -> deps.backup.exporter.cancel()
            ExportAction.Back -> channel.trySend(SettingsEvent.Back)
        }
    }

    private fun start() {
        val current = mutableState.value
        if (current.running) return
        mutableState.update { it.copy(message = null, summary = null) }
        if (current.problem != null) return
        channel.trySend(SettingsEvent.PickFolder)
    }

    private fun run(folder: Uri) {
        val current = mutableState.value
        if (current.running || current.problem != null) return
        mutableState.update { it.copy(running = true, progress = PLANNING, summary = null, message = null) }
        viewModelScope.launch {
            try {
                val opened = deps.backup.openFolder(folder)
                val destination = if (current.encrypt) deps.backup.encrypt(opened, current.passphrase) else opened
                val summary = deps.backup.exporter.export(destination) { progress -> mutableState.update { it.copy(progress = progress) } }
                mutableState.update { it.copy(summary = summary, running = false) }
            } catch (cancelled: ExportCancelledException) {
                finish(ExportMessage.CANCELLED)
            } catch (cancelled: CancellationException) {
                finish(ExportMessage.CANCELLED)
                throw cancelled
            } catch (_: Exception) {
                finish(ExportMessage.FAILED)
            }
        }
    }

    private fun finish(message: ExportMessage) {
        mutableState.update { it.copy(message = message, progress = null, running = false) }
    }
}

enum class ImportError { WRONG_PASSPHRASE, DAMAGED }

@Immutable
data class ImportState(
    val passphrase: String,
    val busy: Boolean,
    val progress: RestoreProgress?,
    val summary: RestoreSummary?,
    val error: ImportError?,
) {
    override fun toString(): String = ImportState::class.java.simpleName
}

sealed interface ImportAction {
    data class SetPassphrase(val value: String) : ImportAction

    data object Start : ImportAction

    data class FilesChosen(val files: List<Uri>) : ImportAction

    data object Back : ImportAction
}

class ImportViewModel(private val deps: SettingsDeps) : ViewModel() {
    private val mutableState = MutableStateFlow(ImportState(passphrase = "", busy = false, progress = null, summary = null, error = null))
    private val channel = Channel<SettingsEvent>(Channel.BUFFERED)

    val state: StateFlow<ImportState> = mutableState.asStateFlow()

    val events: Flow<SettingsEvent> = channel.receiveAsFlow()

    fun onAction(action: ImportAction) {
        when (action) {
            is ImportAction.SetPassphrase -> mutableState.update { it.copy(passphrase = action.value) }
            ImportAction.Start -> {
                if (mutableState.value.busy) return
                mutableState.update { it.copy(error = null, summary = null) }
                channel.trySend(SettingsEvent.PickFiles)
            }
            is ImportAction.FilesChosen -> if (action.files.isNotEmpty()) restore(action.files)
            ImportAction.Back -> channel.trySend(SettingsEvent.Back)
        }
    }

    private fun restore(files: List<Uri>) {
        val current = mutableState.value
        if (current.busy) return
        mutableState.update { it.copy(busy = true, error = null, summary = null) }
        viewModelScope.launch {
            try {
                val read = deps.backup.readFiles(files)
                val passphrase = current.passphrase.ifEmpty { null }
                val summary = deps.backup.restorer.restore(read, passphrase) { progress -> mutableState.update { it.copy(progress = progress) } }
                mutableState.update { it.copy(summary = summary) }
            } catch (wrong: RestorePassphraseException) {
                mutableState.update { it.copy(error = ImportError.WRONG_PASSPHRASE) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.update { it.copy(error = ImportError.DAMAGED) }
            } finally {
                mutableState.update { it.copy(busy = false, progress = null) }
            }
        }
    }
}
