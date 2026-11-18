package dev.kodakiko.ui.core.format

import android.content.Context
import android.content.res.Resources
import androidx.test.core.app.ApplicationProvider
import dev.kodakiko.ui.core.format.DeviceSettingsMatrix.assertEverywhere
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FormatDayHeaderTest {
    private val resources: Resources = ApplicationProvider.getApplicationContext<Context>().resources

    private fun day(localDate: String, today: String, expected: String) =
        assertEverywhere(expected, "formatDayHeader($localDate, $today)") { formatDayHeader(resources, localDate, today) }

    private fun group(key: String, today: String, expected: String) =
        assertEverywhere(expected, "formatGroupHeader($key, $today)") { formatGroupHeader(resources, key, today) }

    @Test
    fun formatsEveryDaySeenOnAGivenToday() {
        day("2026-11-11", "2026-11-11", "Today")
        day("2026-11-10", "2026-11-11", "Yesterday")
        day("2026-11-09", "2026-11-11", "Monday, Nov 9")
        day("2026-01-01", "2026-11-11", "Thursday, Jan 1")
        day("2026-12-25", "2026-11-11", "Friday, Dec 25")
        day("2024-11-09", "2026-11-11", "Nov 9, 2024")
        day("2024-02-29", "2026-11-11", "Feb 29, 2024")
        day("2025-12-31", "2026-01-01", "Yesterday")
        day("2025-12-30", "2026-01-01", "Dec 30, 2025")
        day("2024-02-29", "2024-03-01", "Yesterday")
    }

    @Test
    fun groupHeadersComputedWithTheV031Function() {
        group("m:2026-11", "2026-11-11", "November 2026")
        group("m:2024-02", "2026-11-11", "February 2024")
        group("y:2024", "2026-11-11", "2024")
        group("t:beach", "2026-11-11", "#beach")
        group("t:a b", "2026-11-11", "#a b")
        group("n:", "2026-11-11", "No tags")
        group("2026-11-11", "2026-11-11", "Today")
        group("2026-11-10", "2026-11-11", "Yesterday")
        group("2026-11-09", "2026-11-11", "Monday, Nov 9")
        group("2024-11-09", "2026-11-11", "Nov 9, 2024")
    }
}
