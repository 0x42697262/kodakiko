@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.kodakiko.ui.feature.more

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
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
import com.github.takahirom.roborazzi.roborazziSystemPropertyTaskType
import dev.kodakiko.contract.PublicConfigDto
import dev.kodakiko.contract.SignupMode
import dev.kodakiko.contract.UserDto
import dev.kodakiko.contract.UserRole
import dev.kodakiko.contract.UserStatus
import dev.kodakiko.data.db.AppTheme
import dev.kodakiko.data.db.DeviceSettings
import dev.kodakiko.data.db.DeviceSettingsDefaults
import dev.kodakiko.data.media.CacheUsage
import dev.kodakiko.data.secure.AccountMode
import dev.kodakiko.service.account.ConfigService
import dev.kodakiko.service.stats.JournalStatistics
import dev.kodakiko.service.stats.MonthCount
import dev.kodakiko.service.stats.TopTag
import dev.kodakiko.service.stats.YearCount
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
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import java.io.File

const val KB = 1_024L
const val MB = 1_048_576L
const val GB = 1_073_741_824L
const val LAST_SUCCESS_MS = 1_795_000_000_000L
const val LAST_SUCCESS_TEXT = "11-18T11:06"

val defaults: DeviceSettings get() = DeviceSettingsDefaults.ANDROID

val context: Context get() = ApplicationProvider.getApplicationContext()

fun text(@StringRes id: Int, vararg args: Any): String = context.getString(id, *args)

class MainDispatcherRule : TestWatcher() {
    override fun starting(description: Description) {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}

fun publicConfig(defaultQuotaBytes: Long = 10 * GB): PublicConfigDto = PublicConfigDto(
    signupMode = SignupMode.INVITE,
    sharingEnabled = true,
    publicLinksEnabled = true,
    maxMediaPerEntry = 20,
    maxUploadBytes = 3_758_096_384L,
    defaultQuotaBytes = defaultQuotaBytes,
    trashDays = 30,
    accountDeletionGraceDays = 7,
    serverVersion = "0.3.1",
)

fun userDto(quotaBytes: Long? = null, usedBytes: Long = GB): UserDto = UserDto(
    id = "USER1",
    username = "ada",
    role = UserRole.MEMBER,
    status = UserStatus.ACTIVE,
    quotaBytes = quotaBytes,
    usedBytes = usedBytes,
    createdAt = "2026-01-01T00:00:00.000Z",
    deletionDueAt = null,
)

fun syncStatus(queued: Int = 0, dead: Int = 0, lastSuccessAt: Long? = null): SyncStatus =
    SyncStatus(running = false, queued = queued, dead = dead, waitingForWifi = 0, lastSuccessAt = lastSuccessAt, offline = false)

val TS_MONTHS: List<MonthCount> = listOf(
    "2025-12" to 1,
    "2026-01" to 0,
    "2026-02" to 0,
    "2026-03" to 0,
    "2026-04" to 0,
    "2026-05" to 0,
    "2026-06" to 0,
    "2026-07" to 0,
    "2026-08" to 0,
    "2026-09" to 0,
    "2026-10" to 1,
    "2026-11" to 1,
).map { (month, count) -> MonthCount(month, count) }

val TS_STATISTICS: JournalStatistics = JournalStatistics(
    entries = 3,
    mediaItems = 5,
    entriesWithVideo = 1,
    entriesWithNote = 1,
    waitingToUpload = 1,
    photos = 4,
    videos = 1,
    videoMs = 65_000,
    originalBytes = 10_000,
    favorites = 1,
    tagCount = 2,
    topTags = listOf(TopTag("beach", 3), TopTag("family", 1)),
    firstDay = "2025-12-20",
    lastDay = "2026-11-05",
    perMonth = TS_MONTHS,
    perYear = listOf(YearCount("2025", 1), YearCount("2026", 2)),
)

val EMPTY_STATISTICS: JournalStatistics = JournalStatistics(
    entries = 0,
    mediaItems = 0,
    entriesWithVideo = 0,
    entriesWithNote = 0,
    waitingToUpload = 0,
    photos = 0,
    videos = 0,
    videoMs = 0,
    originalBytes = 0,
    favorites = 0,
    tagCount = 0,
    topTags = emptyList(),
    firstDay = null,
    lastDay = null,
    perMonth = TS_MONTHS.map { it.copy(count = 0) },
    perYear = emptyList(),
)

val USAGE = CacheUsage(originalsBytes = 300 * MB, thumbnailsBytes = 12 * MB, pinnedBytes = 40 * MB, pendingUploadBytes = 5 * MB)

class FakeMoreSettings(initial: DeviceSettings = DeviceSettingsDefaults.ANDROID) : MoreSettings {
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

class FakeMoreAccount(mode: AccountMode = AccountMode.SERVER) : MoreAccount {
    val modeState = MutableStateFlow(mode)
    var locks = 0

