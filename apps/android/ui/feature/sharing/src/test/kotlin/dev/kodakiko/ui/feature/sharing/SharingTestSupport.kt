@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.kodakiko.ui.feature.sharing

import android.content.Context
import android.content.res.Resources
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap
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
import dev.kodakiko.contract.MediaDescriptorV1
import dev.kodakiko.contract.MediaKind
import dev.kodakiko.contract.PublicConfigDto
import dev.kodakiko.contract.ShareDto
import dev.kodakiko.contract.SignupMode
import dev.kodakiko.contract.ThumbnailDescriptor
import dev.kodakiko.data.db.AppTheme
import dev.kodakiko.data.db.CollectionRole
import dev.kodakiko.data.secure.AccountMode
import dev.kodakiko.index.GalleryIndex
import dev.kodakiko.index.IndexedEntry
import dev.kodakiko.service.account.ConfigService
import dev.kodakiko.service.index.IndexBuildReport
import dev.kodakiko.service.index.IndexService
import dev.kodakiko.service.index.IndexStatus
import dev.kodakiko.service.index.IndexVersion
import dev.kodakiko.service.shareout.ShareOutResult
import dev.kodakiko.service.shareout.ShareOutService
import dev.kodakiko.service.shareout.SharePickItem
import dev.kodakiko.service.sharing.AddEntriesResult
import dev.kodakiko.service.sharing.CollectionName
import dev.kodakiko.service.sharing.CollectionNames
import dev.kodakiko.service.sharing.Recipient
import dev.kodakiko.service.sharing.ShareItem
import dev.kodakiko.service.sharing.ShareSelection
import dev.kodakiko.service.sharing.ShareTextOption
import dev.kodakiko.service.sharing.SharingErrorReason
import dev.kodakiko.service.sharing.SharingException
import dev.kodakiko.service.sharing.SharingService
import dev.kodakiko.service.sharing.shareSelection
import dev.kodakiko.ui.core.theme.KodakikoTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import java.io.File

object SharingTestTags {
    const val TAB_KODAKIKO = "sheet.tab.kodakiko"
    const val TAB_APPS = "sheet.tab.apps"
    const val TEXT_NONE = "share.text.none"
    const val TEXT_CAPTIONS = "share.text.captions"
    const val TEXT_CAPTIONS_AND_NOTE = "share.text.captionsAndNote"
    const val GALLERY_TILE = "gallery.tile"
    const val GALLERY_ACTIONS = "gallery.actions"
    const val GALLERY_EMPTY = "gallery.empty"
    const val REMOVE_FROM_JOURNAL = "bulk.removeFromJournal"
}

const val HOME = "home-collection"
const val PICNICS = "picnicsjournal00000001"
const val TRIPS = "tripsjournal0000000002"
const val FROM_BOB = "bobsjournal00000000003"
const val ALICE_ID = "aliceuser0000000000001"
const val BOB_ID = "bobuser000000000000002"
const val CAROL_ID = "caroluser0000000000003"
const val FINGERPRINT = "1a2b 3c4d 5e6f 7a8b"
const val MB = 1_048_576L

val context: Context get() = ApplicationProvider.getApplicationContext()

val resources: Resources get() = context.resources

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
    maxUploadBytes = 1000,
    defaultQuotaBytes = 0,
    trashDays = 30,
    accountDeletionGraceDays = 7,
    serverVersion = "0.3.1",
)

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

class FakeIndexService : IndexService {
    val gallery = GalleryIndex(HOME)
    private val versionState = MutableStateFlow(IndexVersion(IndexStatus.READY, 1, 0))

    override val version: StateFlow<IndexVersion> = versionState.asStateFlow()

