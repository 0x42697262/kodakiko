@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.kodakiko.ui.feature.calendar

import android.content.Context
import android.content.res.Resources
import android.graphics.Bitmap
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
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.roborazziSystemPropertyTaskType
import dev.kodakiko.data.db.AppTheme
import dev.kodakiko.data.db.DeviceSettings
import dev.kodakiko.data.db.DeviceSettingsDefaults
import dev.kodakiko.data.secure.AccountMode
import dev.kodakiko.domain.localDateOf
import dev.kodakiko.index.GalleryIndex
import dev.kodakiko.index.IndexedEntry
import dev.kodakiko.platform.device.HapticKind
import dev.kodakiko.service.index.IndexBuildReport
import dev.kodakiko.service.index.IndexService
import dev.kodakiko.service.index.IndexStatus
import dev.kodakiko.service.index.IndexVersion
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
const val TODAY = "2026-11-21"

val resources: Resources get() = ApplicationProvider.getApplicationContext<Context>().resources

fun entry(
    id: String,
    takenAtUtc: String,
    trashed: Boolean = false,
    unreadable: Boolean = false,
    coverMediaId: String? = "$id-media",
    collectionIds: List<String> = listOf(HOME),
    isOwn: Boolean = true,
): IndexedEntry {
    val localDate = localDateOf(takenAtUtc, 0)
    return IndexedEntry(
        id = id,
        isOwn = isOwn,
        collectionIds = collectionIds,
        trashed = trashed,
        unreadable = unreadable,
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

    fun drop(entryId: String) {
        gallery.remove(entryId)
        changed()
    }

    fun setStatus(status: IndexStatus) {
        currentStatus = status
        changed()
    }

    private fun changed() {
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

class FakeCalendarClock(var today: String = TODAY) : CalendarClock {
    override fun todayLocalDate(): String = today
}

class FakeCalendarSync : CalendarSync {
    var calls = 0
    var failure: Throwable? = null
    var gate: CompletableDeferred<Unit>? = null

    override suspend fun syncNow() {
        calls += 1
        gate?.await()
        failure?.let { throw it }
    }
}

class CalendarHarness {
    val index = FakeIndexService()
    val clock = FakeCalendarClock()
    val sync = FakeCalendarSync()
    val mode = MutableStateFlow(AccountMode.SERVER)
    val settings = MutableStateFlow<DeviceSettings>(DeviceSettingsDefaults.ANDROID)
    val haptics = mutableListOf<HapticKind>()

    val deps = CalendarDeps(
        index = index,
        clock = clock,
        sync = sync,
        accountMode = mode,
        settings = settings,
        haptics = { haptics += it },
    )
}

class FakeCalendarThumbnails(private val missing: Set<String> = emptySet()) : CalendarThumbnails {
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
    thumbnails: CalendarThumbnails = FakeCalendarThumbnails(),
    content: @Composable () -> Unit,
) {
    setContent {
        KodakikoTheme(appTheme = appTheme) {
            CompositionLocalProvider(LocalCalendarThumbnails provides thumbnails) { content() }
        }
    }
    waitForIdle()
}

fun AndroidComposeTestRule<*, *>.count(tag: String): Int = onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().size

fun AndroidComposeTestRule<*, *>.exists(tag: String): Boolean = count(tag) > 0

object CalendarStates {
    fun state(
        month: String = "2026-11",
        today: String = TODAY,
        months: List<String> = listOf("2026-09", "2026-10", "2026-11"),
        days: Map<String, CalendarDay> = emptyMap(),
        canGoNext: Boolean = false,
        jump: CalendarJump? = null,
        pullToSync: Boolean = true,
        refreshing: Boolean = false,
    ): CalendarState = CalendarState(
        today = today,
        month = month,
        months = months,
        days = days,
        canGoNext = canGoNext,
        jump = jump,
        pullToSync = pullToSync,
        refreshing = refreshing,
    )

    val november: Map<String, CalendarDay> = mapOf(
        "2026-11-03" to CalendarDay(count = 2, coverEntryId = "b", coverMediaId = "b-media"),
        "2026-11-09" to CalendarDay(count = 3, coverEntryId = "n", coverMediaId = "n-media"),
        "2026-11-17" to CalendarDay(count = 1, coverEntryId = "s", coverMediaId = "s-media"),
        "2026-11-21" to CalendarDay(count = 1, coverEntryId = "t", coverMediaId = "t-media"),
        "2026-10-30" to CalendarDay(count = 1, coverEntryId = "o", coverMediaId = "o-media"),
    )
}

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