    override val mode: StateFlow<AccountMode> = modeState.asStateFlow()

    override suspend fun lockNow() {
        locks += 1
    }
}

class FakeMoreSync(private val settings: FakeMoreSettings) : MoreSync {
    var calls = 0
    val pausedAtSync = mutableListOf<Boolean>()
    var failure: Throwable? = null

    override suspend fun syncNow() {
        calls += 1
        pausedAtSync += settings.value.syncPaused
        failure?.let { throw it }
    }
}

class MoreHarness(mode: AccountMode = AccountMode.SERVER, initial: DeviceSettings = DeviceSettingsDefaults.ANDROID) {
    val settings = FakeMoreSettings(initial)
    val account = FakeMoreAccount(mode)
    val sync = FakeMoreSync(settings)

    val deps: MoreDeps get() = MoreDeps(settings = settings, account = account, sync = sync)
}

class FakeStatisticsJournal(var stats: JournalStatistics = TS_STATISTICS) : StatisticsJournal {
    val changesState = MutableStateFlow(0)
    var computations = 0

    override val changes: StateFlow<Int> = changesState.asStateFlow()

    override fun statistics(): JournalStatistics {
        computations += 1
        return stats
    }
}

class FakeStatisticsStorage : StatisticsStorage {
    var usage: CacheUsage = USAGE
    var failure: Throwable? = null
    var gate: CompletableDeferred<Unit>? = null
    var calls = 0

    override suspend fun usage(): CacheUsage {
        calls += 1
        gate?.await()
        failure?.let { throw it }
        return usage
    }
}

class FakeConfigService(var config: PublicConfigDto? = publicConfig()) : ConfigService {
    override fun current(): PublicConfigDto? = config

    override suspend fun refresh(): PublicConfigDto? = config

    override fun accept(config: PublicConfigDto) {
        this.config = config
    }

    override fun subscribe(listener: (PublicConfigDto) -> Unit): () -> Unit = {}
}

class StatisticsHarness {
    val journal = FakeStatisticsJournal()
    val sync = MutableStateFlow(syncStatus(queued = 2, dead = 1, lastSuccessAt = LAST_SUCCESS_MS))
    val user = MutableStateFlow<UserDto?>(userDto(quotaBytes = 4 * GB, usedBytes = GB))
    val config = FakeConfigService()
    val storage = FakeStatisticsStorage()

    val deps: StatisticsDeps
        get() = StatisticsDeps(journal = journal, sync = sync, user = user, config = config, storage = storage)
}

class RecordingNavigator(start: List<Route>) : RouteNavigator {
    val stack = start.toMutableList()
    val pushed = mutableListOf<Route>()
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
        stack[stack.lastIndex] = route
    }

    override fun replaceAll(route: Route) {
        stack.clear()
        stack += route
    }
}

class RecordingUriHandler : UriHandler {
    val opened = mutableListOf<String>()

    override fun openUri(uri: String) {
        opened += uri
    }
}

fun AndroidComposeTestRule<*, ComponentActivity>.show(appTheme: AppTheme = AppTheme.LIGHT, content: @Composable () -> Unit) {
    setContent { KodakikoTheme(appTheme = appTheme) { content() } }
    waitForIdle()
}

fun AndroidComposeTestRule<*, ComponentActivity>.showWith(
    navigator: RouteNavigator,
    uriHandler: UriHandler = RecordingUriHandler(),
    appTheme: AppTheme = AppTheme.LIGHT,
    content: @Composable () -> Unit,
) {
    show(appTheme) { CompositionLocalProvider(LocalRouteNavigator provides navigator, LocalUriHandler provides uriHandler) { content() } }
}

fun labeled(text: String): SemanticsMatcher = hasText(text) or hasContentDescription(text) or hasAnyDescendant(hasText(text))

fun contains(text: String): SemanticsMatcher = hasText(text, substring = true) or hasAnyDescendant(hasText(text, substring = true))

fun described(text: String): SemanticsMatcher = hasContentDescription(text) or hasAnyDescendant(hasContentDescription(text))

fun checked(): SemanticsMatcher =
    SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.On) or
        hasAnyDescendant(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.On))

fun unchecked(): SemanticsMatcher =
    SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.Off) or
        hasAnyDescendant(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.Off))

fun AndroidComposeTestRule<*, *>.exists(tag: String): Boolean = onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

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
