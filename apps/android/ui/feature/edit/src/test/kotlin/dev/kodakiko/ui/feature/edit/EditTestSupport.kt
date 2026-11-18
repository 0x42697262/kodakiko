@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.kodakiko.ui.feature.edit

import android.content.Context
import android.content.res.Resources
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import dev.kodakiko.domain.EntryEdit
import dev.kodakiko.domain.applyEntryEdit
import dev.kodakiko.domain.localDateOf
import dev.kodakiko.index.GalleryIndex
import dev.kodakiko.index.IndexedEntry
import dev.kodakiko.media.ResolvedTime
import dev.kodakiko.media.TimeSource
import dev.kodakiko.service.account.ConfigService
import dev.kodakiko.service.account.Observable
import dev.kodakiko.service.entries.BulkResult
import dev.kodakiko.service.entries.CreateEntryInput
import dev.kodakiko.service.entries.CropRect
import dev.kodakiko.service.entries.EntriesService
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
import dev.kodakiko.ui.core.notice.GalleryNotice
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
const val NOW_MS = 1_795_262_400_000L
const val NOW_UTC = "2026-11-21T12:00:00.000Z"
const val DEVICE_OFFSET = 60L

fun mediaId(prefix: String): String = prefix.padEnd(MEDIA_ID_LENGTH, '0')

private const val MEDIA_ID_LENGTH = 22

val P1 = mediaId("photo1")
val P2 = mediaId("photo2")
val P3 = mediaId("photo3")
val VID = mediaId("video1")
val VOX1 = mediaId("voice1")
val VOX2 = mediaId("voice2")
val PASTED = mediaId("pasted1")
val RECORDED = mediaId("recorded1")
val CROPPED = mediaId("cropped1")

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
    originalFileName = "IMG_0001.jpg",
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
    thumbnail = ThumbnailDescriptor(256, 256, 1_000),
    capturedAtUtc = null,
    originalFileName = null,
)

val PARIS = EntryLocation(48.8584, 2.2945, null, LocationSource.EXIF)
val NEAR_HOME = EntryLocation(52.52, 13.405, 25.0, LocationSource.DEVICE)

