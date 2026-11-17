package dev.kodakiko.app.settings

import android.content.Context
import android.net.Uri
import android.os.StatFs
import android.provider.OpenableColumns
import dev.kodakiko.account.startKdfParams
import dev.kodakiko.app.gallery.AccountMedia
import dev.kodakiko.app.sync.AppSyncState
import dev.kodakiko.crypto.Sodium
import dev.kodakiko.data.db.OutboxRow
import dev.kodakiko.data.media.CacheUsage
import dev.kodakiko.data.media.MediaUnavailableException
import dev.kodakiko.data.media.ThumbnailModeSwitch
import dev.kodakiko.data.secure.AccountMode
import dev.kodakiko.platform.picker.FolderPermissions
import dev.kodakiko.platform.picker.FolderWriter
import dev.kodakiko.service.account.AccountService
import dev.kodakiko.service.account.AuthService
import dev.kodakiko.service.account.RowStorePort
import dev.kodakiko.service.backup.ExportDestination
import dev.kodakiko.service.backup.ExportService
import dev.kodakiko.service.backup.ExportWriteException
import dev.kodakiko.service.backup.RestoreFile
import dev.kodakiko.service.backup.RestoreService
import dev.kodakiko.service.backup.SafExportDestination
import dev.kodakiko.service.backup.encryptedDestination
import dev.kodakiko.service.lock.LockControl
import dev.kodakiko.service.sync.SyncService
import dev.kodakiko.service.sync.SyncStatus
import dev.kodakiko.ui.feature.settings.DeviceStorage
import dev.kodakiko.ui.feature.settings.SettingsAccount
import dev.kodakiko.ui.feature.settings.SettingsBackup
import dev.kodakiko.ui.feature.settings.SettingsStorage
import dev.kodakiko.ui.feature.settings.SettingsSync
import dev.kodakiko.ui.feature.settings.SettingsThumbnails
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppSettingsAccount(
    private val auth: AuthService,
    private val accounts: AccountService,
    private val lockControl: LockControl,
) : SettingsAccount {
    override val mode: StateFlow<AccountMode> = auth.mode.flow

    override val roleChanges: Flow<Unit> = accounts.user.flow.drop(1).map {}

    override suspend fun isAdmin(): Boolean = auth.mode.value == AccountMode.SERVER && accounts.isAdmin()

    override suspend fun lockNow() {
        lockControl.lockNow()
    }

    override suspend fun signOut() {
        auth.signOut()
    }
}

class AppSettingsSync(
    private val sync: SyncService,
    private val state: AppSyncState,
    private val rowStore: RowStorePort,
    private val ioDispatcher: CoroutineDispatcher,
) : SettingsSync {
    override val status: StateFlow<SyncStatus> = state.status

    override suspend fun syncNow() {
        sync.syncNow()
    }

    override suspend fun retryDead() {
        sync.retryDead()
    }

    override suspend fun outbox(): List<OutboxRow> = withContext(ioDispatcher) { rowStore.transaction { it.outbox.all() } }
}

class AppSettingsStorage(
    private val context: Context,
    private val media: AccountMedia,
    private val ioDispatcher: CoroutineDispatcher,
) : SettingsStorage {
    override suspend fun usage(): CacheUsage = withContext(ioDispatcher) { opened().originals.usage() }

    override suspend fun device(): DeviceStorage? = withContext(ioDispatcher) {
        val stats = StatFs(context.filesDir.path)
        DeviceStorage(freeBytes = stats.availableBytes, totalBytes = stats.totalBytes)
    }

    override suspend fun enforceCap() {
        withContext(ioDispatcher) { opened().originals.enforceCap() }
    }

    override suspend fun clearOriginals() {
        withContext(ioDispatcher) { opened().originals.clearOriginals() }
    }

    private fun opened(): AccountMedia.Opened = media.current() ?: throw MediaUnavailableException()
}

class AppSettingsThumbnails(
    private val media: AccountMedia,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
) : SettingsThumbnails {
    override val modeSwitch: StateFlow<ThumbnailModeSwitch> = media.modeSwitch

    override suspend fun switchMode(encrypt: Boolean) {
        scope.launch(ioDispatcher) { media.current()?.thumbnails?.switchMode(encrypt) }.join()
    }
}

class AppSettingsBackup(
    private val context: Context,
    private val sodium: Sodium,
    override val exporter: ExportService,
    override val restorer: RestoreService,
    private val sources: RestoreSources,
    private val ioDispatcher: CoroutineDispatcher,
) : SettingsBackup {
    override suspend fun openFolder(folder: Uri): ExportDestination = withContext(ioDispatcher) {
        FolderPermissions.take(context, folder)
        val writer = FolderWriter(context, folder)
        if (!writer.canWrite()) throw ExportWriteException(folder.toString())
        SafExportDestination(createFile = writer::createFile, deleteFile = writer::delete)
    }

    override suspend fun encrypt(destination: ExportDestination, passphrase: String): ExportDestination = withContext(ioDispatcher) {
        val params = startKdfParams(ByteArray(0))
        encryptedDestination(sodium, destination, passphrase, params.opsLimit, params.memLimitBytes)
    }

    override suspend fun readFiles(files: List<Uri>): List<RestoreFile> = withContext(ioDispatcher) {
        val opened = mutableListOf<RestoreFile>()
        try {
            for (uri in files) opened += RestoreFile(displayName(uri), sources.open(uri))
            opened
        } catch (failure: Throwable) {
            opened.forEach(RestoreFile::close)
            throw failure
        }
    }

    private fun displayName(uri: Uri): String {
        val named = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
        return named ?: uri.lastPathSegment.orEmpty()
    }
}
