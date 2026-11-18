@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.kodakiko.ui.feature.viewer

import android.content.Context
import android.content.res.Resources
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.media3.datasource.DataSource
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.github.takahirom.roborazzi.roborazziSystemPropertyTaskType
import dev.kodakiko.contract.EntryLocation
import dev.kodakiko.contract.EntryMetadataV1
import dev.kodakiko.contract.LocationSource
import dev.kodakiko.contract.MediaDescriptorV1
import dev.kodakiko.contract.MediaKind
import dev.kodakiko.contract.PublicConfigDto
import dev.kodakiko.contract.SignupMode
import dev.kodakiko.contract.ThumbnailDescriptor
import dev.kodakiko.data.db.AppTheme
import dev.kodakiko.data.db.DeviceSettings
import dev.kodakiko.data.db.DeviceSettingsDefaults
import dev.kodakiko.data.secure.AccountMode
import dev.kodakiko.domain.EntryEdit
import dev.kodakiko.domain.localDateOf
import dev.kodakiko.index.GalleryIndex
import dev.kodakiko.index.IndexedEntry
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
const val ENTRY = "entry-1"
const val NOW_MS = 1_795_262_400_000L
const val NOW_UTC = "2026-11-21T12:00:00.000Z"
const val DEVICE_OFFSET = 60L

val resources: Resources get() = ApplicationProvider.getApplicationContext<Context>().resources

fun photo(id: String, width: Long = 4000, height: Long = 3000, caption: String? = null): MediaDescriptorV1 = MediaDescriptorV1(
    mediaId = id,
    kind = MediaKind.PHOTO,
    mimeType = "image/jpeg",
    width = width,
    height = height,
    durationMs = null,
    originalPlaintextBytes = 2_000_000,
    thumbnail = ThumbnailDescriptor(512, 384, 30_000),
    capturedAtUtc = null,
    originalFileName = null,
    caption = caption,
)

fun video(id: String, caption: String? = null): MediaDescriptorV1 = MediaDescriptorV1(
    mediaId = id,
    kind = MediaKind.VIDEO,
    mimeType = "video/mp4",
    width = 1920,
    height = 1080,
    durationMs = 20_500,
    originalPlaintextBytes = 5_242_880,
    thumbnail = ThumbnailDescriptor(512, 288, 20_000),
    capturedAtUtc = "2026-11-21T08:59:00.000Z",
    originalFileName = "clip.mp4",
    caption = caption,
)

fun voice(id: String, durationMs: Long? = 20_000): MediaDescriptorV1 = MediaDescriptorV1(
    mediaId = id,
    kind = MediaKind.AUDIO,
    mimeType = "audio/mp4",
    width = 1,
    height = 1,
    durationMs = durationMs,
    originalPlaintextBytes = 100_000,
    thumbnail = ThumbnailDescriptor(1, 1, 1),
    capturedAtUtc = null,
    originalFileName = null,
)

val BERLIN = EntryLocation(52.52, 13.405, null, LocationSource.EXIF)

fun metadata(
    media: List<MediaDescriptorV1> = listOf(photo("p1"), photo("p2")),
    note: String = "",
    tags: List<String> = emptyList(),
    location: EntryLocation? = null,
    favorite: Boolean? = null,
    takenAtUtc: String = "2026-11-21T08:00:00.000Z",
    utcOffsetMinutes: Long = 60,
): EntryMetadataV1 = EntryMetadataV1(
    takenAtUtc = takenAtUtc,
    utcOffsetMinutes = utcOffsetMinutes,
    timeZone = "Europe/Berlin",
    note = note,
    tags = tags,
    location = location,
    media = media,
    createdAtUtc = takenAtUtc,
    editedAtUtc = takenAtUtc,
    favorite = favorite,
)

