package dev.kodakiko.app.navigation

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

    private val notYetBuilt: List<Route> = listOf(
        Route.Home,
        Route.Invite,
        Route.Gallery,
        Route.Calendar,
        Route.Shared,
        Route.More,
        Route.Trash,
        Route.Day("2026-11-21"),
        Route.Entry("entry-1"),
        Route.EntryDetails("entry-1"),
        Route.EntryEdit("entry-1"),
        Route.EntryNew,
        Route.Play("entry-1", "media-1"),
        Route.Capture(),
        Route.Tags,
        Route.Collection("collection-1"),
        Route.CollectionShare("collection-1"),
        Route.LinkNew(listOf("entry-1")),
        Route.Statistics,
        Route.Settings,
        Route.SettingsSearch,
        Route.SettingsAppearance,
        Route.SettingsGallery,
        Route.SettingsCapture,
        Route.SettingsSync,
        Route.SettingsStorage,
        Route.SettingsSecurity,
        Route.SettingsAccount,
        Route.SettingsServer,
        Route.SettingsPassword,
        Route.SettingsDeleteAccount,
        Route.SettingsLinks,
        Route.SettingsAdmin,
        Route.SettingsAdvanced,
        Route.SettingsDiagnostics,
        Route.SettingsAbout,
        Route.SettingsExport,
        Route.SettingsImport,
    )

    @Test
    fun everyAuthAndLockRouteMapsToItsScreen() {
        for ((route, screen) in authAndLock) assertEquals(route.path, screen, AppScreens.of(route))
    }

    @Test
    fun everyOtherRouteMapsToThePlaceholderUntilItsTask() {
        for (route in notYetBuilt) assertEquals(route.path, AppScreen.PLACEHOLDER, AppScreens.of(route))
    }

    @Test
    fun theTableCoversEveryScreenOnce() {
        assertEquals(AppScreen.entries.toSet() - AppScreen.PLACEHOLDER, authAndLock.values.toSet())
        assertEquals(authAndLock.size, authAndLock.values.toSet().size)
    }

    @Test
    fun theTableListsEveryKindOfRoute() {
        val declared = Route::class.java.declaredClasses
            .filter { Route::class.java.isAssignableFrom(it) && !it.isInterface }
            .map { it.name }
            .toSet()
        val listed = (authAndLock.keys + notYetBuilt).map { it.javaClass.name }.toSet()
        assertEquals(declared, listed)
    }
}
