package dev.kodakiko.service.lock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReturnToTest {
    @Test
    fun goesHomeWithoutAReturnPath() {
        assertEquals("/", ReturnTo.nextRouteAfterUnlock(null, false))
    }

    @Test
    fun returnsToARememberedRoute() {
        assertEquals("/calendar", ReturnTo.nextRouteAfterUnlock("/calendar", false))
        assertEquals("/day/2026-05-01", ReturnTo.nextRouteAfterUnlock("/day/2026-05-01", false))
    }

    @Test
    fun returnsToAnEntryOnlyWhenItStillExists() {
        assertEquals("/entry/abc", ReturnTo.nextRouteAfterUnlock("/entry/abc", true))
        assertEquals("/", ReturnTo.nextRouteAfterUnlock("/entry/abc", false))
        assertEquals("/", ReturnTo.nextRouteAfterUnlock("/entry/abc/edit", false))
    }

    @Test
    fun neverReturnsToAnUnprotectedRoute() {
        assertEquals("/", ReturnTo.nextRouteAfterUnlock("/lock", true))
        assertEquals("/", ReturnTo.nextRouteAfterUnlock("/sign-in", true))
    }

    @Test
    fun theseRoutesAreUnprotected() {
        for (path in listOf("/welcome", "/invite", "/sign-in", "/sign-up", "/securing", "/recover", "/lock", "/cancel-deletion", "/s/abc")) {
            assertFalse(path, ReturnTo.isProtectedRoute(path))
        }
    }

    @Test
    fun theseRoutesAreProtected() {
        for (path in listOf("/", "/calendar", "/recovery-key", "/trash", "/day/2026-05-01", "/play/a/b", "/entry/x", "/settings")) {
            assertTrue(path, ReturnTo.isProtectedRoute(path))
        }
    }

    @Test
    fun entryIdOfRouteReadsTheFirstSegmentAfterEntry() {
        assertEquals("abc", ReturnTo.entryIdOfRoute("/entry/abc"))
        assertEquals("abc", ReturnTo.entryIdOfRoute("/entry/abc/edit"))
        assertNull(ReturnTo.entryIdOfRoute("/calendar"))
        assertNull(ReturnTo.entryIdOfRoute("/s/abc"))
    }
}