fun indexed(
    id: String = ENTRY,
    meta: EntryMetadataV1,
    trashed: Boolean = false,
    unreadable: Boolean = false,
    isOwn: Boolean = true,
    collectionIds: List<String> = listOf(HOME),
): IndexedEntry {
    val localDate = localDateOf(meta.takenAtUtc, meta.utcOffsetMinutes)
    return IndexedEntry(
        id = id,
        isOwn = isOwn,
        collectionIds = collectionIds,
        trashed = trashed,
        unreadable = unreadable,
        takenAtUtc = meta.takenAtUtc,
        utcOffsetMinutes = meta.utcOffsetMinutes,
        localDate = localDate,
        monthDay = localDate.substring(MONTH_DAY_START),
        tags = meta.tags,
        coverMediaId = meta.media.firstOrNull()?.mediaId,
        mediaCount = meta.media.size,
        hasVideo = meta.media.any { it.kind == MediaKind.VIDEO },
        notePreview = meta.note,
        pending = false,
        favorite = meta.favorite,
    )
}

private const val MONTH_DAY_START = 5

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

fun clear(viewModel: ViewModel) {
    val store = ViewModelStore()
    val factory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = viewModel as T
    }
    ViewModelProvider(store, factory)[viewModel.javaClass]
    store.clear()
}

class FakeIndexService : IndexService {
    val gallery = GalleryIndex(HOME)
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private var currentStatus = IndexStatus.READY
    private val versionState = MutableStateFlow(IndexVersion(IndexStatus.READY, 1, 0))

    override val version: StateFlow<IndexVersion> = versionState.asStateFlow()

    fun put(vararg entries: IndexedEntry) {
        for (item in entries) gallery.upsert(item, item.notePreview)
        changed()
    }

    fun edit(entryId: String, change: (IndexedEntry) -> IndexedEntry) {
        val current = gallery.get(entryId) ?: return
        gallery.upsert(change(current), current.notePreview)
        changed()
    }

