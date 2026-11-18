package dev.kodakiko.app.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import dev.kodakiko.app.edit.EditAdapterConstants
import dev.kodakiko.data.log.DebugLogConstants
import dev.kodakiko.data.log.DebugLogStore
import dev.kodakiko.log.DebugLogInfo
import dev.kodakiko.platform.picker.SharedFile
import dev.kodakiko.platform.picker.Sharing
import dev.kodakiko.service.diagnostics.DiagnosticsService
import dev.kodakiko.service.shareout.ShareLauncher
import dev.kodakiko.ui.feature.settings.DebugLogPort
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File

class AppDebugLogPort(
    private val context: Context,
    private val store: DebugLogStore,
    private val diagnostics: DiagnosticsService,
    private val launcher: ShareLauncher,
    private val ioDispatcher: CoroutineDispatcher,
) : DebugLogPort {
    override val info: Flow<DebugLogInfo> = store.info()

    override val lines: Flow<List<String>> = store.lines()

    override suspend fun setEnabled(on: Boolean) = store.setEnabled(on)

    override suspend fun clear() = store.clear()

    override suspend fun copy(): Int {
        val text = header() + store.snapshot(DebugLogConstants.COPY_MAX_BYTES)
        withContext(Dispatchers.Main.immediate) {
            val clipboard = context.getSystemService(ClipboardManager::class.java)
            clipboard.setPrimaryClip(ClipData.newPlainText(DebugLogConstants.SHARE_FILE_NAME, text))
        }
        return text.lines().count { it.isNotBlank() }
    }

    override suspend fun share() {
        val file = store.copyToShareFile(File(context.cacheDir, DebugLogConstants.SHARE_DIRECTORY), header())
        val chooser = withContext(ioDispatcher) {
            Sharing.chooser(
                context,
                context.packageName + EditAdapterConstants.FILE_PROVIDER_SUFFIX,
                listOf(SharedFile(file, DebugLogConstants.SHARE_FILE_NAME, SettingsAdapterConstants.TEXT_MIME)),
                null,
            )
        }
        withContext(Dispatchers.Main.immediate) { launcher.launch(chooser) }
    }

    private suspend fun header(): String = withContext(ioDispatcher) {
        diagnostics.debugInfoJson() + DebugLogConstants.NEWLINE + DebugLogConstants.NEWLINE
    }
}
