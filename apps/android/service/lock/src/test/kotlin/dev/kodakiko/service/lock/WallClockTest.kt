package dev.kodakiko.service.lock

import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class WallClockTest {
    private val toleranceMs = 1_000L
    private val jsIsoPattern = Regex("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z$")

    @Test
    fun returnsEpochMillisecondsAndAMatchingIsoString() {
        val before = System.currentTimeMillis()
        val now = SystemWallClock.now()
        assertTrue(now >= before)
        assertTrue(Instant.parse(SystemWallClock.nowIso()).toEpochMilli() >= now - toleranceMs)
        val iso = SystemWallClock.nowIso()
        assertTrue(iso, jsIsoPattern.matches(iso))
    }
}
