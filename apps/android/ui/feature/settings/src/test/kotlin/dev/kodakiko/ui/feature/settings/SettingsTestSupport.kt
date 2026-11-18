@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.kodakiko.ui.feature.settings

import android.content.Context
import android.content.res.Resources
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.github.takahirom.roborazzi.roborazziSystemPropertyTaskType
import dev.kodakiko.contract.OutboxKind
import dev.kodakiko.contract.PublicConfigDto
import dev.kodakiko.contract.SignupMode
import dev.kodakiko.data.db.AppTheme
import dev.kodakiko.data.db.DeviceSettings
import dev.kodakiko.data.db.DeviceSettingsDefaults
import dev.kodakiko.data.db.OutboxRow
import dev.kodakiko.data.db.OutboxState
import dev.kodakiko.data.media.CacheUsage
import dev.kodakiko.data.media.ThumbnailModeSwitch
import dev.kodakiko.data.secure.AccountMode
import dev.kodakiko.service.account.ConfigService
import dev.kodakiko.service.backup.ExportCancelledException
import dev.kodakiko.service.backup.ExportDestination
import dev.kodakiko.service.backup.ExportPart
import dev.kodakiko.service.backup.ExportPhase
import dev.kodakiko.service.backup.ExportProgress
import dev.kodakiko.service.backup.ExportService
import dev.kodakiko.service.backup.ExportSummary
import dev.kodakiko.service.backup.RestoreFile
import dev.kodakiko.service.backup.RestoreProgress
import dev.kodakiko.service.backup.RestoreService
import dev.kodakiko.service.backup.RestoreSummary
import dev.kodakiko.service.sync.SyncStatus
import dev.kodakiko.ui.core.navigation.LocalRouteNavigator
import dev.kodakiko.ui.core.navigation.Route
import dev.kodakiko.ui.core.navigation.RouteNavigator
import dev.kodakiko.ui.core.theme.KodakikoTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import java.io.File

object SettingsTestTags {
    const val DEAD_ITEM = "settings.deadItem"
    const val EXPORT_ENCRYPT = "export.encrypt"

    fun option(base: String, value: Any): String = "$base.$value"

    fun dialog(base: String): String = "$base.dialog"

    fun switch(base: String): String = "$base.switch"
}

const val MB = 1_048_576L
const val GB = 1_073_741_824L
const val ENTRY_A = "entryaaaaaaaaaaaaaaaa1"
const val ENTRY_B = "entrybbbbbbbbbbbbbbbb2"
const val LONG_PASSPHRASE = "a long export passphrase"
const val LAST_SUCCESS_MS = 1_795_000_000_000L
const val LOCATION_PROBE_MS = 10_000L

val defaults: DeviceSettings get() = DeviceSettingsDefaults.ANDROID

val context: Context get() = ApplicationProvider.getApplicationContext()

val resources: Resources get() = context.resources

fun text(@StringRes id: Int, vararg args: Any): String = context.getString(id, *args)

fun plural(@PluralsRes id: Int, quantity: Int, vararg args: Any): String = resources.getQuantityString(id, quantity, *args)

class MainDispatcherRule : TestWatcher() {
    override fun starting(description: Description) {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}

fun publicConfig(sharing: Boolean = true, links: Boolean = true): PublicConfigDto = PublicConfigDto(
    signupMode = SignupMode.INVITE,
    sharingEnabled = sharing,
    publicLinksEnabled = links,
    maxMediaPerEntry = 20,
    maxUploadBytes = 3_758_096_384L,
    defaultQuotaBytes = 10 * GB,
    trashDays = 30,
    accountDeletionGraceDays = 7,
    serverVersion = "0.3.1",
)

fun idleStatus(queued: Int = 0, dead: Int = 0, waitingForWifi: Int = 0, lastSuccessAt: Long? = null, offline: Boolean = false): SyncStatus =
    SyncStatus(running = false, queued = queued, dead = dead, waitingForWifi = waitingForWifi, lastSuccessAt = lastSuccessAt, offline = offline)

fun outboxRow(id: Long, kind: String, entryId: String?, state: OutboxState = OutboxState.DEAD, error: String? = null): OutboxRow = OutboxRow(
    id = id,
    kind = kind,
    payload = JsonObject(if (entryId == null) emptyMap() else mapOf("entryId" to JsonPrimitive(entryId))),
    state = state,
    createdAt = 1_000L + id,
    attempts = 5,
    nextAttemptAt = 2_000L,
    lastErrorCode = error,
)

fun deadRow(id: Long, kind: OutboxKind, entryId: String, error: String? = null): OutboxRow = outboxRow(id, kind.wire, entryId, OutboxState.DEAD, error)

class FakeSettingsStore(initial: DeviceSettings = DeviceSettingsDefaults.ANDROID) : SettingsStore {
    private val state = MutableStateFlow(initial)
    var updates = 0

