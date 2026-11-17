package dev.kodakiko.app.navigation

import dev.kodakiko.ui.core.navigation.CaptureMode
import dev.kodakiko.ui.core.navigation.Route
import org.junit.Assert.assertEquals
import org.junit.Test

class AppScreensTest {
    private val authAndLock: Map<Route, AppScreen> = mapOf(
        Route.Welcome to AppScreen.WELCOME,
        Route.SignUp to AppScreen.SIGN_UP,
        Route.Securing to AppScreen.SECURING,
        Route.RecoveryKey to AppScreen.RECOVERY_KEY,
        Route.SignIn to AppScreen.SIGN_IN,
        Route.Recover to AppScreen.RECOVER,
        Route.CancelDeletion to AppScreen.CANCEL_DELETION,
        Route.LocalSetup to AppScreen.LOCAL_SETUP,
        Route.SettingsConnect to AppScreen.CONNECT_SERVER,
        Route.Lock to AppScreen.LOCK,
    )

    private val gallery: Map<Route, AppScreen> = mapOf(
        Route.Gallery to AppScreen.GALLERY,
        Route.Trash to AppScreen.TRASH,
        Route.Day("2026-11-21") to AppScreen.DAY,
        Route.Day("2024-02-29") to AppScreen.DAY,
    )

    private val calendarAndViewer: Map<Route, AppScreen> = mapOf(
        Route.Calendar to AppScreen.CALENDAR,
        Route.Entry("entry-1") to AppScreen.ENTRY,
        Route.Entry("entry-2") to AppScreen.ENTRY,
        Route.EntryDetails("entry-1") to AppScreen.ENTRY_DETAILS,
        Route.Play("entry-1", "media-1") to AppScreen.PLAY,
    )

    private val edit: Map<Route, AppScreen> = mapOf(
        Route.EntryEdit("entry-1") to AppScreen.ENTRY_EDIT,
        Route.EntryEdit("entry-1", isNew = true) to AppScreen.ENTRY_EDIT,
        Route.EntryNew to AppScreen.ENTRY_NEW,
        Route.EntryCrop("entry-1", "media-1") to AppScreen.ENTRY_CROP,
    )

    private val captureAndTags: Map<Route, AppScreen> = mapOf(
        Route.Capture() to AppScreen.CAPTURE,
        Route.Capture("entry-1") to AppScreen.CAPTURE,
        Route.Capture("entry-1", CaptureMode.VIDEO) to AppScreen.CAPTURE,
        Route.Tags to AppScreen.TAGS,
    )

    private val sharingAndLinks: Map<Route, AppScreen> = mapOf(
        Route.Shared to AppScreen.SHARED,
        Route.Collection("collection-1") to AppScreen.COLLECTION,
        Route.Collection("collection-2") to AppScreen.COLLECTION,
        Route.CollectionShare("collection-1") to AppScreen.COLLECTION_SHARE,
        Route.ShareSheet(listOf("entry-1"), true) to AppScreen.SHARE_SHEET,
        Route.ShareSheet(listOf("entry-1", "entry-2"), false) to AppScreen.SHARE_SHEET,
        Route.LinkNew(listOf("entry-1")) to AppScreen.LINK_NEW,
        Route.LinkNew(listOf("entry-1", "entry-2")) to AppScreen.LINK_NEW,
        Route.SettingsLinks to AppScreen.SETTINGS_LINKS,
    )

    private val settings: Map<Route, AppScreen> = mapOf(
        Route.Settings to AppScreen.SETTINGS,
        Route.SettingsSearch to AppScreen.SETTINGS_SEARCH,
        Route.SettingsAppearance to AppScreen.SETTINGS_APPEARANCE,
        Route.SettingsGallery to AppScreen.SETTINGS_GALLERY,
        Route.SettingsCapture to AppScreen.SETTINGS_CAPTURE,
        Route.SettingsSync to AppScreen.SETTINGS_SYNC,
        Route.SettingsStorage to AppScreen.SETTINGS_STORAGE,
        Route.SettingsSecurity to AppScreen.SETTINGS_SECURITY,
        Route.SettingsExport to AppScreen.SETTINGS_EXPORT,
        Route.SettingsImport to AppScreen.SETTINGS_IMPORT,
    )

    private val notYetBuilt: List<Route> = listOf(
        Route.Home,
        Route.Invite,
        Route.More,
        Route.Statistics,
        Route.SettingsAccount,
        Route.SettingsServer,
        Route.SettingsPassword,
        Route.SettingsDeleteAccount,
        Route.SettingsAdmin,
        Route.SettingsAdvanced,
        Route.SettingsDiagnostics,
        Route.SettingsAbout,
    )

    @Test
    fun everyAuthAndLockRouteMapsToItsScreen() {
        for ((route, screen) in authAndLock) assertEquals(route.path, screen, AppScreens.of(route))
    }

    @Test
    fun theGalleryTrashAndDayRoutesMapToTheGalleryScreens() {
        for ((route, screen) in gallery) assertEquals(route.path, screen, AppScreens.of(route))
    }

    @Test
    fun theCalendarViewerDetailsAndPlayerRoutesMapToTheirScreens() {
        for ((route, screen) in calendarAndViewer) assertEquals(route.path, screen, AppScreens.of(route))
    }

    @Test
    fun theEditNewEntryAndCropRoutesMapToTheEditScreens() {
        for ((route, screen) in edit) assertEquals(route.path, screen, AppScreens.of(route))
    }

    @Test
    fun theCaptureAndTagRoutesMapToTheirScreens() {
        for ((route, screen) in captureAndTags) assertEquals(route.path, screen, AppScreens.of(route))
    }

