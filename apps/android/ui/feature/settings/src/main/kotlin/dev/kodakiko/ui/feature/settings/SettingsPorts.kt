package dev.kodakiko.ui.feature.settings

import android.net.Uri
import dev.kodakiko.data.db.DeviceSettings
import dev.kodakiko.data.db.OutboxRow
import dev.kodakiko.data.media.CacheUsage
import dev.kodakiko.data.media.ThumbnailModeSwitch
import dev.kodakiko.data.secure.AccountMode
import dev.kodakiko.service.account.ConfigService
import dev.kodakiko.service.backup.ExportDestination
import dev.kodakiko.service.backup.ExportService
import dev.kodakiko.service.backup.RestoreFile
import dev.kodakiko.service.backup.RestoreService
import dev.kodakiko.service.sync.SyncStatus
import dev.kodakiko.ui.core.navigation.Route
import kotlinx.coroutines.flow.StateFlow

interface SettingsStore {
    val settings: StateFlow<DeviceSettings>

    fun update(change: (DeviceSettings) -> DeviceSettings)
}

interface SettingsAccount {
    val mode: StateFlow<AccountMode>

    suspend fun isAdmin(): Boolean

    suspend fun lockNow()

    suspend fun signOut()
}

interface SettingsSync {
    val status: StateFlow<SyncStatus>

    suspend fun syncNow()

    suspend fun retryDead()

    suspend fun outbox(): List<OutboxRow>
}

data class DeviceStorage(val freeBytes: Long, val totalBytes: Long)

interface SettingsStorage {
    suspend fun usage(): CacheUsage

    suspend fun device(): DeviceStorage?

    suspend fun enforceCap()

    suspend fun clearOriginals()
}

interface SettingsThumbnails {
    val modeSwitch: StateFlow<ThumbnailModeSwitch>

    suspend fun switchMode(encrypt: Boolean)
}

fun interface SettingsBiometric {
    fun available(): Boolean
}

fun interface SettingsLocation {
    suspend fun probe(timeoutMs: Long): Boolean
}

interface SettingsBackup {
    val exporter: ExportService

    val restorer: RestoreService

    suspend fun openFolder(folder: Uri): ExportDestination

    suspend fun encrypt(destination: ExportDestination, passphrase: String): ExportDestination

    suspend fun readFiles(files: List<Uri>): List<RestoreFile>
}

class SettingsHighlight {
    private val lock = Any()
    private var pending: Pair<Route, String>? = null

    fun request(route: Route, key: String?) {
        synchronized(lock) { pending = key?.let { route to it } }
    }

    fun take(route: Route): String? = synchronized(lock) {
        val current = pending ?: return null
        if (current.first != route) return null
        pending = null
        current.second
    }
}

class SettingsDeps(
    val settings: SettingsStore,
    val account: SettingsAccount,
    val config: ConfigService,
    val sync: SettingsSync,
    val storage: SettingsStorage,
    val thumbnails: SettingsThumbnails,
    val biometric: SettingsBiometric,
    val location: SettingsLocation,
    val backup: SettingsBackup,
    val highlight: SettingsHighlight,
    val dynamicColorAvailable: Boolean,
)

sealed interface SettingsEvent {
    data class Open(val route: Route) : SettingsEvent

    data class Replace(val route: Route) : SettingsEvent

    data object Back : SettingsEvent

    data object PickFolder : SettingsEvent

    data object PickFiles : SettingsEvent
}
