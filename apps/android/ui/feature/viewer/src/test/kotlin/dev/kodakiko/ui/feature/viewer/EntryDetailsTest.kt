package dev.kodakiko.ui.feature.viewer

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.kodakiko.data.db.DeviceSettings
import dev.kodakiko.data.db.DeviceSettingsDefaults
import dev.kodakiko.service.stats.DetailLabel
import dev.kodakiko.service.stats.DetailRow
import dev.kodakiko.service.stats.DetailSection
import dev.kodakiko.service.stats.DetailSectionKind
import dev.kodakiko.service.stats.DetailValue
import dev.kodakiko.service.stats.JournalStatistics
import dev.kodakiko.service.stats.StatsService
import dev.kodakiko.ui.core.TestTags
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

val DETAIL_SECTIONS: List<DetailSection> = listOf(
    DetailSection(
        DetailSectionKind.ENTRY,
        null,
        listOf(
            DetailRow(DetailLabel.ID, DetailValue.Text("ENTRY1")),
            DetailRow(DetailLabel.TAKEN, DetailValue.Text("2026-11-01 09:00 (UTC+01:00, Europe/Berlin)")),
            DetailRow(DetailLabel.VERSION, DetailValue.Text("3")),
            DetailRow(DetailLabel.SYNC, DetailValue.Text("synced")),
            DetailRow(DetailLabel.TAGS, DetailValue.Text("#beach")),
            DetailRow(DetailLabel.NOTE_LENGTH, DetailValue.Characters(5)),
        ),
    ),
    DetailSection(
        DetailSectionKind.VIDEO,
        1,
        listOf(
            DetailRow(DetailLabel.FILE_NAME, DetailValue.Text("clip.mp4")),
            DetailRow(DetailLabel.TYPE, DetailValue.Text("video/mp4")),
            DetailRow(DetailLabel.SIZE, DetailValue.Bytes(5_242_880)),
            DetailRow(DetailLabel.DIMENSIONS, DetailValue.Text("1920 × 1080")),
            DetailRow(DetailLabel.DURATION, DetailValue.Duration(20_500)),
            DetailRow(DetailLabel.ORIGINAL, DetailValue.Storage(onDevice = true, uploaded = true)),
            DetailRow(DetailLabel.THUMBNAIL, DetailValue.Storage(onDevice = false, uploaded = false)),
            DetailRow(DetailLabel.ENCRYPTED_SIZE, DetailValue.Bytes(5_300_000)),
        ),
    ),
    DetailSection(
        DetailSectionKind.PHOTO,
        2,
        listOf(
            DetailRow(DetailLabel.TYPE, DetailValue.Text("image/jpeg")),
            DetailRow(DetailLabel.ORIGINAL, DetailValue.Unknown),
        ),
    ),
)

class FakeStats : StatsService {
    val calls = mutableListOf<String>()
    var failure: Throwable? = null
    var sections: List<DetailSection> = DETAIL_SECTIONS

    override fun statistics(): JournalStatistics = error("not used by the details screen")

    override suspend fun entryDetails(entryId: String): List<DetailSection> {
        calls += entryId
        failure?.let { throw it }
        return sections
    }
}

@RunWith(RobolectricTestRunner::class)
class EntryDetailsViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val stats = FakeStats()
    private val settings = MutableStateFlow<DeviceSettings>(DeviceSettingsDefaults.ANDROID)

    private fun TestScope.open(): Pair<EntryDetailsViewModel, Observed<EntryDetailsState, EntryDetailsEvent>> {
        val vm = EntryDetailsViewModel(EntryDetailsDeps(stats = stats, settings = settings), ENTRY)
        return vm to observe(vm.state, vm.events)
    }

    @Test
    fun loadsTheSectionsOfTheEntry() = runTest {
        val (_, screen) = open()
        assertEquals(listOf(ENTRY), stats.calls)
        assertEquals(DETAIL_SECTIONS, screen.value.sections)
    }

    @Test
    fun turningTechnicalDetailsOnOrOffReloads() = runTest {
        val (_, screen) = open()
        settings.value = settings.value.copy(showTechnicalDetails = true)
        assertEquals(listOf(ENTRY, ENTRY), stats.calls)
        settings.value = settings.value.copy(hapticFeedback = !settings.value.hapticFeedback)
        assertEquals(listOf(ENTRY, ENTRY), stats.calls)
        settings.value = settings.value.copy(showTechnicalDetails = false)
        assertEquals(3, stats.calls.size)
        assertEquals(DETAIL_SECTIONS, screen.value.sections)
    }

    @Test
    fun aFailedLoadShowsNoSections() = runTest {
        stats.failure = IllegalStateException("gone")
        val (_, screen) = open()
        assertEquals(emptyList<DetailSection>(), screen.value.sections)
    }

    @Test
    fun backLeavesTheScreen() = runTest {
        val (vm, screen) = open()
        vm.onAction(EntryDetailsAction.Back)
        assertEquals(listOf<EntryDetailsEvent>(EntryDetailsEvent.Back), screen.events)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h1280dp-mdpi")
class EntryDetailsContentTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun shows(text: String): Boolean = compose.onAllNodes(hasText(text), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun beforeLoadingThereAreNoSections() {
        compose.show { EntryDetailsContent(state = EntryDetailsState(sections = null), onAction = {}) }
        assertTrue(compose.exists(TestTags.Details.SCREEN))
        assertTrue(shows("Details"))
        assertFalse(shows("Entry"))
    }

    @Test
    fun everySectionReadsAsV031WithTypedValuesResolved() {
        compose.show { EntryDetailsContent(state = EntryDetailsState(sections = DETAIL_SECTIONS), onAction = {}) }
        assertTrue(compose.exists(TestTags.Details.SCREEN))
        val expected = listOf(
            "Details", "Entry", "Video 1", "Photo 2",
            "Id", "ENTRY1", "Taken", "2026-11-01 09:00 (UTC+01:00, Europe/Berlin)", "Version", "3", "Sync state", "synced",
            "Tags", "#beach", "Note length", "5 characters",
            "File name", "clip.mp4", "Type", "video/mp4", "Size", "5 MB", "Dimensions", "1920 × 1080", "Duration", "00:20",
            "Original", "On this device, uploaded", "Thumbnail", "Not on this device, not uploaded yet", "Encrypted size", "5.1 MB",
            "image/jpeg", "Unknown",
        )
        for (text in expected) assertTrue(text, shows(text))
    }

    @Test
    fun anEmptyResultShowsOnlyTheTitle() {
        compose.show { EntryDetailsContent(state = EntryDetailsState(sections = emptyList()), onAction = {}) }
        assertTrue(shows("Details"))
        assertFalse(shows("Entry"))
    }
}