    override val settings: StateFlow<DeviceSettings> = state.asStateFlow()

    val value: DeviceSettings get() = state.value

    fun set(next: DeviceSettings) {
        state.value = next
    }

    override fun update(change: (DeviceSettings) -> DeviceSettings) {
        updates += 1
        state.update(change)
    }
}

class FakeSettingsAccount(mode: AccountMode = AccountMode.SERVER) : SettingsAccount {
    val modeState = MutableStateFlow(mode)
    var admin = false
    var adminFailure: Throwable? = null
    var adminCalls = 0
    var locks = 0
    var signOuts = 0

    override val mode: StateFlow<AccountMode> = modeState.asStateFlow()

    override suspend fun isAdmin(): Boolean {
        adminCalls += 1
        adminFailure?.let { throw it }
        return admin
    }

    override suspend fun lockNow() {
        locks += 1
    }

    override suspend fun signOut() {
        signOuts += 1
    }
}

class FakeConfigService(var config: PublicConfigDto? = publicConfig()) : ConfigService {
    private val listeners = mutableListOf<(PublicConfigDto) -> Unit>()

    override fun current(): PublicConfigDto? = config

    override suspend fun refresh(): PublicConfigDto? = config

    override fun accept(config: PublicConfigDto) {
        this.config = config
        listeners.toList().forEach { it(config) }
    }

    override fun subscribe(listener: (PublicConfigDto) -> Unit): () -> Unit {
        listeners += listener
        return { listeners -= listener }
    }
}

class FakeSettingsSync : SettingsSync {
    val statusState = MutableStateFlow(idleStatus())
    val rows = mutableListOf<OutboxRow>()
    val calls = mutableListOf<String>()
    var syncGate: CompletableDeferred<Unit>? = null
    var syncFailure: Throwable? = null

    override val status: StateFlow<SyncStatus> = statusState.asStateFlow()

    override suspend fun syncNow() {
        calls += SYNC_NOW
        syncGate?.await()
        syncFailure?.let { throw it }
    }

    override suspend fun retryDead() {
        calls += RETRY_DEAD
        val revived = rows.map { if (it.state == OutboxState.DEAD) it.copy(state = OutboxState.QUEUED, attempts = 0, lastErrorCode = null) else it }
        rows.clear()
        rows += revived
    }

    override suspend fun outbox(): List<OutboxRow> {
        calls += OUTBOX
        return rows.toList()
    }

    companion object {
        const val SYNC_NOW = "syncNow"
        const val RETRY_DEAD = "retryDead"
        const val OUTBOX = "outbox"
    }
}

class FakeSettingsStorage(private val store: FakeSettingsStore) : SettingsStorage {
    var usage = CacheUsage(originalsBytes = 300 * MB, thumbnailsBytes = 12 * MB, pinnedBytes = 40 * MB, pendingUploadBytes = 5 * MB)
    var device: DeviceStorage? = DeviceStorage(freeBytes = 20 * GB, totalBytes = 64 * GB)
    val calls = mutableListOf<String>()
    val capsAtEnforce = mutableListOf<Long>()

