@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.kodakiko.ui.feature.capture

import android.content.Context
import android.content.res.Resources
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.roborazziSystemPropertyTaskType
import dev.kodakiko.contract.EntryMetadataV1
import dev.kodakiko.contract.MediaDescriptorV1
import dev.kodakiko.contract.MediaKind
import dev.kodakiko.contract.PublicConfigDto
import dev.kodakiko.contract.SignupMode
import dev.kodakiko.contract.ThumbnailDescriptor
import dev.kodakiko.data.db.AppTheme
import dev.kodakiko.data.db.DeviceSettings
import dev.kodakiko.data.db.DeviceSettingsDefaults
import dev.kodakiko.domain.DeviceLocation
import dev.kodakiko.domain.EntryEdit
import dev.kodakiko.domain.localDateOf
import dev.kodakiko.index.GalleryIndex
import dev.kodakiko.index.IndexedEntry
import dev.kodakiko.media.GeoPoint
import dev.kodakiko.media.ResolvedTime
import dev.kodakiko.media.TimeSource
import dev.kodakiko.platform.camera.CameraFacing
import dev.kodakiko.platform.device.HapticKind
import dev.kodakiko.service.account.ConfigService
import dev.kodakiko.service.entries.BulkResult
import dev.kodakiko.service.entries.CaptureTray
import dev.kodakiko.service.entries.CaptureTrayDeps
import dev.kodakiko.service.entries.CreateEntryInput
import dev.kodakiko.service.entries.EntriesService
import dev.kodakiko.service.entries.EntryFiles
import dev.kodakiko.service.entries.ImportOutcome
import dev.kodakiko.service.entries.ImportPipeline
import dev.kodakiko.service.entries.ImportRefusal
import dev.kodakiko.service.entries.ImportSlots
import dev.kodakiko.service.entries.ImportedMedia
import dev.kodakiko.service.entries.MediaInput
import dev.kodakiko.service.entries.MediaOrigin
import dev.kodakiko.service.entries.VoiceNoteInfo
import dev.kodakiko.service.index.IndexBuildReport
import dev.kodakiko.service.index.IndexService
import dev.kodakiko.service.index.IndexStatus
import dev.kodakiko.service.index.IndexVersion
import dev.kodakiko.ui.core.navigation.Route
import dev.kodakiko.ui.core.navigation.RouteNavigator
import dev.kodakiko.ui.core.theme.KodakikoTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
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
const val AUTHORITY = "dev.kodakiko.capture.test.files"
const val MAX_UPLOAD_BYTES = 3_758_096_384L
const val NOW_UTC = "2026-11-21T12:00:00.000Z"

val resources: Resources get() = ApplicationProvider.getApplicationContext<Context>().resources

val context: Context get() = ApplicationProvider.getApplicationContext()

fun mediaId(prefix: String): String = prefix.padEnd(MEDIA_ID_LENGTH, '0')

private const val MEDIA_ID_LENGTH = 22
private const val KEY_BYTES = 32

val scratch: File get() = File(context.cacheDir, "capture-test").apply { mkdirs() }

fun cameraPhoto(name: String = "camera-photo.jpg"): MediaInput = MediaInput(MediaOrigin.CAMERA, File(scratch, name), null, null)

fun cameraVideo(name: String = "camera-video.mp4"): MediaInput = MediaInput(MediaOrigin.CAMERA, File(scratch, name), null, null)

fun picked(fileName: String?, file: String = fileName ?: "picked.bin"): MediaInput = MediaInput(MediaOrigin.PICKER, File(scratch, file), fileName, null)

fun photoDescriptor(id: String): MediaDescriptorV1 = MediaDescriptorV1(
    mediaId = id,
    kind = MediaKind.PHOTO,
    mimeType = "image/jpeg",
    width = 4000,
    height = 3000,
    durationMs = null,
    originalPlaintextBytes = 2_000_000,
    thumbnail = ThumbnailDescriptor(512, 384, 30_000),
    capturedAtUtc = null,
    originalFileName = null,
)

