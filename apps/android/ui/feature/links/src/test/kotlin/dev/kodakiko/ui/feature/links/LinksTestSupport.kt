@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.kodakiko.ui.feature.links

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
import dev.kodakiko.contract.EntryMetadataV1
import dev.kodakiko.contract.MediaDescriptorV1
import dev.kodakiko.contract.MediaKind
import dev.kodakiko.contract.ThumbnailDescriptor
import dev.kodakiko.data.db.AppTheme
import dev.kodakiko.service.links.CreateLinkInput
import dev.kodakiko.service.links.CreateLinkProgress
import dev.kodakiko.service.links.CreateLinkResult
import dev.kodakiko.service.links.OwnerLink
import dev.kodakiko.service.links.PublicLinksService
import dev.kodakiko.ui.core.theme.KodakikoTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import java.io.File
import java.time.Instant

object LinksTestTags {
    const val COPY = "links.copy"
    const val COPIED = "links.copied"
    const val SHARE_ROW = "links.shareRow"
    const val BACK = "shared.back"
}

const val ENTRY_A = "entryaaaaaaaaaaaaaaaa1"
const val ENTRY_B = "entrybbbbbbbbbbbbbbbb2"
const val MB = 1_048_576L
const val KB = 1_024L
const val NOW_MS = 1_795_000_000_000L
const val DAY_MS = 86_400_000L
const val HOUR_MS = 3_600_000L
const val NOTE = "Secret picnic spot behind the old mill\nsecond line"
const val LINK_URL = "https://kodakiko.example/s/linkid0000000000000001#c2VjcmV0"
const val DEFAULT_TITLE = "Shared moments"

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

fun media(id: String, kind: MediaKind, bytes: Long, thumbnailBytes: Long): MediaDescriptorV1 = MediaDescriptorV1(
    mediaId = id,
    kind = kind,
    mimeType = if (kind == MediaKind.PHOTO) "image/jpeg" else "video/quicktime",
    width = 4000,
    height = 3000,
    durationMs = if (kind == MediaKind.PHOTO) null else 20_000,
    originalPlaintextBytes = bytes,
    thumbnail = ThumbnailDescriptor(512, 384, thumbnailBytes),
    capturedAtUtc = null,
    originalFileName = null,
)

fun metadata(takenAtUtc: String, note: String, vararg items: MediaDescriptorV1): EntryMetadataV1 = EntryMetadataV1(
    takenAtUtc = takenAtUtc,
    utcOffsetMinutes = 0,
    timeZone = null,
    note = note,
    tags = listOf("picnic"),
    location = null,
    media = items.toList(),
    createdAtUtc = takenAtUtc,
    editedAtUtc = takenAtUtc,
)

fun ownerLink(
    linkId: String,
    title: String,
    expiresAt: String? = null,
    maxViews: Long? = null,
    viewCount: Long = 0,
): OwnerLink = OwnerLink(
    linkId = linkId,
    url = "https://kodakiko.example/s/$linkId#secret",
    title = title,
    expiresAt = expiresAt,
    maxViews = maxViews,
    viewCount = viewCount,
    createdAt = "2026-11-20T10:00:00.000Z",
)

fun isoAt(ms: Long): String = Instant.ofEpochMilli(ms).toString()

class FakeLinksService : PublicLinksService {
    val created = mutableListOf<CreateLinkInput>()
    val revoked = mutableListOf<String>()
    val calls = mutableListOf<String>()
    val links = mutableListOf<OwnerLink>()
    var progressSteps: List<CreateLinkProgress> = emptyList()
    var result = CreateLinkResult(url = LINK_URL, linkId = "linkid0000000000000001", skippedEntries = 0, skippedUndecodable = 0, skippedUnsupportedVideo = 0)
    var createFailure: Throwable? = null
    var listFailure: Throwable? = null
    var revokeFailure: Throwable? = null
    var createGate: CompletableDeferred<Unit>? = null
    var progressGate: CompletableDeferred<Unit>? = null

    override suspend fun create(input: CreateLinkInput, onProgress: (CreateLinkProgress) -> Unit): CreateLinkResult {
        calls += CREATE
        created += input
        for ((index, step) in progressSteps.withIndex()) {
            onProgress(step)
            if (index == progressSteps.lastIndex) progressGate?.await()
        }
        createGate?.await()
        createFailure?.let { throw it }
        return result
    }

    override suspend fun list(): List<OwnerLink> {
        calls += LIST
        listFailure?.let { throw it }
        return links.toList()
    }

    override suspend fun revoke(linkId: String) {
        calls += REVOKE
        revoked += linkId
        revokeFailure?.let { throw it }
        links.removeAll { it.linkId == linkId }
    }

    companion object {
        const val CREATE = "create"
        const val LIST = "list"
        const val REVOKE = "revoke"
    }
}

class FakeLinkSharing : LinkSharing {
    val copied = mutableListOf<String>()
    val shared = mutableListOf<String>()

    override fun copy(text: String) {
        copied += text
    }

    override fun share(text: String) {
        shared += text
    }
}

class FakeLinkThumbnails : LinkThumbnails {
    val requests = mutableListOf<Pair<String, String>>()

    override suspend fun load(entryId: String, mediaId: String): ImageBitmap? {
        requests += entryId to mediaId
        return null
    }
}

class LinksHarness {
    val links = FakeLinksService()
    val sharing = FakeLinkSharing()
    val metadata = mutableMapOf<String, EntryMetadataV1>()
    val reads = mutableListOf<String>()
    var readFailure: Throwable? = null
    var now = NOW_MS

    val deps = LinksDeps(
        links = links,
        readMetadata = { entryId ->
            reads += entryId
            readFailure?.let { throw it }
            requireNotNull(metadata[entryId]) { "no entry $entryId" }
        },
        sharing = sharing,
        defaultTitle = DEFAULT_TITLE,
        nowMs = { now },
    )

    fun u18Entry() {
        metadata[ENTRY_A] = metadata(
            "2026-05-05T08:00:00.000Z",
            NOTE,
            media("photogps", MediaKind.PHOTO, 3 * MB, 40 * KB),
            media("videogpsmov", MediaKind.VIDEO, 20 * MB, 30 * KB),
            media("video20s", MediaKind.VIDEO, 10 * MB, 30 * KB),
        )
    }

    fun secondEntry() {
        metadata[ENTRY_B] = metadata("2026-05-06T09:30:00.000Z", "", media("lake", MediaKind.PHOTO, 2 * MB, 20 * KB))
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