    override suspend fun usage(): CacheUsage {
        calls += USAGE
        return usage
    }

    override suspend fun device(): DeviceStorage? {
        calls += DEVICE
        return device
    }

    override suspend fun enforceCap() {
        calls += ENFORCE_CAP
        capsAtEnforce += store.value.originalCacheCapBytes
    }

    override suspend fun clearOriginals() {
        calls += CLEAR_ORIGINALS
        usage = usage.copy(originalsBytes = 0)
    }

    companion object {
        const val USAGE = "usage"
        const val DEVICE = "device"
        const val ENFORCE_CAP = "enforceCap"
        const val CLEAR_ORIGINALS = "clearOriginals"
    }
}

class FakeSettingsThumbnails(private val store: FakeSettingsStore) : SettingsThumbnails {
    val modeSwitchState = MutableStateFlow(ThumbnailModeSwitch.IDLE)
    val switches = mutableListOf<Boolean>()
    val settingAtSwitch = mutableListOf<Boolean>()

    override val modeSwitch: StateFlow<ThumbnailModeSwitch> = modeSwitchState.asStateFlow()

    override suspend fun switchMode(encrypt: Boolean) {
        switches += encrypt
        settingAtSwitch += store.value.encryptThumbnails
    }
}

class FakeSettingsLocation(private val store: FakeSettingsStore) : SettingsLocation {
    var result = true
    val timeouts = mutableListOf<Long>()
    val settingAtProbe = mutableListOf<Boolean>()
    var gate: CompletableDeferred<Unit>? = null

    override suspend fun probe(timeoutMs: Long): Boolean {
        timeouts += timeoutMs
        settingAtProbe += store.value.locationForCaptures
        gate?.await()
        return result
    }
}

class FakeExportPart : ExportPart {
    override fun write(bytes: ByteArray) = Unit

    override fun close() = Unit

    override fun abort() = Unit
}

class FakeDestination(val label: String) : ExportDestination {
    override val partMaxBytes: Long = GB

    override fun createPart(name: String): ExportPart = FakeExportPart()
}

class FakeExportService : ExportService {
    val destinations = mutableListOf<ExportDestination>()
    var progressSteps: List<ExportProgress> = emptyList()
    var progressGate: CompletableDeferred<Unit>? = null
    var gate: CompletableDeferred<Unit>? = null
    var failure: Throwable? = null
    var cancels = 0
    var summary = ExportSummary(parts = 2, entries = 2, mediaFiles = 2, bytes = 3 * MB, skippedUnreadable = 0, skippedMissing = 0, skippedTooLarge = 0)
    private var cancelled: CompletableDeferred<Unit>? = null

    override suspend fun export(destination: ExportDestination, onProgress: (ExportProgress) -> Unit): ExportSummary {
        destinations += destination
        val stop = CompletableDeferred<Unit>().also { cancelled = it }
        for ((index, step) in progressSteps.withIndex()) {
            onProgress(step)
            if (index == progressSteps.lastIndex) progressGate?.await()
        }
        gate?.let { waiting ->
            select<Unit> {
                waiting.onAwait {}
                stop.onAwait {}
            }
        }
        if (stop.isCompleted) throw ExportCancelledException()
        failure?.let { throw it }
        return summary
    }

    override fun cancel() {
        cancels += 1
        cancelled?.complete(Unit)
    }
}

class FakeRestoreService : RestoreService {
    val runs = mutableListOf<Pair<List<RestoreFile>, String?>>()
    var progressSteps: List<RestoreProgress> = emptyList()
    var progressGate: CompletableDeferred<Unit>? = null
    var failure: Throwable? = null
    var summary = RestoreSummary(entries = 2, media = 2, duplicates = 0, failed = emptyList())