    fun put(
        id: String,
        collectionIds: List<String>,
        takenAtUtc: String = "2026-05-03T08:00:00.000Z",
        trashed: Boolean = false,
        coverMediaId: String? = "$id-cover",
        isOwn: Boolean = true,
    ) {
        val localDate = takenAtUtc.substring(0, LOCAL_DATE_LENGTH)
        gallery.upsert(
            IndexedEntry(
                id = id,
                isOwn = isOwn,
                collectionIds = collectionIds,
                trashed = trashed,
                unreadable = false,
                takenAtUtc = takenAtUtc,
                utcOffsetMinutes = 0,
                localDate = localDate,
                monthDay = localDate.substring(MONTH_DAY_START),
                tags = emptyList(),
                coverMediaId = coverMediaId,
                mediaCount = 1,
                hasVideo = false,
                notePreview = "",
                pending = false,
                favorite = null,
            ),
            "",
        )
        bump()
    }

    fun remove(id: String) {
        gallery.remove(id)
        bump()
    }

    private fun bump() {
        versionState.value = IndexVersion(IndexStatus.READY, 1, gallery.version())
    }

    override suspend fun build(): IndexBuildReport = IndexBuildReport(gallery.size(), 0, 0)

    override fun index(): GalleryIndex? = gallery

    override fun status(): IndexStatus = IndexStatus.READY

    override fun subscribe(listener: () -> Unit): () -> Unit = {}

    override fun clear() = Unit

    private companion object {
        const val LOCAL_DATE_LENGTH = 10
        const val MONTH_DAY_START = 5
    }
}

class FakeCollectionNames : CollectionNames {
    val names = mutableMapOf<String, CollectionName>()
    var clears = 0

    override suspend fun get(collectionId: String): CollectionName = names[collectionId] ?: CollectionName.Unnamed

    override fun clear() {
        clears += 1
    }
}

class FakeJournalRows : SharedJournalRows {
    val rows = mutableListOf<SharedJournalRow>()
    var loads = 0

    override suspend fun load(): List<SharedJournalRow> {
        loads += 1
        return rows.toList()
    }
}

data class SharingCall(val kind: String, val collectionId: String? = null, val args: List<String> = emptyList())

class FakeSharingService(private val names: FakeCollectionNames, private val rows: FakeJournalRows) : SharingService {
    val calls = mutableListOf<SharingCall>()
    val shares = mutableMapOf<String, MutableList<ShareDto>>()
    val users = mutableMapOf<String, Recipient>()
    var failure: Throwable? = null
    var failOn: String? = null
    var gate: CompletableDeferred<Unit>? = null
    var nextId = "newjournal000000000001"
    var addResult = AddEntriesResult(added = emptyList(), alreadyIn = emptyList(), notSynced = emptyList(), failed = emptyList())

    private suspend fun enter(kind: String, collectionId: String?, args: List<String>) {
        calls += SharingCall(kind, collectionId, args)
        gate?.await()
        val error = failure
        if (error != null && (failOn == null || failOn == kind)) throw error
    }

    fun kinds(): List<String> = calls.map { it.kind }

    override suspend fun createJournal(name: String): String {
        enter(CREATE, null, listOf(name))
        names.names[nextId] = CollectionName.Named(name)
        rows.rows += SharedJournalRow(nextId, CollectionRole.OWNER, "alice")
        return nextId
    }

    override suspend fun renameJournal(id: String, name: String) {
        enter(RENAME, id, listOf(name))
        names.names[id] = CollectionName.Named(name)
    }

    override suspend fun deleteJournal(id: String) {
        enter(DELETE, id, emptyList())
        rows.rows.removeAll { it.id == id }
    }

    override suspend fun addEntries(collectionId: String, entryIds: List<String>): AddEntriesResult {
        enter(ADD, collectionId, entryIds)
        return addResult
    }

    override suspend fun removeEntries(collectionId: String, entryIds: List<String>) {
        enter(REMOVE, collectionId, entryIds)
    }

    override suspend fun lookupRecipient(username: String): Recipient {
        enter(LOOKUP, null, listOf(username))
        return users[username] ?: throw SharingException(SharingErrorReason.USER_NOT_FOUND)
    }

