@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.kodakiko.ui.feature.gallery

import android.content.res.Resources
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.github.takahirom.roborazzi.roborazziSystemPropertyTaskType
import dev.kodakiko.contract.EntryMetadataV1
import dev.kodakiko.contract.PublicConfigDto
import dev.kodakiko.contract.SignupMode
import dev.kodakiko.data.db.AppTheme
import dev.kodakiko.data.db.DeviceSettings
import dev.kodakiko.data.db.DeviceSettingsDefaults
import dev.kodakiko.data.secure.AccountMode
import dev.kodakiko.domain.EntryEdit
import dev.kodakiko.domain.localDateOf
import dev.kodakiko.index.GalleryIndex
import dev.kodakiko.index.IndexedEntry
import dev.kodakiko.platform.device.HapticKind
import dev.kodakiko.service.account.ConfigService
import dev.kodakiko.service.account.Observable
import dev.kodakiko.service.entries.BulkResult
import dev.kodakiko.service.entries.CreateEntryInput
import dev.kodakiko.service.entries.EntriesService
import dev.kodakiko.service.entries.ImportedMedia
import dev.kodakiko.service.index.IndexBuildReport
import dev.kodakiko.service.index.IndexService
import dev.kodakiko.service.index.IndexStatus
import dev.kodakiko.service.index.IndexVersion
import dev.kodakiko.ui.core.notice.GalleryNotice
import dev.kodakiko.ui.core.theme.KodakikoTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

const val HOME = "home-collection"
const val TODAY = "2026-11-21"
const val NOW_MS = 1_795_250_000_000L
const val TRASH_DAYS = 30L
const val ON_THIS_DAY_TILE_LIMIT = 10

val resources: Resources get() = ApplicationProvider.getApplicationContext<android.content.Context>().resources

fun string(@StringRes id: Int, vararg args: Any): String = resources.getString(id, *args)

fun plural(@PluralsRes id: Int, count: Int): String = resources.getQuantityString(id, count, count)

fun entry(
    id: String,
    takenAtUtc: String,
    tags: List<String> = emptyList(),
    note: String = "",
    mediaCount: Int = 1,
    hasVideo: Boolean = false,
    pending: Boolean = false,
    favorite: Boolean? = null,
    unreadable: Boolean = false,
    trashed: Boolean = false,
    coverMediaId: String? = "media-$id",
    isOwn: Boolean = true,
    collectionIds: List<String> = listOf(HOME),
    utcOffsetMinutes: Long = 0,
): IndexedEntry {
    val localDate = localDateOf(takenAtUtc, utcOffsetMinutes)
    return IndexedEntry(
        id = id,
        isOwn = isOwn,
        collectionIds = collectionIds,
        trashed = trashed,
        unreadable = unreadable,
        takenAtUtc = takenAtUtc,
        utcOffsetMinutes = utcOffsetMinutes,
        localDate = localDate,
        monthDay = localDate.substring(MONTH_DAY_START),
        tags = tags,
        coverMediaId = coverMediaId,
        mediaCount = mediaCount,
        hasVideo = hasVideo,
        notePreview = note.take(NOTE_PREVIEW_CHARS),
        pending = pending,
        favorite = favorite,
    )
}

private const val MONTH_DAY_START = 5
private const val NOTE_PREVIEW_CHARS = 140

class MainDispatcherRule : TestWatcher() {
    override fun starting(description: Description) {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}

class Observed<S, E>(private val state: StateFlow<S>, val events: List<E>) {
    val value: S get() = state.value
}

fun <S, E> TestScope.observe(state: StateFlow<S>, events: Flow<E>): Observed<S, E> {
    val received = mutableListOf<E>()
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { state.collect {} }
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { events.collect { received += it } }
    return Observed(state, received)
}

class FakeIndexService(home: String = HOME) : IndexService {
    val gallery = GalleryIndex(home)
    private val notes = LinkedHashMap<String, String>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private var currentStatus = IndexStatus.READY
    private val versionState = MutableStateFlow(IndexVersion(IndexStatus.READY, 1, 0))

    override val version: StateFlow<IndexVersion> = versionState.asStateFlow()

    fun put(vararg entries: IndexedEntry) {
        for (item in entries) {
            val text = notes[item.id] ?: item.notePreview
            notes[item.id] = text
            gallery.upsert(item, text)
        }
        changed()
    }

    fun putWithNote(item: IndexedEntry, noteText: String) {
        notes[item.id] = noteText
        gallery.upsert(item, noteText)
        changed()
    }

    fun edit(entryId: String, change: (IndexedEntry) -> IndexedEntry) {
        val current = gallery.get(entryId) ?: return
        gallery.upsert(change(current), notes[entryId] ?: current.notePreview)
    }