    override suspend fun restore(files: List<RestoreFile>, passphrase: String?, onProgress: (RestoreProgress) -> Unit): RestoreSummary {
        runs += files to passphrase
        for ((index, step) in progressSteps.withIndex()) {
            onProgress(step)
            if (index == progressSteps.lastIndex) progressGate?.await()
        }
        failure?.let { throw it }
        return summary
    }
}

class FakeSettingsBackup : SettingsBackup {
    val exportService = FakeExportService()
    val restoreService = FakeRestoreService()
    val openedFolders = mutableListOf<Uri>()
    val encrypted = mutableListOf<Pair<ExportDestination, String>>()
    val readRequests = mutableListOf<List<Uri>>()
    var readFailure: Throwable? = null

    override val exporter: ExportService get() = exportService

    override val restorer: RestoreService get() = restoreService

    override suspend fun openFolder(folder: Uri): ExportDestination {
        openedFolders += folder
        return FakeDestination(folder.toString())
    }

    override suspend fun encrypt(destination: ExportDestination, passphrase: String): ExportDestination {
        encrypted += destination to passphrase
        return FakeDestination(KDKX_LABEL)
    }

    override suspend fun readFiles(files: List<Uri>): List<RestoreFile> {
        readRequests += files
        readFailure?.let { throw it }
        return files.map { RestoreFile(it.lastPathSegment.orEmpty(), byteArrayOf(1, 2, 3)) }
    }

    companion object {
        const val KDKX_LABEL = "kdkx"
    }
}

class SettingsHarness(mode: AccountMode = AccountMode.SERVER, initial: DeviceSettings = DeviceSettingsDefaults.ANDROID) {
    val store = FakeSettingsStore(initial)
    val account = FakeSettingsAccount(mode)
    val config = FakeConfigService()
    val sync = FakeSettingsSync()
    val storage = FakeSettingsStorage(store)
    val thumbnails = FakeSettingsThumbnails(store)
    val location = FakeSettingsLocation(store)
    val backup = FakeSettingsBackup()
    val highlight = SettingsHighlight()
    var biometric = true
    var dynamicColor = true
    val profile = FakeSettingsProfile()
    val credentials = FakeSettingsCredentials()
    val index = FakeIndexService()
    val diagnostics = FakeDiagnosticsService()
    val licenses = FakeSettingsLicenses()
    val admin = FakeAdminService()
    val share = FakeAdminShare()
    var nowMs = NOW_MS
    var appVersion = APP_VERSION
    var showDiagnostics = true

    val deps: SettingsDeps
        get() = SettingsDeps(
            settings = store,
            account = account,
            config = config,
            sync = sync,
            storage = storage,
            thumbnails = thumbnails,
            biometric = SettingsBiometric { biometric },
            location = location,
            backup = backup,
            highlight = highlight,
            dynamicColorAvailable = dynamicColor,
        )

    val accountDeps: AccountSettingsDeps
        get() = AccountSettingsDeps(settings = deps, profile = profile, credentials = credentials, nowMs = { nowMs })

    val advancedDeps: AdvancedSettingsDeps
        get() = AdvancedSettingsDeps(settings = deps, index = index, diagnostics = diagnostics, showDiagnostics = showDiagnostics, debugLog = debugLog)

    val debugLog = FakeDebugLogPort()

    val aboutDeps: AboutDeps
        get() = AboutDeps(settings = deps, appVersion = appVersion, licenses = licenses)

    val adminDeps: AdminDeps
        get() = AdminDeps(settings = deps, admin = admin, share = share, nowMs = { nowMs })
}

class RecordingNavigator(start: List<Route>) : RouteNavigator {
    val stack = start.toMutableList()
    val pushed = mutableListOf<Route>()
    val replaced = mutableListOf<Route>()
    val replacedAll = mutableListOf<Route>()
    var pops = 0

    override val routes: List<Route> get() = stack.toList()

    override fun push(route: Route) {
        pushed += route
        stack += route
    }

    override fun pop(): Boolean {
        pops += 1
        if (stack.size <= 1) return false
        stack.removeAt(stack.lastIndex)
        return true
    }

    override fun replace(route: Route) {
        replaced += route
        stack[stack.lastIndex] = route
    }