    override suspend fun share(collectionId: String, recipient: Recipient) {
        enter(SHARE, collectionId, listOf(recipient.userId))
        shares.getOrPut(collectionId) { mutableListOf() } += ShareDto(recipient.userId, recipient.username, "2026-11-20T10:00:00.000Z")
    }

    override suspend fun listShares(collectionId: String): List<ShareDto> {
        enter(LIST, collectionId, emptyList())
        return shares[collectionId].orEmpty().toList()
    }

    override suspend fun unshare(collectionId: String, recipientUserId: String) {
        enter(UNSHARE, collectionId, listOf(recipientUserId))
        shares[collectionId]?.removeAll { it.recipientUserId == recipientUserId }
    }

    override suspend fun leave(collectionId: String) {
        enter(LEAVE, collectionId, emptyList())
        rows.rows.removeAll { it.id == collectionId }
    }

    override fun myFingerprint(): String = FINGERPRINT

    companion object {
        const val CREATE = "createJournal"
        const val RENAME = "renameJournal"
        const val DELETE = "deleteJournal"
        const val ADD = "addEntries"
        const val REMOVE = "removeEntries"
        const val LOOKUP = "lookupRecipient"
        const val SHARE = "share"
        const val LIST = "listShares"
        const val UNSHARE = "unshare"
        const val LEAVE = "leave"
    }
}

data class ShareOutCall(val items: List<SharePickItem>, val chosen: Set<String>, val option: ShareTextOption)

class FakeShareOutService : ShareOutService {
    val byEntry = mutableMapOf<String, List<SharePickItem>>()
    val itemRequests = mutableListOf<List<String>>()
    val shared = mutableListOf<ShareOutCall>()
    var itemsFailure: Throwable? = null
    var shareFailure: Throwable? = null
    var shareGate: CompletableDeferred<Unit>? = null

    fun store(entryId: String, note: String, vararg media: MediaDescriptorV1) {
        byEntry[entryId] = media.mapIndexed { position, descriptor -> SharePickItem(entryId, descriptor, position, note) }
    }

    override suspend fun items(entryIds: List<String>): List<SharePickItem> {
        itemRequests += entryIds
        itemsFailure?.let { throw it }
        return entryIds.flatMap { byEntry[it].orEmpty() }
    }

    override fun selection(items: List<SharePickItem>, chosen: Set<String>): ShareSelection =
        shareSelection(items.map { ShareItem(it.media.mediaId, it.media.originalPlaintextBytes, it.media.caption) }, chosen)

    override suspend fun share(items: List<SharePickItem>, chosen: Set<String>, option: ShareTextOption): ShareOutResult {
        shared += ShareOutCall(items, chosen, option)
        shareGate?.await()
        shareFailure?.let { throw it }
        return ShareOutResult(shared = chosen.size, missing = 0)
    }

    override suspend fun releaseAll() = Unit
}

class FakeThumbnails : SharingThumbnails {
    val requests = mutableListOf<Pair<String, String>>()

    override suspend fun load(entryId: String, mediaId: String): ImageBitmap? {
        requests += entryId to mediaId
        return null
    }
}

fun photo(id: String, bytes: Long = 2 * MB, caption: String? = null): MediaDescriptorV1 = MediaDescriptorV1(
    mediaId = id,
    kind = MediaKind.PHOTO,
    mimeType = "image/jpeg",
    width = 4000,
    height = 3000,
    durationMs = null,
    originalPlaintextBytes = bytes,
    thumbnail = ThumbnailDescriptor(512, 384, 30_000),
    capturedAtUtc = null,
    originalFileName = null,
    caption = caption,
)

fun video(id: String, bytes: Long = 20 * MB, caption: String? = null): MediaDescriptorV1 = MediaDescriptorV1(
    mediaId = id,
    kind = MediaKind.VIDEO,
    mimeType = "video/mp4",
    width = 1920,
    height = 1080,
    durationMs = 20_000,
    originalPlaintextBytes = bytes,
    thumbnail = ThumbnailDescriptor(512, 288, 20_000),
    capturedAtUtc = null,
    originalFileName = null,
    caption = caption,
)