fun metadata(
    media: List<MediaDescriptorV1> = listOf(photo(P1), photo(P2)),
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

fun indexed(id: String, meta: EntryMetadataV1): IndexedEntry {
    val localDate = localDateOf(meta.takenAtUtc, meta.utcOffsetMinutes)
    return IndexedEntry(
        id = id,
        isOwn = true,
        collectionIds = listOf(HOME),
        trashed = false,
        unreadable = false,
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

fun inputFile(name: String): MediaInput = MediaInput(MediaOrigin.PICKER, File("/tmp/kodakiko-edit-test/$name"), null, null)

fun imported(descriptor: MediaDescriptorV1): ImportedMedia = ImportedMedia(
    mediaId = descriptor.mediaId,
    mediaKey = ByteArray(KEY_BYTES) { 7 },
    originalObjectId = mediaId("original"),
    thumbnailObjectId = mediaId("thumbnail"),
    originalCiphertextBytes = 100,
    thumbnailCiphertextBytes = 10,
    descriptor = descriptor,
    time = ResolvedTime(NOW_UTC, DEVICE_OFFSET, TimeSource.NOW),
    location = null,
    thumbnailBytes = byteArrayOf(1, 2, 3),
)

private const val KEY_BYTES = 32

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

fun <S, E> TestScope.observe(state: StateFlow<S>, events: Flow<E>, log: MutableList<String>? = null): Observed<S, E> {
    val received = mutableListOf<E>()
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { state.collect {} }
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
        events.collect {
            received += it
            log?.add("event:$it")
        }
    }
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

class FakeIndexService : IndexService {
    val gallery = GalleryIndex(HOME)
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val versionState = MutableStateFlow(IndexVersion(IndexStatus.READY, 1, 0))

    override val version: StateFlow<IndexVersion> = versionState.asStateFlow()

    fun put(id: String, meta: EntryMetadataV1) {
        gallery.upsert(indexed(id, meta), meta.note)
        versionState.value = IndexVersion(IndexStatus.READY, 1, gallery.version())
        listeners.forEach { it() }
    }

    override suspend fun build(): IndexBuildReport = IndexBuildReport(gallery.size(), 0, 0)

    override fun index(): GalleryIndex = gallery

    override fun status(): IndexStatus = IndexStatus.READY

    override fun subscribe(listener: () -> Unit): () -> Unit {
        listeners += listener
        return { listeners.remove(listener) }
    }

    override fun clear() = Unit
}

class FakeEntries(private val log: MutableList<String>) : EntriesService {
    val stored = LinkedHashMap<String, EntryMetadataV1>()
    val edits = mutableListOf<EntryEdit>()
    val removed = mutableListOf<String>()
    val added = mutableListOf<List<String>>()
    val favorites = mutableListOf<Boolean>()
    val trashed = mutableListOf<List<String>>()
    val deleted = mutableListOf<List<String>>()
    var deleteFailure: Throwable? = null
    val created = mutableListOf<CreateEntryInput>()
    var reads = 0
    var readFailure: Throwable? = null
    var editFailures = 0
    var removeFailures = 0
    var editGate: CompletableDeferred<Unit>? = null
    var createResult: () -> String = { "created-entry" }

    fun meta(id: String = ENTRY): EntryMetadataV1 = stored.getValue(id)

    override suspend fun readMetadata(entryId: String): EntryMetadataV1 {
        reads += 1
        readFailure?.let { throw it }
        return stored[entryId] ?: throw IllegalStateException("no entry $entryId")
    }

    override suspend fun edit(entryId: String, edit: EntryEdit) {
        log += "edit"
        editGate?.await()
        if (editFailures > 0) {
            editFailures -= 1
            throw IllegalStateException("disk full")
        }
        edits += edit
        stored[entryId] = applyEntryEdit(meta(entryId), edit, NOW_UTC)
    }

    override suspend fun addMedia(entryId: String, items: List<ImportedMedia>) {
        log += "addMedia:" + items.joinToString(",") { it.mediaId }
        added += items.map { it.mediaId }
        stored[entryId] = applyEntryEdit(meta(entryId), EntryEdit(addMedia = items.map { it.descriptor }), NOW_UTC)
    }

    override suspend fun removeMedia(entryId: String, mediaId: String) {
        log += "removeMedia:$mediaId"
        if (removeFailures > 0) {
            removeFailures -= 1
            throw IllegalStateException("disk full")
        }
        removed += mediaId
        stored[entryId] = applyEntryEdit(meta(entryId), EntryEdit(removeMediaId = mediaId), NOW_UTC)
    }

    override suspend fun setFavorite(entryIds: List<String>, favorite: Boolean): BulkResult {
        log += "setFavorite:$favorite"
        favorites += favorite
        for (id in entryIds) stored[id] = applyEntryEdit(meta(id), EntryEdit(favorite = favorite), NOW_UTC)
        return BulkResult(entryIds.size, 0)
    }

    override suspend fun trash(entryIds: List<String>): BulkResult {
        log += "trash"
        trashed += entryIds
        return BulkResult(entryIds.size, 0)
    }

    override suspend fun createFromTray(input: CreateEntryInput): String {
        log += "createFromTray"
        created += input
        return createResult()
    }

    override suspend fun restore(entryIds: List<String>): BulkResult = error(UNUSED)

    override suspend fun deletePermanently(entryIds: List<String>): BulkResult {
        log += "deletePermanently"
        deleteFailure?.let { throw it }
        deleted += entryIds
        entryIds.forEach { stored.remove(it) }
        return BulkResult(entryIds.size, 0)
    }

    override suspend fun createFromMedia(input: CreateEntryInput, items: List<ImportedMedia>): String = error(UNUSED)

    override suspend fun addTagToMany(entryIds: List<String>, tag: String): BulkResult = error(UNUSED)

    override suspend fun removeTagFromMany(entryIds: List<String>, tag: String): BulkResult = error(UNUSED)

    override suspend fun renameTag(entryIds: List<String>, from: String, to: String): BulkResult = error(UNUSED)

    override suspend fun deleteTag(entryIds: List<String>, tag: String): BulkResult = error(UNUSED)

    private companion object {
        const val UNUSED = "not used by the edit page"
    }
}

data class ImportCall(val input: MediaInput, val slots: ImportSlots, val voice: VoiceNoteInfo? = null)

class FakeImports(private val log: MutableList<String>) : ImportPipeline {
    val calls = mutableListOf<ImportCall>()
    var next: MediaDescriptorV1 = photo(PASTED)
    var nextVoice: MediaDescriptorV1 = voice(RECORDED, 2_400)
    var refuse: ImportRefusal? = null

    override suspend fun importOne(input: MediaInput, slots: ImportSlots): ImportOutcome {
        log += "importOne"
        calls += ImportCall(input, slots)
        refuse?.let { return ImportOutcome.Refused(it, input.fileName) }
        return ImportOutcome.Imported(imported(next))
    }

    override suspend fun importVoiceNote(input: MediaInput, voice: VoiceNoteInfo, slots: ImportSlots): ImportOutcome {
        log += "importVoiceNote"
        calls += ImportCall(input, slots, voice)
        refuse?.let { return ImportOutcome.Refused(it, input.fileName) }
        return ImportOutcome.Imported(imported(nextVoice))
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

fun publicConfig(sharing: Boolean = true, links: Boolean = true, maxMedia: Long = 20): PublicConfigDto = PublicConfigDto(
    signupMode = SignupMode.INVITE,
    sharingEnabled = sharing,
    publicLinksEnabled = links,
    maxMediaPerEntry = maxMedia,
    maxUploadBytes = 1000,
    defaultQuotaBytes = 0,
    trashDays = 30,
    accountDeletionGraceDays = 7,
    serverVersion = "0.1.0",
)

class FakeClipboard(private val log: MutableList<String>) : EditClipboard {
    var image: MediaInput? = null
    var checks = 0
    val copied = mutableListOf<String>()

    override fun copyText(text: String) {
        log += "copyText"
        copied += text
    }

    override suspend fun hasImage(): Boolean {
        checks += 1
        return image != null
    }

    override suspend fun readImage(): MediaInput? {
        log += "readClipboard"
        return image
    }
}

class FakeRecorder(private val log: MutableList<String>) : EditRecorder {
    var startFailure: Throwable? = null
    var elapsed = 0L
    var result: RecordedVoiceNote? = RecordedVoiceNote(
        input = MediaInput(MediaOrigin.CAMERA, File("/tmp/kodakiko-edit-test/voice.m4a"), null, null),
        durationMs = 2_400,
        mimeType = "audio/mp4",
    )
    var recording = false

    override suspend fun start() {
        log += "recordStart"
        startFailure?.let { throw it }
        recording = true
        elapsed = 0
    }

    override suspend fun stop(): RecordedVoiceNote? {
        log += "recordStop"
        recording = false
        return result
    }

    override fun elapsedMs(): Long = elapsed
}

class FakeVoice(private val log: MutableList<String>) : EditVoice {
    private val state = MutableStateFlow<String?>(null)
    val toggles = mutableListOf<String>()

    override val playing: StateFlow<String?> = state.asStateFlow()

    override suspend fun toggle(entryId: String, mediaId: String) {
        toggles += mediaId
        state.value = if (state.value == mediaId) null else mediaId
    }

    override fun stop() {
        log += "voiceStop"
        state.value = null
    }
}

class FakeSharing(private val log: MutableList<String>) : EditSharing {
    override suspend fun shareItem(entryId: String, mediaId: String) {
        log += "shareItem:$mediaId"
    }

    override suspend fun saveToDevice(entryId: String, mediaId: String) {
        log += "saveToDevice:$mediaId"
    }
}

class FakePicks(private val log: MutableList<String>) : EditPicks {
    val inputs = LinkedHashMap<Uri, MediaInput?>()
    val imported = mutableListOf<Pair<MediaInput, String?>>()

    override suspend fun copy(uri: Uri): MediaInput? {
        log += "copy:$uri"
        return inputs[uri]
    }

    override fun imported(input: MediaInput, mediaId: String?) {
        imported += input to mediaId
    }
}

class FakeDeviceOriginals(private val log: MutableList<String>) : DeviceOriginals {
    val pending = linkedSetOf<String>()
    val counted = mutableListOf<List<String>>()
    val kept = mutableListOf<List<String>>()
    val deletedOriginals = mutableListOf<List<String>>()
    val forgotten = mutableListOf<List<String>>()
    val replacements = mutableListOf<Pair<String, String>>()

    override fun deletableCount(mediaIds: Collection<String>): Int {
        counted += mediaIds.toList()
        return mediaIds.count { it in pending }
    }

    override fun replaced(oldMediaId: String, newMediaId: String) {
        replacements += oldMediaId to newMediaId
        if (pending.remove(oldMediaId)) pending += newMediaId
    }

    override fun keep(mediaIds: Collection<String>) {
        log += "keepOriginals"
        kept += mediaIds.toList()
        pending.removeAll(mediaIds.toSet())
    }

    override suspend fun delete(mediaIds: Collection<String>) {
        log += "deleteOriginals"
        deletedOriginals += mediaIds.filter { it in pending }
        pending.removeAll(mediaIds.toSet())
    }

    override fun forget(mediaIds: Collection<String>) {
        log += "forgetOriginals"
        forgotten += mediaIds.toList()
        pending.removeAll(mediaIds.toSet())
    }
}

class FakeEditClock(var nowMs: Long = NOW_MS, var offset: Long = DEVICE_OFFSET) : EditClock {
    override fun nowMs(): Long = nowMs

    override fun deviceOffsetMinutes(atMs: Long): Long = offset
}

class EditHarness {
    val log: MutableList<String> = mutableListOf()
    val entries = FakeEntries(log)
    val imports = FakeImports(log)
    val index = FakeIndexService()
    val config = FakeConfigService()
    val settings = MutableStateFlow<DeviceSettings>(DeviceSettingsDefaults.ANDROID)
    val clipboard = FakeClipboard(log)
    val recorder = FakeRecorder(log)
    val voice = FakeVoice(log)
    val sharing = FakeSharing(log)
    val notices = Observable<GalleryNotice?>(null)
    val flushes = EditFlushes()
    val clock = FakeEditClock()
    val picks = FakePicks(log)
    val originals = FakeDeviceOriginals(log)

    val deps = EditDeps(
        entries = entries,
        imports = imports,
        index = index,
        config = config,
        settings = settings,
        clipboard = clipboard,
        recorder = recorder,
        voice = voice,
        sharing = sharing,
        notices = notices,
        flushes = flushes,
        clock = clock,
        picks = picks,
        originals = originals,
    )

    fun store(meta: EntryMetadataV1, id: String = ENTRY) {
        entries.stored[id] = meta
        index.put(id, meta)
    }

    fun tagged(vararg tags: String) {
        tags.forEachIndexed { position, tag -> index.put("tagged-$position", metadata(media = listOf(photo(mediaId("tagged$position"))), tags = listOf(tag))) }
    }
}

fun solid(color: Color, width: Int = 64, height: Int = 48): ImageBitmap {
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    bitmap.eraseColor(color.toArgb())
    return bitmap.asImageBitmap()
}

private val PALETTE = listOf(Color(0xFF8D6E63), Color(0xFF4DB6AC), Color(0xFF7986CB), Color(0xFFFFB74D), Color(0xFF81C784))

fun colorOf(id: String): Color = PALETTE[Math.floorMod(id.hashCode(), PALETTE.size)]

class FakeEditImages : EditImages {
    val requests = mutableListOf<String>()

    override suspend fun thumbnail(entryId: String, mediaId: String): ImageBitmap {
        requests += mediaId
        return solid(colorOf(mediaId))
    }
}

class FakeCropOriginal(private val log: MutableList<String>, override val bitmap: ImageBitmap = solid(Color(0xFF4DB6AC), 400, 300)) : CropOriginal {
    val crops = mutableListOf<CropRect>()
    var failure: Throwable? = null
    var gate: CompletableDeferred<Unit>? = null
    var released = 0

    override suspend fun crop(rect: CropRect): MediaInput {
        log += "crop"
        crops += rect
        gate?.await()
        failure?.let { throw it }
        return inputFile("cropped.jpg")
    }

    override fun release() {
        log += "release"
        released += 1
    }
}

class FakeCropOriginals(private val log: MutableList<String>) : CropOriginals {
    var original: FakeCropOriginal? = FakeCropOriginal(log)
    val opened = mutableListOf<Pair<String, String>>()

    override suspend fun open(entryId: String, mediaId: String): CropOriginal? {
        log += "open:$mediaId"
        opened += entryId to mediaId
        return original
    }
}

class CropHarness {
    val log: MutableList<String> = mutableListOf()
    val entries = FakeEntries(log)
    val imports = FakeImports(log).apply { next = photo(CROPPED, 3000, 2000) }
    val originals = FakeCropOriginals(log)
    val config = FakeConfigService()

    val deps = CropDeps(entries = entries, imports = imports, originals = originals, config = config)

    fun store(meta: EntryMetadataV1, id: String = ENTRY) {
        entries.stored[id] = meta
    }
}

fun AndroidComposeTestRule<*, ComponentActivity>.show(
    appTheme: AppTheme = AppTheme.LIGHT,
    images: EditImages = FakeEditImages(),
    content: @Composable () -> Unit,
) {
    setContent {
        KodakikoTheme(appTheme = appTheme) {
            CompositionLocalProvider(LocalEditImages provides images) { content() }
        }
    }
    waitForIdle()
}

fun AndroidComposeTestRule<*, *>.count(tag: String): Int = onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().size

fun AndroidComposeTestRule<*, *>.exists(tag: String): Boolean = count(tag) > 0

object Screenshots {
    const val QUALIFIERS = "w360dp-h640dp-xhdpi"
    const val TALL_QUALIFIERS = "w360dp-h1400dp-mdpi"

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