fun imported(id: String): ImportedMedia = ImportedMedia(
    mediaId = id,
    mediaKey = ByteArray(KEY_BYTES) { 7 },
    originalObjectId = "$id-original",
    thumbnailObjectId = "$id-thumbnail",
    originalCiphertextBytes = 100,
    thumbnailCiphertextBytes = 10,
    descriptor = photoDescriptor(id),
    time = ResolvedTime(NOW_UTC, 60, TimeSource.NOW),
    location = null,
    thumbnailBytes = byteArrayOf(1, 2, 3),
)

fun entryMeta(mediaCount: Int): EntryMetadataV1 = EntryMetadataV1(
    takenAtUtc = "2026-11-21T08:00:00.000Z",
    utcOffsetMinutes = 60,
    timeZone = "Europe/Berlin",
    note = "",
    tags = emptyList(),
    location = null,
    media = (0 until mediaCount).map { photoDescriptor(mediaId("entry$it")) },
    createdAtUtc = "2026-11-21T08:00:00.000Z",
    editedAtUtc = "2026-11-21T08:00:00.000Z",
    favorite = null,
)

val PARIS = DeviceLocation(GeoPoint(48.8584, 2.2945), 12.0)

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

fun TestScope.clearAtEnd(viewModel: ViewModel) {
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
        try {
            awaitCancellation()
        } finally {
            clear(viewModel)
        }
    }
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

class FakeEntryFiles : EntryFiles {
    val deleted = CopyOnWriteArrayList<String>()

    override fun writeObject(objectId: String, chunks: Sequence<ByteArray>): Long = 0

    override fun delete(objectId: String) {
        deleted += objectId
    }

    override fun writePlainThumbnail(mediaId: String, bytes: ByteArray) = Unit

    override fun deletePlainThumbnail(mediaId: String) {
        deleted += "plain:$mediaId"
    }
}

data class ImportCall(val input: MediaInput, val slots: ImportSlots)

class FakeImports : ImportPipeline {
    val calls = CopyOnWriteArrayList<ImportCall>()
    val refusals = mutableMapOf<String, ImportRefusal>()
    var gate: CompletableDeferred<Unit>? = null
    private var counter = 0

    override suspend fun importOne(input: MediaInput, slots: ImportSlots): ImportOutcome {
        calls += ImportCall(input, slots)
        gate?.await()
        if (slots.used >= slots.limit) return ImportOutcome.Refused(ImportRefusal.TRAY_FULL, input.fileName)
        refusals[input.file.name]?.let { return ImportOutcome.Refused(it, input.fileName) }
        counter += 1
        return ImportOutcome.Imported(imported(mediaId("item$counter")))
    }

    override suspend fun importVoiceNote(input: MediaInput, voice: VoiceNoteInfo, slots: ImportSlots): ImportOutcome = error("capture never imports voice notes")
}

class FakeCamera : CaptureCamera {
    var photo: MediaInput? = cameraPhoto()
    var photos = 0
    val starts = mutableListOf<Long>()
    var stops = 0
    private var finished: ((MediaInput?) -> Unit)? = null

    val recording: Boolean get() = finished != null

    override suspend fun takePhoto(): MediaInput? {
        photos += 1
        return photo
    }

    override fun startRecording(maxFileSizeBytes: Long, onFinished: (MediaInput?) -> Unit) {
        starts += maxFileSizeBytes
        finished = onFinished
    }

    override fun stopRecording() {
        stops += 1
    }

    fun finish(result: MediaInput? = cameraVideo()) {
        val callback = requireNotNull(finished) { "no recording to finish" }
        finished = null
        callback(result)
    }
}

class FakePermissions : CapturePermissions {
    var camera = CameraPermission.GRANTED
    var cameraAfterRequest = CameraPermission.GRANTED
    var microphone = true
    var microphoneAfterRequest = true
    var cameraRequests = 0
    var microphoneRequests = 0

    override fun camera(): CameraPermission = camera

    override suspend fun requestCamera(): CameraPermission {
        cameraRequests += 1
        camera = cameraAfterRequest
        return camera
    }

    override fun microphone(): Boolean = microphone

    override suspend fun requestMicrophone(): Boolean {
        microphoneRequests += 1
        microphone = microphoneAfterRequest
        return microphone
    }
}