fun recipient(userId: String, username: String, fingerprint: String = FINGERPRINT): Recipient =
    Recipient(userId = userId, username = username, publicKey = ByteArray(PUBLIC_KEY_BYTES) { 1 }, fingerprint = fingerprint)

fun shareDto(userId: String, username: String): ShareDto = ShareDto(userId, username, "2026-11-20T10:00:00.000Z")

const val PUBLIC_KEY_BYTES = 32

fun journal(
    id: String,
    name: String?,
    role: CollectionRole = CollectionRole.OWNER,
    owner: String = "alice",
    count: Int = 0,
): SharedJournal = SharedJournal(
    id = id,
    name = if (name == null) CollectionName.Unnamed else CollectionName.Named(name),
    role = role,
    ownerUsername = owner,
    entryCount = count,
)

class SharingHarness {
    val config = FakeConfigService()
    val accountMode = MutableStateFlow(AccountMode.SERVER)
    val syncedAt = MutableStateFlow<Long?>(null)
    val index = FakeIndexService()
    val names = FakeCollectionNames()
    val rows = FakeJournalRows()
    val sharing = FakeSharingService(names, rows)
    val shareOut = FakeShareOutService()
    var syncs = 0
    var syncGate: CompletableDeferred<Unit>? = null

    val deps = SharingDeps(
        sharing = sharing,
        names = names,
        rows = rows,
        index = index,
        shareOut = shareOut,
        config = config,
        accountMode = accountMode,
        syncedAt = syncedAt,
        syncNow = {
            syncs += 1
            syncGate?.await()
        },
    )

    fun journalRow(id: String, name: String?, role: CollectionRole = CollectionRole.OWNER, owner: String = "alice") {
        rows.rows += SharedJournalRow(id, role, owner)
        if (name != null) names.names[id] = CollectionName.Named(name)
    }

    fun picnicsWithTwoEntries() {
        journalRow(PICNICS, "Picnics")
        index.put("e1", listOf(HOME, PICNICS), takenAtUtc = "2026-05-03T08:00:00.000Z")
        index.put("e2", listOf(HOME, PICNICS), takenAtUtc = "2026-05-04T08:00:00.000Z")
    }

    fun bobsJournalWithTwoEntries() {
        journalRow(FROM_BOB, "Picnics", CollectionRole.VIEWER, "bob")
        index.put("b1", listOf(FROM_BOB), takenAtUtc = "2026-05-03T08:00:00.000Z", isOwn = false)
        index.put("b2", listOf(FROM_BOB), takenAtUtc = "2026-05-04T08:00:00.000Z", isOwn = false)
    }

    fun pairEntry() {
        shareOut.store("pair", "pair", photo("p1", 2 * MB, "Beach"), photo("p2", 3 * MB))
    }
}

fun AndroidComposeTestRule<*, ComponentActivity>.show(appTheme: AppTheme = AppTheme.LIGHT, content: @Composable () -> Unit) {
    setContent { KodakikoTheme(appTheme = appTheme) { content() } }
    waitForIdle()
}

fun labeled(text: String): SemanticsMatcher = hasText(text) or hasContentDescription(text) or hasAnyDescendant(hasText(text))

fun contains(text: String): SemanticsMatcher = hasText(text, substring = true) or hasAnyDescendant(hasText(text, substring = true))

fun chosen(): SemanticsMatcher =
    SemanticsMatcher.expectValue(SemanticsProperties.Selected, true) or SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.On)

fun notChosen(): SemanticsMatcher =
    SemanticsMatcher.expectValue(SemanticsProperties.Selected, false) or SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.Off)

fun AndroidComposeTestRule<*, *>.count(tag: String): Int = onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().size

fun AndroidComposeTestRule<*, *>.exists(tag: String): Boolean = count(tag) > 0

fun AndroidComposeTestRule<*, *>.showsText(text: String): Boolean = onAllNodes(hasText(text), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

object Screenshots {
    const val QUALIFIERS = "w320dp-h640dp-xhdpi"

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
