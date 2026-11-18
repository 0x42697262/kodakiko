package dev.kodakiko.app.navigation

import dev.kodakiko.ui.core.navigation.CaptureMode
import dev.kodakiko.ui.core.navigation.Route
import org.junit.Assert.assertEquals
import org.junit.Test

class RouteTransitionsTest {
    @Test
    fun captureSlidesUpThePlayerFadesAndEverythingElseSlides() {
        assertEquals(TransitionKind.SLIDE_UP, RouteTransitions.kindOf(Route.Capture()))
        assertEquals(TransitionKind.SLIDE_UP, RouteTransitions.kindOf(Route.Capture(entryId = "abc", mode = CaptureMode.VIDEO)))
        assertEquals(TransitionKind.FADE, RouteTransitions.kindOf(Route.Play("abc", "m1")))
        for (route in listOf(
            Route.Trash,
            Route.Entry("abc"),
            Route.EntryEdit("abc"),
            Route.EntryNew,
            Route.Day("2026-05-01"),
            Route.Settings,
            Route.SettingsAppearance,
            Route.Tags,
            Route.SignIn,
            Route.Lock,
            Route.Welcome,
        )) {
            assertEquals(route.path, TransitionKind.SLIDE, RouteTransitions.kindOf(route))
        }
    }
}
