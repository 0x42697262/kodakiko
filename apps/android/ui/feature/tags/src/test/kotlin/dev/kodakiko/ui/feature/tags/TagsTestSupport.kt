@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.kodakiko.ui.feature.tags

import android.content.Context
import android.content.res.Resources
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
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
import dev.kodakiko.data.db.AppTheme
import dev.kodakiko.data.db.DeviceSettings
import dev.kodakiko.data.db.DeviceSettingsDefaults
import dev.kodakiko.domain.EntryEdit
import dev.kodakiko.index.GalleryIndex
import dev.kodakiko.index.IndexedEntry
import dev.kodakiko.service.entries.BulkResult
import dev.kodakiko.service.entries.CreateEntryInput
import dev.kodakiko.service.entries.EntriesService
import dev.kodakiko.service.entries.ImportedMedia
import dev.kodakiko.service.index.IndexBuildReport
import dev.kodakiko.service.index.IndexService
import dev.kodakiko.service.index.IndexStatus
import dev.kodakiko.service.index.IndexVersion
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

object TagsTestTags {
    const val BACK = "settings.back"
    const val DRAG = "tags.drag"
}

const val HOME = "home-collection"
const val OTHER_COLLECTION = "shared-collection"

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

class FakeIndexService(var available: Boolean = true) : IndexService {
    val gallery = GalleryIndex(HOME)
    private val versionState = MutableStateFlow(IndexVersion(IndexStatus.READY, 1, 0))

    override val version: StateFlow<IndexVersion> = versionState.asStateFlow()

    fun put(
        id: String,
        tags: List<String>,
        takenAtUtc: String = "2026-11-21T08:00:00.000Z",
        isOwn: Boolean = true,
        trashed: Boolean = false,
        collectionIds: List<String> = listOf(HOME),
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
                tags = tags,
                coverMediaId = null,
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

    fun tagsOf(id: String): List<String> = gallery.get(id)?.tags.orEmpty()

    fun retag(id: String, tags: List<String>) {
        val entry = requireNotNull(gallery.get(id)) { "no entry $id" }
        gallery.upsert(entry.copy(tags = tags), "")
        bump()
    }

    private fun bump() {
        versionState.value = IndexVersion(IndexStatus.READY, 1, gallery.version())
    }

    override suspend fun build(): IndexBuildReport = IndexBuildReport(gallery.size(), 0, 0)

    override fun index(): GalleryIndex? = if (available) gallery else null

    override fun status(): IndexStatus = IndexStatus.READY

    override fun subscribe(listener: () -> Unit): () -> Unit = {}

    override fun clear() = Unit

    private companion object {
        const val LOCAL_DATE_LENGTH = 10
        const val MONTH_DAY_START = 5
    }
}

data class TagCall(val kind: String, val entryIds: List<String>, val from: String, val to: String? = null)

class FakeTagEntries(private val index: FakeIndexService) : EntriesService {
    val calls = mutableListOf<TagCall>()
    var failure: Throwable? = null
    var gate: CompletableDeferred<Unit>? = null

    override suspend fun renameTag(entryIds: List<String>, from: String, to: String): BulkResult {
        calls += TagCall(RENAME, entryIds, from, to)
        gate?.await()
        failure?.let { throw it }
        var changed = 0
        for (id in entryIds) {
            val tags = index.tagsOf(id)
            if (from !in tags) continue
            index.retag(id, (tags.filter { it != from } + to).distinct().sorted())
            changed += 1
        }
        return BulkResult(changed, entryIds.size - changed)
    }

    override suspend fun deleteTag(entryIds: List<String>, tag: String): BulkResult {
        calls += TagCall(DELETE, entryIds, tag)
        gate?.await()
        failure?.let { throw it }
        var changed = 0
        for (id in entryIds) {
            val tags = index.tagsOf(id)
            if (tag !in tags) continue
            index.retag(id, tags.filter { it != tag })
            changed += 1
        }
        return BulkResult(changed, entryIds.size - changed)
    }

    override suspend fun createFromTray(input: CreateEntryInput): String = error(UNUSED)

    override suspend fun createFromMedia(input: CreateEntryInput, items: List<ImportedMedia>): String = error(UNUSED)

    override suspend fun edit(entryId: String, edit: EntryEdit) = error(UNUSED)

    override suspend fun addMedia(entryId: String, items: List<ImportedMedia>) = error(UNUSED)

    override suspend fun removeMedia(entryId: String, mediaId: String) = error(UNUSED)

    override suspend fun trash(entryIds: List<String>): BulkResult = error(UNUSED)

    override suspend fun restore(entryIds: List<String>): BulkResult = error(UNUSED)

    override suspend fun deletePermanently(entryIds: List<String>): BulkResult = error(UNUSED)

    override suspend fun addTagToMany(entryIds: List<String>, tag: String): BulkResult = error(UNUSED)

    override suspend fun removeTagFromMany(entryIds: List<String>, tag: String): BulkResult = error(UNUSED)

    override suspend fun setFavorite(entryIds: List<String>, favorite: Boolean): BulkResult = error(UNUSED)

    override suspend fun readMetadata(entryId: String): EntryMetadataV1 = error(UNUSED)

    companion object {
        const val RENAME = "renameTag"
        const val DELETE = "deleteTag"
        private const val UNUSED = "not used by the tag editor"
    }
}

class FakeTagsSettings(initial: DeviceSettings = DeviceSettingsDefaults.ANDROID) : TagsSettings {
    private val state = MutableStateFlow(initial)
    val writes = mutableListOf<List<String>>()

    override val settings: StateFlow<DeviceSettings> = state.asStateFlow()

    override fun update(change: (DeviceSettings) -> DeviceSettings) {
        val next = change(state.value)
        writes += next.tagOrder
        state.value = next
    }

    fun order(tags: List<String>) {
        state.value = state.value.copy(tagOrder = tags)
    }

    val tagOrder: List<String> get() = state.value.tagOrder
}

class TagsHarness {
    val index = FakeIndexService()
    val entries = FakeTagEntries(index)
    val settings = FakeTagsSettings()
    val deps = TagsDeps(index = index, entries = entries, settings = settings)

    fun beachAndCity() {
        index.put("e1", listOf("beach"), takenAtUtc = "2026-05-01T08:00:00.000Z")
        index.put("e2", listOf("beach", "city"), takenAtUtc = "2026-05-02T08:00:00.000Z")
    }
}

fun AndroidComposeTestRule<*, ComponentActivity>.show(appTheme: AppTheme = AppTheme.LIGHT, content: @Composable () -> Unit) {
    setContent { KodakikoTheme(appTheme = appTheme) { content() } }
    waitForIdle()
}

fun labeled(text: String): SemanticsMatcher = hasText(text) or hasContentDescription(text) or hasAnyDescendant(hasText(text))

fun AndroidComposeTestRule<*, *>.count(tag: String): Int = onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().size

fun AndroidComposeTestRule<*, *>.exists(tag: String): Boolean = count(tag) > 0

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
