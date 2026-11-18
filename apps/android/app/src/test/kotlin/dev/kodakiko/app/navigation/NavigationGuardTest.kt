package dev.kodakiko.app.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationGuardTest {
    @Test
    fun theLockWindowIs600Milliseconds() {
        assertEquals(600L, NavigationConstants.NAVIGATION_LOCK_MS)
    }

    @Test
    fun letsTheFirstPushThroughAndDropsRepeatsToTheSamePageInsideTheLockWindow() {
        var now = 1_000L
        val guard = NavigationGuard { now }
        assertTrue(guard.allow("/tags"))
        repeat(9) {
            now += 30
            assertFalse(guard.allow("/tags"))
        }
    }

    @Test
    fun letsADifferentPageThroughAtOnce() {
        var now = 1_000L
        val guard = NavigationGuard { now }
        guard.allow("/settings/storage")
        now += 30
        assertTrue(guard.allow("/settings/export"))
    }

    @Test
    fun allowsTheSamePageAgainOnceTheWindowHasPassed() {
        var now = 1_000L
        val guard = NavigationGuard { now }
        guard.allow("/tags")
        now += NavigationConstants.NAVIGATION_LOCK_MS
        assertTrue(guard.allow("/tags"))
    }

    @Test
    fun theWindowCountsFromTheLastAllowedPushNotFromDroppedOnes() {
        var now = 1_000L
        val guard = NavigationGuard { now }
        assertTrue(guard.allow("/tags"))
        now += 599
        assertFalse(guard.allow("/tags"))
        now += 1
        assertTrue(guard.allow("/tags"))
    }

    @Test
    fun onlyTheLastAllowedPageIsLocked() {
        var now = 1_000L
        val guard = NavigationGuard { now }
        assertTrue(guard.allow("/tags"))
        now += 30
        assertTrue(guard.allow("/trash"))
        now += 30
        assertTrue(guard.allow("/tags"))
        now += 30
        assertFalse(guard.allow("/tags"))
    }

    @Test
    fun keysCompareByValue() {
        var now = 1_000L
        val guard = NavigationGuard { now }
        assertTrue(guard.allow(listOf("/entry", "a")))
        now += 30
        assertFalse(guard.allow(listOf("/entry", "a")))
        assertTrue(guard.allow(listOf("/entry", "b")))
    }
}