    fun drop(entryId: String) {
        gallery.remove(entryId)
        notes.remove(entryId)
    }

    fun setStatus(status: IndexStatus) {
        currentStatus = status
        changed()
    }

    fun changed() {
        versionState.value = IndexVersion(currentStatus, 1, gallery.version())
        listeners.forEach { it() }
    }

    override suspend fun build(): IndexBuildReport = IndexBuildReport(gallery.size(), 0, 0)

    override fun index(): GalleryIndex? = if (currentStatus == IndexStatus.READY) gallery else null

    override fun status(): IndexStatus = currentStatus

    override fun subscribe(listener: () -> Unit): () -> Unit {
        listeners += listener
        return { listeners.remove(listener) }
    }

    override fun clear() = Unit
}

data class BulkCall(val name: String, val entryIds: List<String>, val argument: Any? = null)

class FakeEntriesService(private val index: FakeIndexService) : EntriesService {
    val calls = mutableListOf<BulkCall>()
    var result: BulkResult? = null
    var failure: Throwable? = null
    var gate: CompletableDeferred<Unit>? = null

    private suspend fun bulk(name: String, entryIds: List<String>, argument: Any? = null, apply: (String) -> Unit): BulkResult {
        calls += BulkCall(name, entryIds, argument)
        gate?.await()
        failure?.let { throw it }
        entryIds.forEach(apply)
        index.changed()
        return result ?: BulkResult(entryIds.size, 0)
    }

    override suspend fun trash(entryIds: List<String>): BulkResult =
        bulk("trash", entryIds) { id -> index.edit(id) { it.copy(trashed = true) } }

    override suspend fun restore(entryIds: List<String>): BulkResult =
        bulk("restore", entryIds) { id -> index.edit(id) { it.copy(trashed = false) } }

    override suspend fun deletePermanently(entryIds: List<String>): BulkResult = bulk("deletePermanently", entryIds) { index.drop(it) }

    override suspend fun addTagToMany(entryIds: List<String>, tag: String): BulkResult =
        bulk("addTagToMany", entryIds, tag) { id -> index.edit(id) { if (tag in it.tags) it else it.copy(tags = it.tags + tag) } }

    override suspend fun removeTagFromMany(entryIds: List<String>, tag: String): BulkResult =
        bulk("removeTagFromMany", entryIds, tag) { id -> index.edit(id) { it.copy(tags = it.tags - tag) } }

    override suspend fun setFavorite(entryIds: List<String>, favorite: Boolean): BulkResult =
        bulk("setFavorite", entryIds, favorite) { id -> index.edit(id) { it.copy(favorite = favorite) } }

    override suspend fun createFromTray(input: CreateEntryInput): String = error(UNUSED)

    override suspend fun createFromMedia(input: CreateEntryInput, items: List<ImportedMedia>): String = error(UNUSED)

    override suspend fun edit(entryId: String, edit: EntryEdit) = error(UNUSED)

    override suspend fun addMedia(entryId: String, items: List<ImportedMedia>) = error(UNUSED)

    override suspend fun removeMedia(entryId: String, mediaId: String) = error(UNUSED)

    override suspend fun renameTag(entryIds: List<String>, from: String, to: String): BulkResult = error(UNUSED)

    override suspend fun deleteTag(entryIds: List<String>, tag: String): BulkResult = error(UNUSED)

    override suspend fun readMetadata(entryId: String): EntryMetadataV1 = error(UNUSED)

    private companion object {
        const val UNUSED = "not used by the gallery"
    }
}

class FakeSettings(initial: DeviceSettings = DeviceSettingsDefaults.ANDROID) : GallerySettings {
    private val state = MutableStateFlow(initial)
    var updates = 0

    override val settings: StateFlow<DeviceSettings> = state.asStateFlow()

    var value: DeviceSettings
        get() = state.value
        set(next) {
            state.value = next
        }

    override fun update(change: (DeviceSettings) -> DeviceSettings) {
        updates += 1
        state.value = change(state.value)
    }
}

class FakeAccount(username: String = "alice", mode: AccountMode = AccountMode.SERVER) : GalleryAccount {
    val calls = mutableListOf<String>()
    var failure: Throwable? = null
    val usernameState = MutableStateFlow(username)
    val modeState = MutableStateFlow(mode)

    override val username: StateFlow<String> = usernameState.asStateFlow()

    override val mode: StateFlow<AccountMode> = modeState.asStateFlow()

    override suspend fun lockNow() {
        calls += "lockNow"
        failure?.let { throw it }
    }

    override suspend fun signOut() {
        calls += "signOut"
        failure?.let { throw it }
    }
}

class FakeSync : GallerySync {
    var calls = 0
    var failure: Throwable? = null
    var gate: CompletableDeferred<Unit>? = null