class FakeCaptureFiles : CaptureFiles {
    val outputs = mutableListOf<File>()
    val copied = mutableListOf<Uri>()
    val unreadable = mutableSetOf<String>()

    override fun phoneCameraOutput(video: Boolean): File {
        val file = File(File(context.cacheDir, "capture"), "phone-${outputs.size}" + if (video) ".mp4" else ".jpg")
        file.parentFile!!.mkdirs()
        file.writeBytes(byteArrayOf(1, 2, 3))
        outputs += file
        return file
    }

    override suspend fun copyPicked(uri: Uri): MediaInput? {
        copied += uri
        val name = uri.lastPathSegment ?: return null
        if (name in unreadable) return null
        return picked(name)
    }
}

class FakeLocation : CaptureLocation {
    val requests = mutableListOf<Long>()
    var result: DeviceLocation? = PARIS
    var failure: Throwable? = null

    override suspend fun currentPosition(timeoutMs: Long): DeviceLocation? {
        requests += timeoutMs
        failure?.let { throw it }
        return result
    }
}

class FakeClock : CaptureClock {
    var source: () -> Long = { 0L }

    override fun nowMs(): Long = source()
}

class FakeIndexService : IndexService {
    val gallery = GalleryIndex(HOME)
    private val versionState = MutableStateFlow(IndexVersion(IndexStatus.READY, 1, 0))

    override val version: StateFlow<IndexVersion> = versionState.asStateFlow()

    fun put(id: String, meta: EntryMetadataV1, isOwn: Boolean = true, trashed: Boolean = false) {
        val localDate = localDateOf(meta.takenAtUtc, meta.utcOffsetMinutes)
        gallery.upsert(
            IndexedEntry(
                id = id,
                isOwn = isOwn,
                collectionIds = listOf(HOME),
                trashed = trashed,
                unreadable = false,
                takenAtUtc = meta.takenAtUtc,
                utcOffsetMinutes = meta.utcOffsetMinutes,
                localDate = localDate,
                monthDay = localDate.substring(MONTH_DAY_START),
                tags = meta.tags,
                coverMediaId = meta.media.firstOrNull()?.mediaId,
                mediaCount = meta.media.size,
                hasVideo = false,
                notePreview = meta.note,
                pending = false,
                favorite = meta.favorite,
            ),
            meta.note,
        )
        versionState.value = IndexVersion(IndexStatus.READY, 1, gallery.version())
    }

    override suspend fun build(): IndexBuildReport = IndexBuildReport(gallery.size(), 0, 0)

    override fun index(): GalleryIndex = gallery

    override fun status(): IndexStatus = IndexStatus.READY

    override fun subscribe(listener: () -> Unit): () -> Unit = {}

    override fun clear() = Unit

    private companion object {
        const val MONTH_DAY_START = 5
    }
}

class FakeEntries : EntriesService {
    val added = mutableListOf<Pair<String, List<String>>>()
    var addFailure: Throwable? = null
    var addGate: CompletableDeferred<Unit>? = null

    override suspend fun addMedia(entryId: String, items: List<ImportedMedia>) {
        addGate?.await()
        addFailure?.let { throw it }
        added += entryId to items.map { it.mediaId }
    }

    override suspend fun createFromTray(input: CreateEntryInput): String = error(UNUSED)

    override suspend fun createFromMedia(input: CreateEntryInput, items: List<ImportedMedia>): String = error(UNUSED)

    override suspend fun edit(entryId: String, edit: EntryEdit) = error(UNUSED)

    override suspend fun removeMedia(entryId: String, mediaId: String) = error(UNUSED)

    override suspend fun trash(entryIds: List<String>): BulkResult = error(UNUSED)

    override suspend fun restore(entryIds: List<String>): BulkResult = error(UNUSED)

    override suspend fun deletePermanently(entryIds: List<String>): BulkResult = error(UNUSED)

    override suspend fun addTagToMany(entryIds: List<String>, tag: String): BulkResult = error(UNUSED)

    override suspend fun removeTagFromMany(entryIds: List<String>, tag: String): BulkResult = error(UNUSED)

    override suspend fun setFavorite(entryIds: List<String>, favorite: Boolean): BulkResult = error(UNUSED)

