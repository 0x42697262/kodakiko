package dev.kodakiko.ui.feature.viewer

import dev.kodakiko.contract.MediaKind
import dev.kodakiko.data.secure.AccountMode
import dev.kodakiko.service.index.IndexStatus
import dev.kodakiko.ui.core.navigation.Route
import dev.kodakiko.ui.core.notice.GalleryNotice
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class EntryViewerViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val h = ViewerHarness()

    private val full = metadata(
        media = listOf(photo("p1", caption = "Heron at dawn"), voice("v1"), video("m2"), photo("p3")),
        note = "**swim** at the lake",
        tags = listOf("beach", "family"),
        location = BERLIN,
        favorite = null,
    )

    private fun TestScope.open(entryId: String = ENTRY): Pair<EntryViewerViewModel, Observed<ViewerState, ViewerEvent>> {
        val vm = EntryViewerViewModel(h.deps, entryId)
        return vm to observe(vm.state, vm.events)
    }

    private val ViewerState.body: ViewerBody get() = requireNotNull(requireNotNull(entry).body)

    @Test
    fun showsThePhotosAndVideosAsPagesAndEverythingBelowThePager() = runTest {
        h.entries.store(ENTRY, full)
        val (_, screen) = open()
        val entry = requireNotNull(screen.value.entry)
        assertTrue(entry.owner)
        assertFalse(entry.trashed)
        assertFalse(entry.unreadable)
        assertNull(entry.sharedBy)
        assertEquals(
            listOf(
                ViewerPage("p1", MediaKind.PHOTO, 4000, 3000, "Heron at dawn"),
                ViewerPage("m2", MediaKind.VIDEO, 1920, 1080, null),
                ViewerPage("p3", MediaKind.PHOTO, 4000, 3000, null),
            ),
            screen.value.body.pages,
        )
        assertEquals(listOf(VoiceNote("v1", 20_000)), screen.value.body.voiceNotes)
        assertEquals(ViewerTime("2026-11-21T08:00:00.000Z", 60, NOW_UTC, DEVICE_OFFSET), screen.value.body.time)
        assertEquals(BERLIN, screen.value.body.location)
        assertEquals(listOf("beach", "family"), screen.value.body.tags)
        assertEquals("**swim** at the lake", screen.value.body.note)
        assertFalse(screen.value.body.favorite)
        assertEquals(0, screen.value.page)
        assertFalse(screen.value.zoomed)
        assertNull(screen.value.sheet)
    }

    @Test
    fun whileTheIndexBuildsNothingIsShownAndTheViewerStays() = runTest {
        h.entries.store(ENTRY, full)
        h.index.setStatus(IndexStatus.BUILDING)
        val (_, screen) = open()
        assertNull(screen.value.entry)
        assertEquals(emptyList<ViewerEvent>(), screen.events)
        h.index.setStatus(IndexStatus.READY)
        assertEquals(3, screen.value.body.pages.size)
    }

    @Test
    fun anEntryThatNoLongerExistsLeavesWithTheGoneNoticeOnce() = runTest {
        val (_, screen) = open("missing")
        assertEquals(GalleryNotice.Gone, h.notices.value)
        assertEquals(listOf<ViewerEvent>(ViewerEvent.Leave), screen.events)
        h.index.changed()
        assertEquals(listOf<ViewerEvent>(ViewerEvent.Leave), screen.events)
    }

    @Test
    fun anEntryDeletedMeanwhileLeavesWithTheGoneNotice() = runTest {
        h.entries.store(ENTRY, full)
        val (_, screen) = open()
        h.index.drop(ENTRY)
        assertEquals(GalleryNotice.Gone, h.notices.value)
        assertEquals(listOf<ViewerEvent>(ViewerEvent.Leave), screen.events)
    }

    @Test
    fun anUnreadableEntryShowsNoBodyAndIsNotDecrypted() = runTest {
        h.entries.store(ENTRY, full, unreadable = true)
        val (_, screen) = open()
        val entry = requireNotNull(screen.value.entry)
        assertTrue(entry.unreadable)
        assertNull(entry.body)
        assertEquals(emptyList<String>(), h.entries.reads)
    }

    @Test
    fun aMetadataReadFailureShowsNoBody() = runTest {
        h.entries.store(ENTRY, full)
        h.entries.readFailure = IllegalStateException("damaged")
        val (_, screen) = open()
        assertTrue(requireNotNull(screen.value.entry).owner)
        assertNull(screen.value.entry?.body)
    }

    @Test
    fun anEntrySharedWithYouNamesItsOwner() = runTest {
        h.entries.store(ENTRY, full, isOwn = false)
        h.owners[ENTRY] = "bob"
        val (_, screen) = open()
        assertFalse(requireNotNull(screen.value.entry).owner)
        assertEquals("bob", screen.value.entry?.sharedBy)
    }

    @Test
    fun anEntryInTheTrashIsMarkedTrashed() = runTest {
        h.entries.store(ENTRY, full, trashed = true)
        val (_, screen) = open()
        assertTrue(requireNotNull(screen.value.entry).trashed)
    }

    @Test
    fun zoomingIsReportedAndAPageChangeResetsIt() = runTest {
        h.entries.store(ENTRY, full)
        val (vm, screen) = open()
        vm.onAction(ViewerAction.ZoomChanged(true))
        assertTrue(screen.value.zoomed)
        vm.onAction(ViewerAction.ZoomChanged(false))
        assertFalse(screen.value.zoomed)
        vm.onAction(ViewerAction.ZoomChanged(true))
        vm.onAction(ViewerAction.PageChanged(2))
        assertEquals(2, screen.value.page)
        assertFalse(screen.value.zoomed)
    }

    @Test
    fun whenTheItemOrderChangesThePagerStartsOverAtTheFirstPage() = runTest {
        h.entries.store(ENTRY, full)
        val (vm, screen) = open()
        vm.onAction(ViewerAction.PageChanged(2))
        h.entries.replace(ENTRY, full.copy(media = listOf(photo("p3"), photo("p1"), video("m2"))))
        assertEquals(listOf("p3", "p1", "m2"), screen.value.body.pages.map { it.mediaId })
        assertEquals(0, screen.value.page)
    }

    @Test
    fun anEditThatKeepsTheOrderKeepsThePage() = runTest {
        h.entries.store(ENTRY, full)
        val (vm, screen) = open()
        vm.onAction(ViewerAction.PageChanged(1))
        h.entries.replace(ENTRY, full.copy(note = "changed"))
        assertEquals("changed", screen.value.body.note)
        assertEquals(1, screen.value.page)
    }

    @Test
    fun favoriteTogglesThroughTheEntriesService() = runTest {
        h.entries.store(ENTRY, full)
        val (vm, screen) = open()
        vm.onAction(ViewerAction.ToggleFavorite)
        assertEquals(EntriesCall("setFavorite", listOf(ENTRY), true), h.entries.calls.last())
        assertTrue(screen.value.body.favorite)
        vm.onAction(ViewerAction.ToggleFavorite)
        assertEquals(EntriesCall("setFavorite", listOf(ENTRY), false), h.entries.calls.last())
        assertFalse(screen.value.body.favorite)
    }

    @Test
    fun theActionRowAndTheBarNavigate() = runTest {
        h.entries.store(ENTRY, full)
        val (vm, screen) = open()
        vm.onAction(ViewerAction.OpenDetails)
        vm.onAction(ViewerAction.Edit)
        vm.onAction(ViewerAction.OpenPlayer("m2"))
        vm.onAction(ViewerAction.Back)
        assertEquals(
            listOf(
                ViewerEvent.Open(Route.EntryDetails(ENTRY)),
                ViewerEvent.Open(Route.EntryEdit(ENTRY)),
                ViewerEvent.Open(Route.Play(ENTRY, "m2")),
                ViewerEvent.Back,
            ),
            screen.events,
        )
    }

    @Test
    fun theMapActionOpensTheGeoUri() = runTest {
        h.entries.store(ENTRY, full)
        val (vm, screen) = open()
        vm.onAction(ViewerAction.OpenMap)
        assertEquals(listOf<ViewerEvent>(ViewerEvent.OpenUri("geo:52.52,13.405?q=52.52,13.405")), screen.events)
    }

    @Test
    fun aConfirmedNoteLinkIsOpened() = runTest {
        h.entries.store(ENTRY, full)
        val (vm, screen) = open()
        vm.onAction(ViewerAction.OpenLink("https://example.com/a?b=c"))
        assertEquals(listOf<ViewerEvent>(ViewerEvent.OpenUri("https://example.com/a?b=c")), screen.events)
    }

    @Test
    fun aTagChipOpensTheGalleryFilteredToThatTag() = runTest {
        h.entries.store(ENTRY, full)
        val (vm, screen) = open()
        vm.onAction(ViewerAction.ShowTag("family"))
        assertEquals(listOf("family"), h.shownTags)
        assertEquals(listOf<ViewerEvent>(ViewerEvent.Leave), screen.events)
    }

    @Test
    fun theMoreSheetKeepsTheEntryOfflineAndStopsKeepingIt() = runTest {
        h.entries.store(ENTRY, full)
        val (vm, screen) = open()
        assertFalse(screen.value.pinned)
        vm.onAction(ViewerAction.OpenMore)
        assertEquals(ViewerSheet.MORE, screen.value.sheet)
        vm.onAction(ViewerAction.ToggleKeepOffline)
        assertNull(screen.value.sheet)
        assertEquals(listOf(ENTRY to true), h.media.pinCalls)
        assertTrue(screen.value.pinned)
        vm.onAction(ViewerAction.OpenMore)
        vm.onAction(ViewerAction.ToggleKeepOffline)
        assertEquals(listOf(ENTRY to true, ENTRY to false), h.media.pinCalls)
        assertFalse(screen.value.pinned)
    }

    @Test
    fun aPinnedEntryOpensPinned() = runTest {
        h.entries.store(ENTRY, full)
        h.media.pinned += ENTRY
        val (_, screen) = open()
        assertTrue(screen.value.pinned)
    }

    @Test
    fun addMediaClosesTheSheetAndOpensCaptureForThisEntry() = runTest {
        h.entries.store(ENTRY, full)
        val (vm, screen) = open()
        vm.onAction(ViewerAction.OpenMore)
        vm.onAction(ViewerAction.AddMedia)
        assertNull(screen.value.sheet)
        assertEquals(listOf<ViewerEvent>(ViewerEvent.Open(Route.Capture(entryId = ENTRY))), screen.events)
    }

    @Test
    fun moveToTrashTrashesNotifiesTheGalleryAndLeaves() = runTest {
        h.entries.store(ENTRY, full)
        val (vm, screen) = open()
        vm.onAction(ViewerAction.OpenMore)
        vm.onAction(ViewerAction.Trash)
        assertNull(screen.value.sheet)
        assertEquals(listOf(EntriesCall("trash", listOf(ENTRY))), h.entries.calls)
        assertEquals(GalleryNotice.Trashed(ENTRY), h.notices.value)
        assertEquals(listOf<ViewerEvent>(ViewerEvent.Leave), screen.events)
    }

    @Test
    fun deletePermanentlyAsksFirstThenDeletesAndLeaves() = runTest {
        h.entries.store(ENTRY, full, trashed = true)
        val (vm, screen) = open()
        vm.onAction(ViewerAction.AskDeletePermanently)
        assertEquals(ViewerSheet.CONFIRM_DELETE, screen.value.sheet)
        assertEquals(emptyList<EntriesCall>(), h.entries.calls)
        vm.onAction(ViewerAction.ConfirmDeletePermanently)
        assertNull(screen.value.sheet)
        assertEquals(listOf(EntriesCall("deletePermanently", listOf(ENTRY))), h.entries.calls)
        assertEquals(listOf<ViewerEvent>(ViewerEvent.Leave), screen.events)
        assertNull(h.notices.value)
    }

    @Test
    fun cancellingTheDeleteConfirmationChangesNothing() = runTest {
        h.entries.store(ENTRY, full, trashed = true)
        val (vm, screen) = open()
        vm.onAction(ViewerAction.AskDeletePermanently)
        vm.onAction(ViewerAction.DismissSheet)
        assertNull(screen.value.sheet)
        assertEquals(emptyList<EntriesCall>(), h.entries.calls)
        assertEquals(emptyList<ViewerEvent>(), screen.events)
    }

    @Test
    fun restoreTakesTheEntryOutOfTheTrashAndStays() = runTest {
        h.entries.store(ENTRY, full, trashed = true)
        val (vm, screen) = open()
        vm.onAction(ViewerAction.Restore)
        assertEquals(listOf(EntriesCall("restore", listOf(ENTRY))), h.entries.calls)
        assertFalse(requireNotNull(screen.value.entry).trashed)
        assertEquals(emptyList<ViewerEvent>(), screen.events)
    }

    @Test
    fun shareOffersInKodakikoOnlyToTheOwnerWhenTheServerAllowsSharing() = runTest {
        h.entries.store(ENTRY, full)
        val (vm, screen) = open()
        assertTrue(screen.value.sharingAvailable)
        vm.onAction(ViewerAction.Share)
        assertEquals(ViewerEvent.Share(listOf(ENTRY), inKodakiko = true), screen.events.last())
    }

    @Test
    fun withSharingAndLinksOffShareGoesToAppsOnly() = runTest {
        h.config.config = publicConfig(sharing = false, links = false)
        h.entries.store(ENTRY, full)
        val (vm, screen) = open()
        assertFalse(screen.value.sharingAvailable)
        vm.onAction(ViewerAction.Share)
        assertEquals(ViewerEvent.Share(listOf(ENTRY), inKodakiko = false), screen.events.last())
    }

    @Test
    fun publicLinksAloneMakeSharingAvailable() = runTest {
        h.config.config = publicConfig(sharing = false, links = true)
        h.entries.store(ENTRY, full)
        val (_, screen) = open()
        assertTrue(screen.value.sharingAvailable)
    }

    @Test
    fun withoutAServerConfigSharingIsUnavailable() = runTest {
        h.config.config = null
        h.entries.store(ENTRY, full)
        val (_, screen) = open()
        assertFalse(screen.value.sharingAvailable)
    }

    @Test
    fun aRecipientSharesToAppsOnly() = runTest {
        h.entries.store(ENTRY, full, isOwn = false)
        val (vm, screen) = open()
        vm.onAction(ViewerAction.Share)
        assertEquals(ViewerEvent.Share(listOf(ENTRY), inKodakiko = false), screen.events.last())
    }

    @Test
    fun theStateKnowsALocalAccount() = runTest {
        h.entries.store(ENTRY, full)
        val (_, screen) = open()
        assertFalse(screen.value.local)
        h.mode.value = AccountMode.LOCAL
        assertTrue(screen.value.local)
    }

    @Test
    fun voiceNotesPlayAndPauseThroughTheVoicePort() = runTest {
        h.entries.store(ENTRY, full)
        val (vm, screen) = open()
        vm.onAction(ViewerAction.ToggleVoice("v1"))
        assertEquals(listOf(ENTRY to "v1"), h.voice.toggles)
        assertEquals("v1", screen.value.voicePlaying)
        vm.onAction(ViewerAction.ToggleVoice("v1"))
        assertNull(screen.value.voicePlaying)
    }

    @Test
    fun leavingTheScreenStopsTheVoiceNote() = runTest {
        h.entries.store(ENTRY, full)
        val (vm, screen) = open()
        vm.onAction(ViewerAction.ToggleVoice("v1"))
        vm.onAction(ViewerAction.ScreenVisible(false))
        assertEquals(1, h.voice.stops)
        assertNull(screen.value.voicePlaying)
    }

    @Test
    fun clearingTheViewModelStopsTheVoiceNote() = runTest {
        h.entries.store(ENTRY, full)
        val (vm, _) = open()
        vm.onAction(ViewerAction.ToggleVoice("v1"))
        clear(vm)
        assertTrue(h.voice.stops >= 1)
    }

    @Test
    fun theTimeFollowsTheClockAndTheDeviceOffset() = runTest {
        h.clock.nowMs = NOW_MS + 86_400_000L
        h.clock.offset = -300
        h.entries.store(ENTRY, full)
        val (_, screen) = open()
        assertEquals(ViewerTime("2026-11-21T08:00:00.000Z", 60, "2026-11-22T12:00:00.000Z", -300), screen.value.body.time)
    }

    @Test
    fun anEntryWithOnlyAVoiceNoteHasNoPages() = runTest {
        h.entries.store(ENTRY, metadata(media = listOf(voice("v1", durationMs = null))))
        val (_, screen) = open()
        assertEquals(emptyList<ViewerPage>(), screen.value.body.pages)
        assertEquals(listOf(VoiceNote("v1", null)), screen.value.body.voiceNotes)
    }
}
