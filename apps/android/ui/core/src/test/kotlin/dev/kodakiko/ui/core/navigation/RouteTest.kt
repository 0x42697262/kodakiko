package dev.kodakiko.ui.core.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream

class RouteTest {
    private val expectedPaths: List<Pair<Route, String>> = listOf(
        Route.Welcome to "/welcome",
        Route.Invite to "/invite",
        Route.SignUp to "/sign-up",
        Route.SignIn to "/sign-in",
        Route.Securing to "/securing",
        Route.RecoveryKey to "/recovery-key",
        Route.Recover to "/recover",
        Route.CancelDeletion to "/cancel-deletion",
        Route.LocalSetup to "/local-setup",
        Route.Lock to "/lock",
        Route.Gallery to "/",
        Route.Calendar to "/calendar",
        Route.Shared to "/shared",
        Route.More to "/more",
        Route.Trash to "/trash",
        Route.Day("2026-05-01") to "/day/2026-05-01",
        Route.Entry("abc") to "/entry/abc",
        Route.EntryDetails("abc") to "/entry/abc/details",
        Route.EntryEdit("abc") to "/entry/abc/edit",
        Route.EntryNew to "/entry/new",
        Route.Play("abc", "m1") to "/play/abc/m1",
        Route.Capture() to "/capture",
        Route.Tags to "/tags",
        Route.Collection("c1") to "/collections/c1",
        Route.CollectionShare("c1") to "/collections/c1/share",
        Route.LinkNew(emptyList()) to "/links/new",
        Route.Statistics to "/statistics",
        Route.Settings to "/settings",
        Route.SettingsSearch to "/settings/search",
        Route.SettingsAppearance to "/settings/appearance",
        Route.SettingsGallery to "/settings/gallery",
        Route.SettingsCapture to "/settings/capture",
        Route.SettingsSync to "/settings/sync",
        Route.SettingsStorage to "/settings/storage",
        Route.SettingsSecurity to "/settings/security",
        Route.SettingsAccount to "/settings/account",
        Route.SettingsServer to "/settings/server",
        Route.SettingsPassword to "/settings/password",
        Route.SettingsDeleteAccount to "/settings/delete-account",
        Route.SettingsConnect to "/settings/connect",
        Route.SettingsLinks to "/settings/links",
        Route.SettingsAdmin to "/settings/admin",
        Route.SettingsAdvanced to "/settings/advanced",
        Route.SettingsDiagnostics to "/settings/diagnostics",
        Route.SettingsAbout to "/settings/about",
        Route.SettingsExport to "/settings/export",
        Route.SettingsImport to "/settings/import",
    )

    @Test
    fun everyRouteMirrorsItsV031Path() {
        for ((route, path) in expectedPaths) assertEquals(route.toString(), path, route.path)
    }

    @Test
    fun pathsReadBackIntoTheSameRoute() {
        for ((route, path) in expectedPaths) assertEquals(path, route, Route.fromPath(path))
    }

    @Test
    fun homeIsTheStartTabAliasAtTheRootPath() {
        assertEquals("/", Route.Home.path)
        assertEquals(Route.Gallery, Route.fromPath("/"))
    }

    @Test
    fun theFourTabsAreTabRoutesInBottomBarOrder() {
        assertEquals(listOf(Route.Gallery, Route.Calendar, Route.Shared, Route.More), Route.tabs)
        for (tab in Route.tabs) assertTrue(tab.toString(), tab is Route.Tab)
        for ((route, _) in expectedPaths) {
            if (route !in Route.tabs) assertTrue(route.toString(), route !is Route.Tab)
        }
    }

    @Test
    fun entryNewIsNotReadAsAnEntryWithTheIdNew() {
        assertEquals(Route.EntryNew, Route.fromPath("/entry/new"))
        assertEquals(Route.Entry("new-ish"), Route.fromPath("/entry/new-ish"))
    }

    @Test
    fun queryParametersAreNotPartOfThePath() {
        assertEquals("/capture", Route.Capture(entryId = "abc", mode = CaptureMode.VIDEO).path)
        assertEquals(Route.Capture(), Route.fromPath("/capture"))
        assertEquals(CaptureMode.PHOTO, Route.Capture().mode)
        assertEquals("/links/new", Route.LinkNew(listOf("a", "b")).path)
    }

    @Test
    fun unknownOrMalformedPathsReadAsNothing() {
        for (path in listOf("", "calendar", "/nowhere", "/entry", "/entry/", "/entry/a/b/c", "/day", "/play/a", "/s/abc", "/settings/unknown", "/calendar/x")) {
            assertNull(path, Route.fromPath(path))
        }
    }

    @Test
    fun routesSurviveJavaSerializationForSavedNavigatorState() {
        for ((route, _) in expectedPaths + (Route.Capture("abc", CaptureMode.VIDEO) to "") + (Route.LinkNew(listOf("a")) to "")) {
            val bytes = ByteArrayOutputStream().also { out -> ObjectOutputStream(out).use { it.writeObject(route) } }.toByteArray()
            val read = ObjectInputStream(ByteArrayInputStream(bytes)).use { it.readObject() }
            assertEquals(route.toString(), route, read)
        }
    }

    @Test
    fun pathsAreUnique() {
        val paths = expectedPaths.map { it.first.path }
        assertEquals(paths.size, paths.toSet().size)
    }
}