    override suspend fun renameTag(entryIds: List<String>, from: String, to: String): BulkResult = error(UNUSED)

    override suspend fun deleteTag(entryIds: List<String>, tag: String): BulkResult = error(UNUSED)

    override suspend fun readMetadata(entryId: String): EntryMetadataV1 = error(UNUSED)

    private companion object {
        const val UNUSED = "not used by the capture screen"
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

fun publicConfig(maxMedia: Long = 20, maxUploadBytes: Long = MAX_UPLOAD_BYTES): PublicConfigDto = PublicConfigDto(
    signupMode = SignupMode.INVITE,
    sharingEnabled = true,
    publicLinksEnabled = true,
    maxMediaPerEntry = maxMedia,
    maxUploadBytes = maxUploadBytes,
    defaultQuotaBytes = 0,
    trashDays = 30,
    accountDeletionGraceDays = 7,
    serverVersion = "0.1.0",
)

class CaptureHarness {
    val files = FakeEntryFiles()
    val tray = CaptureTray(CaptureTrayDeps(files = files, ioDispatcher = Dispatchers.Unconfined))
    val imports = FakeImports()
    val entries = FakeEntries()
    val index = FakeIndexService()
    val config = FakeConfigService()
    val settings = MutableStateFlow<DeviceSettings>(DeviceSettingsDefaults.ANDROID)
    val camera = FakeCamera()
    val permissions = FakePermissions()
    val captureFiles = FakeCaptureFiles()
    val location = FakeLocation()
    val haptics = mutableListOf<HapticKind>()
    val clock = FakeClock()

    val deps = CaptureDeps(
        tray = tray,
        imports = imports,
        entries = entries,
        index = index,
        config = config,
        settings = settings,
        camera = camera,
        permissions = permissions,
        files = captureFiles,
        location = location,
        haptics = { haptics += it },
        clock = clock,
        fileProviderAuthority = AUTHORITY,
    )

    fun settings(change: (DeviceSettings) -> DeviceSettings) {
        settings.value = change(settings.value)
    }

    fun entry(mediaCount: Int, isOwn: Boolean = true, trashed: Boolean = false) {
        index.put(ENTRY, entryMeta(mediaCount), isOwn, trashed)
    }
}

data class NavCall(val kind: String, val route: Route? = null)

class RecordingNavigator(initial: List<Route>) : RouteNavigator {
    private val stack = initial.toMutableList()

    val calls = mutableListOf<NavCall>()

    override val routes: List<Route> get() = stack.toList()

    override fun push(route: Route) {
        calls += NavCall(PUSH, route)
        stack += route
    }

    override fun pop(): Boolean {
        calls += NavCall(POP)
        if (stack.size <= 1) return false
        stack.removeAt(stack.lastIndex)
        return true
    }

    override fun replace(route: Route) {
        calls += NavCall(REPLACE, route)
        stack[stack.lastIndex] = route
    }

    override fun replaceAll(route: Route) {
        calls += NavCall(REPLACE_ALL, route)
        stack.clear()
        stack += route
    }

    companion object {
        const val PUSH = "push"
        const val POP = "pop"
        const val REPLACE = "replace"
        const val REPLACE_ALL = "replaceAll"
    }
}

class FakePreview : CameraPreview {
    val facings = mutableListOf<CameraFacing>()

    @Composable
    override fun Content(facing: CameraFacing, modifier: Modifier) {
        facings += facing
    }
}

fun solid(color: Color, width: Int = 72, height: Int = 72): ImageBitmap {
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    bitmap.eraseColor(color.toArgb())
    return bitmap.asImageBitmap()
}

fun AndroidComposeTestRule<*, ComponentActivity>.show(
    appTheme: AppTheme = AppTheme.LIGHT,
    preview: CameraPreview? = null,
    content: @Composable () -> Unit,
) {
    setContent {
        KodakikoTheme(appTheme = appTheme) {
            if (preview == null) content() else CompositionLocalProvider(LocalCameraPreview provides preview) { content() }
        }
    }
    waitForIdle()
}

fun labeled(text: String): SemanticsMatcher = hasText(text) or hasContentDescription(text) or hasAnyDescendant(hasText(text))

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