    override suspend fun syncNow() {
        calls += 1
        gate?.await()
        failure?.let { throw it }
    }
}

class FakeClock(var nowMs: Long = NOW_MS, var today: String = TODAY) : GalleryClock {
    override fun nowMs(): Long = nowMs

    override fun todayLocalDate(): String = today
}

class FakeConfigService(var config: PublicConfigDto? = publicConfig()) : ConfigService {
    override fun current(): PublicConfigDto? = config

    override suspend fun refresh(): PublicConfigDto? = config

    override fun accept(config: PublicConfigDto) {
        this.config = config
    }

    override fun subscribe(listener: (PublicConfigDto) -> Unit): () -> Unit = {}
}

fun publicConfig(trashDays: Long = TRASH_DAYS): PublicConfigDto =
    PublicConfigDto(
        signupMode = SignupMode.INVITE,
        sharingEnabled = true,
        publicLinksEnabled = true,
        maxMediaPerEntry = 20,
        maxUploadBytes = 1000,
        defaultQuotaBytes = 0,
        trashDays = trashDays,
        accountDeletionGraceDays = 7,
        serverVersion = "0.1.0",
    )

class Harness(dispatcher: CoroutineDispatcher = UnconfinedTestDispatcher()) {
    val index = FakeIndexService()
    val entries = FakeEntriesService(index)
    val settings = FakeSettings()
    val filter = GalleryFilterStore()
    val notices = Observable<GalleryNotice?>(null)
    val account = FakeAccount()
    val sync = FakeSync()
    val config = FakeConfigService()
    val clock = FakeClock()
    val haptics = mutableListOf<HapticKind>()
    var random = 0.0

    val deps = GalleryDeps(
        index = index,
        entries = entries,
        settings = settings,
        filter = filter,
        notices = notices,
        account = account,
        sync = sync,
        config = config,
        haptics = { haptics += it },
        clock = clock,
        random = { random },
        dispatcher = dispatcher,
    )
}

class FakeThumbnails(private val missing: Set<String> = emptySet()) : GalleryThumbnails {
    val requested = mutableListOf<Pair<String, String>>()

    override suspend fun load(entryId: String, mediaId: String): ImageBitmap? {
        requested += entryId to mediaId
        if (mediaId in missing) return null
        return solid(colorOf(mediaId))
    }

    companion object {
        private const val SIZE = 64
        private val PALETTE = listOf(
            Color(0xFF8D6E63),
            Color(0xFF4DB6AC),
            Color(0xFF7986CB),
            Color(0xFFFFB74D),
            Color(0xFFA1887F),
            Color(0xFF81C784),
        )

        fun colorOf(mediaId: String): Color = PALETTE[Math.floorMod(mediaId.hashCode(), PALETTE.size)]

        fun solid(color: Color): ImageBitmap {
            val bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(color.toArgb())
            return bitmap.asImageBitmap()
        }
    }
}

fun AndroidComposeTestRule<*, ComponentActivity>.show(
    appTheme: AppTheme = AppTheme.LIGHT,
    thumbnails: GalleryThumbnails = FakeThumbnails(),
    content: @Composable () -> Unit,
) {
    setContent {
        KodakikoTheme(appTheme = appTheme) {
            CompositionLocalProvider(LocalGalleryThumbnails provides thumbnails) { content() }
        }
    }
    waitForIdle()
}

fun AndroidComposeTestRule<*, *>.exists(tag: String): Boolean = count(tag) > 0

fun AndroidComposeTestRule<*, *>.count(tag: String): Int = onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().size

fun AndroidComposeTestRule<*, *>.tagCount(pattern: Regex): Int =
    onAllNodes(SemanticsMatcher("tag matches $pattern") { node -> node.config.getOrNull(SemanticsProperties.TestTag)?.matches(pattern) == true }, useUnmergedTree = true)
        .fetchSemanticsNodes().size

fun AndroidComposeTestRule<*, *>.tileCount(): Int = tagCount(TILE_TAG)

fun AndroidComposeTestRule<*, *>.headerCount(): Int = tagCount(HEADER_TAG)

private val TILE_TAG = Regex("^gallery\\.tile\\.\\d+$")

private val HEADER_TAG = Regex("^gallery\\.dayHeader\\.\\d+$")

fun AndroidComposeTestRule<*, *>.assertTagShowsText(tag: String, text: String) {
    onNode(hasTestTag(tag) and (hasText(text) or hasAnyDescendant(hasText(text))), useUnmergedTree = true).assertExists()
}

fun SemanticsNodeInteraction.isNotShown(): Boolean = runCatching { assertIsDisplayed() }.isFailure

object Screenshots {
    const val QUALIFIERS = "w360dp-h640dp-xhdpi"

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