    @Test
    fun theSharedTabJournalShareWithShareSheetLinkAndLinksListRoutesMapToTheirScreens() {
        for ((route, screen) in sharingAndLinks) assertEquals(route.path, screen, AppScreens.of(route))
    }

    @Test
    fun theSettingsRootSearchAndTheFirstSettingsScreensWithBackupMapToTheirScreens() {
        for ((route, screen) in settings) assertEquals(route.path, screen, AppScreens.of(route))
    }

    @Test
    fun theShareSheetRouteCarriesTheEntriesAndWhetherInKodakikoIsAllowed() {
        val route = Route.ShareSheet(listOf("entry-1", "entry-2"), false)
        assertEquals(listOf("entry-1", "entry-2"), route.entryIds)
        assertEquals(false, route.inKodakiko)
        assertEquals(true, Route.ShareSheet(listOf("entry-1"), true).inKodakiko)
        assertEquals(Route.ShareSheet(listOf("entry-1"), true), Route.ShareSheet(listOf("entry-1"), true))
        assertEquals(listOf("entry-1", "entry-2"), Route.LinkNew(listOf("entry-1", "entry-2")).entryIds)
        assertEquals("collection-1", Route.CollectionShare("collection-1").collectionId)
    }

    @Test
    fun aCaptureRouteCarriesTheEntryAndTheMode() {
        assertEquals(null, Route.Capture().entryId)
        assertEquals(CaptureMode.PHOTO, Route.Capture().mode)
        assertEquals("entry-1", Route.Capture("entry-1", CaptureMode.VIDEO).entryId)
        assertEquals(CaptureMode.VIDEO, Route.Capture("entry-1", CaptureMode.VIDEO).mode)
    }

    @Test
    fun aNewEntryRouteRemembersItIsNewAndTheCropRouteNamesItsItem() {
        assertEquals(false, Route.EntryEdit("entry-1").isNew)
        assertEquals(true, Route.EntryEdit("entry-1", isNew = true).isNew)
        assertEquals("media-1", Route.EntryCrop("entry-1", "media-1").mediaId)
        assertEquals("entry-1", Route.EntryCrop("entry-1", "media-1").entryId)
    }

    @Test
    fun everyOtherRouteMapsToThePlaceholderUntilItsTask() {
        for (route in notYetBuilt) assertEquals(route.path, AppScreen.PLACEHOLDER, AppScreens.of(route))
    }

    @Test
    fun theTableCoversEveryScreenOnce() {
        assertEquals(
            AppScreen.entries.toSet() - AppScreen.PLACEHOLDER,
            authAndLock.values.toSet() + gallery.values.toSet() + calendarAndViewer.values.toSet() + edit.values.toSet() + captureAndTags.values.toSet() +
                sharingAndLinks.values.toSet() + settings.values.toSet(),
        )
        assertEquals(authAndLock.size, authAndLock.values.toSet().size)
        assertEquals(emptySet<AppScreen>(), authAndLock.values.toSet() intersect gallery.values.toSet())
        assertEquals(setOf(AppScreen.GALLERY, AppScreen.TRASH, AppScreen.DAY), gallery.values.toSet())
        assertEquals(setOf(AppScreen.CALENDAR, AppScreen.ENTRY, AppScreen.ENTRY_DETAILS, AppScreen.PLAY), calendarAndViewer.values.toSet())
        assertEquals(emptySet<AppScreen>(), (authAndLock.values.toSet() + gallery.values.toSet()) intersect calendarAndViewer.values.toSet())
        assertEquals(setOf(AppScreen.ENTRY_EDIT, AppScreen.ENTRY_NEW, AppScreen.ENTRY_CROP), edit.values.toSet())
        assertEquals(emptySet<AppScreen>(), (authAndLock.values.toSet() + gallery.values.toSet() + calendarAndViewer.values.toSet()) intersect edit.values.toSet())
        assertEquals(setOf(AppScreen.CAPTURE, AppScreen.TAGS), captureAndTags.values.toSet())
        assertEquals(
            emptySet<AppScreen>(),
            (authAndLock.values.toSet() + gallery.values.toSet() + calendarAndViewer.values.toSet() + edit.values.toSet()) intersect captureAndTags.values.toSet(),
        )
        assertEquals(
            setOf(AppScreen.SHARED, AppScreen.COLLECTION, AppScreen.COLLECTION_SHARE, AppScreen.SHARE_SHEET, AppScreen.LINK_NEW, AppScreen.SETTINGS_LINKS),
            sharingAndLinks.values.toSet(),
        )
        assertEquals(
            emptySet<AppScreen>(),
            (authAndLock.values.toSet() + gallery.values.toSet() + calendarAndViewer.values.toSet() + edit.values.toSet() + captureAndTags.values.toSet()) intersect
                sharingAndLinks.values.toSet(),
        )
        assertEquals(settings.size, settings.values.toSet().size)
        assertEquals(
            emptySet<AppScreen>(),
            (
                authAndLock.values.toSet() + gallery.values.toSet() + calendarAndViewer.values.toSet() + edit.values.toSet() + captureAndTags.values.toSet() +
                    sharingAndLinks.values.toSet()
                ) intersect settings.values.toSet(),
        )
    }

    @Test
    fun theTableListsEveryKindOfRoute() {
        val declared = Route::class.java.declaredClasses
            .filter { Route::class.java.isAssignableFrom(it) && !it.isInterface }
            .map { it.name }
            .toSet()
        val listed = (authAndLock.keys + gallery.keys + calendarAndViewer.keys + edit.keys + captureAndTags.keys + sharingAndLinks.keys + settings.keys + notYetBuilt)
            .map { it.javaClass.name }
            .toSet()
        assertEquals(declared, listed)
    }
}