    fun drop(entryId: String) {
        gallery.remove(entryId)
        changed()
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

data class EntriesCall(val name: String, val entryIds: List<String>, val argument: Any? = null)

class FakeEntriesService(private val index: FakeIndexService) : EntriesService {
    val metadata = LinkedHashMap<String, EntryMetadataV1>()
    val calls = mutableListOf<EntriesCall>()
    val reads = mutableListOf<String>()
    var readFailure: Throwable? = null

    fun store(id: String, meta: EntryMetadataV1, trashed: Boolean = false, unreadable: Boolean = false, isOwn: Boolean = true) {
        metadata[id] = meta
        index.put(indexed(id, meta, trashed = trashed, unreadable = unreadable, isOwn = isOwn))
    }

    fun replace(id: String, meta: EntryMetadataV1) {
        metadata[id] = meta
        index.edit(id) { indexed(id, meta, trashed = it.trashed, unreadable = it.unreadable, isOwn = it.isOwn) }
    }

    override suspend fun readMetadata(entryId: String): EntryMetadataV1 {
        reads += entryId
        readFailure?.let { throw it }
        return metadata[entryId] ?: error("no metadata for $entryId")
    }

    override suspend fun setFavorite(entryIds: List<String>, favorite: Boolean): BulkResult {
        calls += EntriesCall("setFavorite", entryIds, favorite)
        for (id in entryIds) metadata[id]?.let { replace(id, it.copy(favorite = favorite)) }
        return BulkResult(entryIds.size, 0)
    }

    override suspend fun trash(entryIds: List<String>): BulkResult {
        calls += EntriesCall("trash", entryIds)
        for (id in entryIds) index.edit(id) { it.copy(trashed = true) }
        return BulkResult(entryIds.size, 0)
    }

    override suspend fun restore(entryIds: List<String>): BulkResult {
        calls += EntriesCall("restore", entryIds)
        for (id in entryIds) index.edit(id) { it.copy(trashed = false) }
        return BulkResult(entryIds.size, 0)
    }

    override suspend fun deletePermanently(entryIds: List<String>): BulkResult {
        calls += EntriesCall("deletePermanently", entryIds)
        for (id in entryIds) index.drop(id)
        return BulkResult(entryIds.size, 0)
    }

    override suspend fun createFromTray(input: CreateEntryInput): String = error(UNUSED)

    override suspend fun createFromMedia(input: CreateEntryInput, items: List<ImportedMedia>): String = error(UNUSED)

    override suspend fun edit(entryId: String, edit: EntryEdit) = error(UNUSED)

    override suspend fun addMedia(entryId: String, items: List<ImportedMedia>) = error(UNUSED)

    override suspend fun removeMedia(entryId: String, mediaId: String) = error(UNUSED)

    override suspend fun addTagToMany(entryIds: List<String>, tag: String): BulkResult = error(UNUSED)

    override suspend fun removeTagFromMany(entryIds: List<String>, tag: String): BulkResult = error(UNUSED)

    override suspend fun renameTag(entryIds: List<String>, from: String, to: String): BulkResult = error(UNUSED)

    override suspend fun deleteTag(entryIds: List<String>, tag: String): BulkResult = error(UNUSED)

    private companion object {
        const val UNUSED = "not used by the viewer"
    }
}

class FakeViewerMedia : ViewerMedia {
    val pinned = mutableSetOf<String>()
    val onDevice = mutableSetOf<String>()
    val pinCalls = mutableListOf<Pair<String, Boolean>>()

    override suspend fun isPinned(entryId: String): Boolean = entryId in pinned

    override suspend fun setPinned(entryId: String, pinned: Boolean) {
        pinCalls += entryId to pinned
        if (pinned) this.pinned += entryId else this.pinned -= entryId
    }

    override suspend fun isOriginalOnDevice(mediaId: String): Boolean = mediaId in onDevice
}

class FakePreparedVideo(val mediaId: String) : PreparedVideo {
    var released = 0

    override val uri: Uri = Uri.parse("kodakiko-test://video/$mediaId")

    override val dataSourceFactory: DataSource.Factory = DataSource.Factory { error("no data in unit tests") }

    override fun release() {
        released += 1
    }
}

class FakeVideoSource : VideoSource {
    val prepared = mutableListOf<FakePreparedVideo>()
    val requests = mutableListOf<Pair<String, String>>()
    var failure: Throwable? = null
    var gate: CompletableDeferred<Unit>? = null
    var progress: List<Float> = emptyList()

    override suspend fun prepare(entryId: String, mediaId: String, onProgress: (Float) -> Unit): PreparedVideo {
        requests += entryId to mediaId
        progress.forEach(onProgress)
        gate?.await()
        failure?.let { throw it }
        return FakePreparedVideo(mediaId).also { prepared += it }
    }
}

class FakeViewerVoice : ViewerVoice {
    val playingState = MutableStateFlow<String?>(null)
    val toggles = mutableListOf<Pair<String, String>>()
    var stops = 0

    override val playing: StateFlow<String?> = playingState.asStateFlow()

    override suspend fun toggle(entryId: String, mediaId: String) {
        toggles += entryId to mediaId
        playingState.value = if (playingState.value == mediaId) null else mediaId
    }

    override fun stop() {
        stops += 1
        playingState.value = null
    }
}

class FakeViewerClock(var nowMs: Long = NOW_MS, var offset: Long = DEVICE_OFFSET) : ViewerClock {
    override fun nowMs(): Long = nowMs

    override fun deviceOffsetMinutes(atMs: Long): Long = offset
}

class FakeConfigService(var config: PublicConfigDto? = publicConfig()) : ConfigService {
    override fun current(): PublicConfigDto? = config

    override suspend fun refresh(): PublicConfigDto? = config

    override fun accept(config: PublicConfigDto) {
        this.config = config
    }

    override fun subscribe(listener: (PublicConfigDto) -> Unit): () -> Unit = {}
}

fun publicConfig(sharing: Boolean = true, links: Boolean = true): PublicConfigDto = PublicConfigDto(
    signupMode = SignupMode.INVITE,
    sharingEnabled = sharing,
    publicLinksEnabled = links,
    maxMediaPerEntry = 20,
    maxUploadBytes = 1000,
    defaultQuotaBytes = 0,
    trashDays = 30,
    accountDeletionGraceDays = 7,
    serverVersion = "0.1.0",
)

class ViewerHarness {
    val index = FakeIndexService()
    val entries = FakeEntriesService(index)
    val media = FakeViewerMedia()
    val videos = FakeVideoSource()
    val voice = FakeViewerVoice()
    val settings = MutableStateFlow<DeviceSettings>(DeviceSettingsDefaults.ANDROID)
    val wifi = MutableStateFlow(true)
    val mode = MutableStateFlow(AccountMode.SERVER)
    val config = FakeConfigService()
    val owners = mutableMapOf<String, String>()
    val notices = Observable<GalleryNotice?>(null)
    val shownTags = mutableListOf<String>()
    val clock = FakeViewerClock()

    val deps = ViewerDeps(
        index = index,
        entries = entries,
        media = media,
        videos = videos,
        voice = voice,
        settings = settings,
        wifi = wifi,
        accountMode = mode,
        config = config,
        owners = { owners[it] },
        notices = notices,
        gallery = { shownTags += it },
        clock = clock,
    )
}

fun solid(color: Color, width: Int = 64, height: Int = 48): ImageBitmap {
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    bitmap.eraseColor(color.toArgb())
    return bitmap.asImageBitmap()
}

private val PALETTE = listOf(Color(0xFF8D6E63), Color(0xFF4DB6AC), Color(0xFF7986CB), Color(0xFFFFB74D), Color(0xFF81C784))

fun colorOf(mediaId: String): Color = PALETTE[Math.floorMod(mediaId.hashCode(), PALETTE.size)]

class FakeViewerImages(
    private val originals: Map<String, OriginalImage> = emptyMap(),
    private val gates: Map<String, CompletableDeferred<OriginalImage>> = emptyMap(),
    private val progress: Map<String, DownloadProgress> = emptyMap(),
) : ViewerImages {
    val thumbnails = mutableListOf<String>()
    val originalRequests = mutableListOf<String>()

    override suspend fun thumbnail(entryId: String, mediaId: String): ImageBitmap? {
        thumbnails += mediaId
        return solid(colorOf(mediaId))
    }

    override suspend fun original(entryId: String, mediaId: String, onProgress: (DownloadProgress) -> Unit): OriginalImage {
        originalRequests += mediaId
        progress[mediaId]?.let(onProgress)
        gates[mediaId]?.let { return it.await() }
        return originals[mediaId] ?: OriginalImage.Loaded(solid(colorOf(mediaId), ORIGINAL_WIDTH, ORIGINAL_HEIGHT))
    }

    private companion object {
        const val ORIGINAL_WIDTH = 400
        const val ORIGINAL_HEIGHT = 300
    }
}

data class InlineCall(val mediaId: String, val muted: Boolean)

class FakeVideoSurfaces : VideoSurfaces {
    val inline = mutableListOf<InlineCall>()
    val fullScreen = mutableListOf<String>()
    var reportProblem: VideoProblem? = null

    @Composable
    override fun Inline(video: PreparedVideo, muted: Boolean, modifier: Modifier) {
        inline += InlineCall((video as FakePreparedVideo).mediaId, muted)
        Box(modifier.background(Color(0xFF263238)))
    }

    @Composable
    override fun FullScreen(video: PreparedVideo, onProblem: (VideoProblem) -> Unit, modifier: Modifier) {
        fullScreen += (video as FakePreparedVideo).mediaId
        reportProblem?.let(onProblem)
        Box(modifier.background(Color(0xFF37474F)))
    }
}

fun AndroidComposeTestRule<*, ComponentActivity>.show(
    appTheme: AppTheme = AppTheme.LIGHT,
    images: ViewerImages = FakeViewerImages(),
    surfaces: VideoSurfaces = FakeVideoSurfaces(),
    content: @Composable () -> Unit,
) {
    setContent {
        KodakikoTheme(appTheme = appTheme) {
            CompositionLocalProvider(LocalViewerImages provides images, LocalVideoSurfaces provides surfaces) { content() }
        }
    }
    waitForIdle()
}

fun AndroidComposeTestRule<*, *>.count(tag: String): Int = onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().size

fun AndroidComposeTestRule<*, *>.exists(tag: String): Boolean = count(tag) > 0

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
