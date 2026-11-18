package dev.kodakiko.app.navigation

import dev.kodakiko.data.db.StartScreen
import dev.kodakiko.service.account.AccountState
import dev.kodakiko.service.lock.ReturnTo
import dev.kodakiko.ui.core.navigation.Route
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteGuardsTest {
    private val unprotected: List<Route> = listOf(
        Route.Welcome,
        Route.Invite,
        Route.SignIn,
        Route.SignUp,
        Route.Securing,
        Route.Recover,
        Route.Lock,
        Route.CancelDeletion,
    )

    private val openBeforeUnlock: List<Route> = unprotected + Route.LocalSetup

    private val needUnlock: List<Route> = listOf(
        Route.Home,
        Route.Gallery,
        Route.Calendar,
        Route.Shared,
        Route.More,
        Route.RecoveryKey,
        Route.Trash,
        Route.Day("2026-05-01"),
        Route.Entry("abc"),
        Route.EntryDetails("abc"),
        Route.EntryEdit("abc"),
        Route.EntryNew,
        Route.Play("abc", "m1"),
        Route.Capture(),
        Route.Tags,
        Route.Collection("c1"),
        Route.CollectionShare("c1"),
        Route.LinkNew(listOf("abc")),
        Route.ShareSheet(listOf("abc"), true),
        Route.ShareSheet(listOf("abc"), false),
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
        Route.SettingsConnect,
        Route.SettingsLinks,
        Route.SettingsAdmin,
        Route.SettingsAdvanced,
        Route.SettingsDiagnostics,
        Route.SettingsAbout,
        Route.SettingsExport,
        Route.SettingsImport,
    )

    @Test
    fun eachAccountStateHasItsRoute() {
        assertEquals(Route.Welcome, RouteGuards.routeForState(AccountState.NoServer, StartScreen.GALLERY))
        assertEquals(Route.Welcome, RouteGuards.routeForState(AccountState.SignedOut(), StartScreen.GALLERY))
        assertEquals(Route.Welcome, RouteGuards.routeForState(AccountState.SignedOut("alice"), StartScreen.GALLERY))
        assertEquals(Route.Lock, RouteGuards.routeForState(AccountState.Locked, StartScreen.GALLERY))
        assertEquals(Route.CancelDeletion, RouteGuards.routeForState(AccountState.PendingDeletion, StartScreen.GALLERY))
        assertEquals(Route.Gallery, RouteGuards.routeForState(unlockedState(), StartScreen.GALLERY))
    }

    @Test
    fun theUnlockedRouteIsTheStartScreenTab() {
        assertEquals(Route.Gallery, RouteGuards.startRoute(StartScreen.GALLERY))
        assertEquals(Route.Calendar, RouteGuards.startRoute(StartScreen.CALENDAR))
        assertEquals(Route.Calendar, RouteGuards.routeForState(unlockedState(), StartScreen.CALENDAR))
        assertEquals(Route.Lock, RouteGuards.routeForState(AccountState.Locked, StartScreen.CALENDAR))
    }

    @Test
    fun theUnprotectedRoutesOfReturnToAndLocalSetupAreOpenBeforeUnlock() {
        for (route in openBeforeUnlock) assertFalse(route.path, RouteGuards.requiresUnlock(route))
        for (route in unprotected) assertFalse(route.path, ReturnTo.isProtectedRoute(route.path))
    }

    @Test
    fun everyOtherRouteNeedsAnUnlockedAccountAndIsProtectedForReturnTo() {
        for (route in needUnlock) {
            assertTrue(route.path, RouteGuards.requiresUnlock(route))
            assertTrue(route.path, ReturnTo.isProtectedRoute(route.path))
        }
    }

    @Test
    fun aRouteThatNeedsUnlockRedirectsToTheRouteOfTheStateOtherwiseStays() {
        assertEquals(Route.Lock, RouteGuards.redirect(Route.Trash, AccountState.Locked, StartScreen.GALLERY))
        assertEquals(Route.Welcome, RouteGuards.redirect(Route.Gallery, AccountState.SignedOut(), StartScreen.GALLERY))
        assertEquals(Route.Welcome, RouteGuards.redirect(Route.SettingsAppearance, AccountState.NoServer, StartScreen.GALLERY))
        assertEquals(Route.CancelDeletion, RouteGuards.redirect(Route.Entry("abc"), AccountState.PendingDeletion, StartScreen.GALLERY))
        assertNull(RouteGuards.redirect(Route.Trash, unlockedState(), StartScreen.GALLERY))
        assertNull(RouteGuards.redirect(Route.SignIn, AccountState.SignedOut(), StartScreen.GALLERY))
        assertNull(RouteGuards.redirect(Route.LocalSetup, AccountState.NoServer, StartScreen.GALLERY))
        assertNull(RouteGuards.redirect(Route.Recover, AccountState.Locked, StartScreen.GALLERY))
    }

    @Test
    fun theHomeAliasResolvesToTheStartTab() {
        assertEquals(Route.Gallery, RouteGuards.resolve(Route.Home, StartScreen.GALLERY))
        assertEquals(Route.Calendar, RouteGuards.resolve(Route.Home, StartScreen.CALENDAR))
        assertEquals(Route.Trash, RouteGuards.resolve(Route.Trash, StartScreen.CALENDAR))
        assertEquals(Route.More, RouteGuards.resolve(Route.More, StartScreen.CALENDAR))
    }
}