    override fun replaceAll(route: Route) {
        replacedAll += route
        stack.clear()
        stack += route
    }
}

fun progress(phase: ExportPhase, done: Int, total: Int, bytes: Long, part: Int, parts: Int): ExportProgress =
    ExportProgress(phase = phase, entriesDone = done, entriesTotal = total, bytesWritten = bytes, partNumber = part, partsTotal = parts)

fun AndroidComposeTestRule<*, ComponentActivity>.show(appTheme: AppTheme = AppTheme.LIGHT, content: @Composable () -> Unit) {
    setContent { KodakikoTheme(appTheme = appTheme) { content() } }
    waitForIdle()
}

fun AndroidComposeTestRule<*, ComponentActivity>.showWith(navigator: RouteNavigator, appTheme: AppTheme = AppTheme.LIGHT, content: @Composable () -> Unit) {
    show(appTheme) { CompositionLocalProvider(LocalRouteNavigator provides navigator) { content() } }
}

fun labeled(text: String): SemanticsMatcher = hasText(text) or hasContentDescription(text) or hasAnyDescendant(hasText(text))

fun contains(text: String): SemanticsMatcher = hasText(text, substring = true) or hasAnyDescendant(hasText(text, substring = true))

fun checked(): SemanticsMatcher =
    SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.On) or
        hasAnyDescendant(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.On))

fun unchecked(): SemanticsMatcher =
    SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.Off) or
        hasAnyDescendant(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.Off))

fun selected(): SemanticsMatcher =
    SemanticsMatcher.expectValue(SemanticsProperties.Selected, true) or hasAnyDescendant(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))

fun AndroidComposeTestRule<*, *>.count(tag: String): Int = onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().size

fun AndroidComposeTestRule<*, *>.exists(tag: String): Boolean = count(tag) > 0

fun AndroidComposeTestRule<*, *>.showsText(text: String): Boolean = onAllNodes(hasText(text), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

fun AndroidComposeTestRule<*, *>.topOf(tag: String): Float = onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().first().boundsInRoot.top

object Screenshots {
    const val QUALIFIERS = "w360dp-h900dp-xhdpi"

    private const val DIRECTORY = "src/test/screenshots"

    fun path(name: String): String = "$DIRECTORY/$name.png"

    fun options(name: String): RoborazziOptions {
        val requested = roborazziSystemPropertyTaskType()
        val taskType = when {
            requested.isEnabled() -> requested
            File(path(name)).isFile -> RoborazziTaskType.Verify
            else -> RoborazziTaskType.Record
        }
        return RoborazziOptions(taskType = taskType)
    }
}

fun SemanticsNodeInteraction.captureScreenshot(name: String) {
    captureRoboImage(Screenshots.path(name), Screenshots.options(name))
}

fun captureScreenScreenshot(name: String) {
    captureScreenRoboImage(Screenshots.path(name), Screenshots.options(name))
}

class FakeDebugLogPort : DebugLogPort {
    val infoFlow = kotlinx.coroutines.flow.MutableStateFlow(dev.kodakiko.log.DebugLogInfo.EMPTY)
    val linesFlow = kotlinx.coroutines.flow.MutableStateFlow(emptyList<String>())
    var copies = 0
    var shares = 0
    var failure: Exception? = null

    override val info: kotlinx.coroutines.flow.Flow<dev.kodakiko.log.DebugLogInfo> = infoFlow
    override val lines: kotlinx.coroutines.flow.Flow<List<String>> = linesFlow

    override suspend fun setEnabled(on: Boolean) {
        failure?.let { throw it }
        infoFlow.value = infoFlow.value.copy(enabled = on)
    }

    override suspend fun clear() {
        failure?.let { throw it }
        linesFlow.value = emptyList()
        infoFlow.value = infoFlow.value.copy(entries = 0, sizeBytes = 0)
    }

    override suspend fun copy(): Int {
        failure?.let { throw it }
        copies += 1
        return linesFlow.value.size
    }

    override suspend fun share() {
        failure?.let { throw it }
        shares += 1
    }
}
