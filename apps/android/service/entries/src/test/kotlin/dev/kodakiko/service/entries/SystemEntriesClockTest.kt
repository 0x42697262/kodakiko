package dev.kodakiko.service.entries

import dev.kodakiko.js.JsDate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

const val PARIS_WINTER_OFFSET_MINUTES = -60L
const val PARIS_SUMMER_OFFSET_MINUTES = -120L
const val SUMMER_ISO = "2026-07-01T10:00:00.000Z"

class SystemEntriesClockTest {
    private val original: TimeZone = TimeZone.getDefault()

    @After
    fun restoreTimeZone() {
        TimeZone.setDefault(original)
    }

    @Test
    fun reportsTheJavaScriptTimezoneOffsetOfTheDeviceZoneAtAnInstant() {
        TimeZone.setDefault(TimeZone.getTimeZone(TEST_TIME_ZONE))

        assertEquals(PARIS_WINTER_OFFSET_MINUTES, SystemEntriesClock.timezoneOffsetMinutes(FIRST_NOW_MS))
        assertEquals(PARIS_SUMMER_OFFSET_MINUTES, SystemEntriesClock.timezoneOffsetMinutes(JsDate.parse(SUMMER_ISO).toLong()))
        assertEquals(TEST_TIME_ZONE, SystemEntriesClock.timeZone())
    }

    @Test
    fun readsTheWallClockAsMillisecondsAndAsAnIsoString() {
        val before = System.currentTimeMillis()
        val now = SystemEntriesClock.now()
        val iso = SystemEntriesClock.nowIso()
        val after = System.currentTimeMillis()

        assertTrue(now in before..after)
        assertTrue(Regex("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z$").matches(iso))
        assertTrue(JsDate.parse(iso).toLong() in before..after)
    }
}
