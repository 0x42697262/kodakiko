package dev.kodakiko.ui.core.format

import android.content.Context
import android.content.res.Resources
import androidx.test.core.app.ApplicationProvider
import dev.kodakiko.ui.core.format.DeviceSettingsMatrix.assertEverywhere
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

private const val NOW_UTC = "2026-11-11T20:00:00.000Z"

@RunWith(RobolectricTestRunner::class)
class FormatEntryTimeTest {
    private val resources: Resources = ApplicationProvider.getApplicationContext<Context>().resources

    private fun shows(name: String, taken: String, offset: Long, now: String, device: Long, expected: String) =
        assertEverywhere(expected, "formatEntryTime shows $name") { formatEntryTime(resources, taken, offset, now, device) }

    @Test
    fun showsDaysRelativeToTheDevice() {
        shows("today", "2026-11-11T14:05:00.000Z", 0, NOW_UTC, 0, "Today, 14:05")
        shows("today at midnight", "2026-11-11T00:00:00.000Z", 0, NOW_UTC, 0, "Today, 00:00")
        shows("yesterday", "2026-11-10T09:30:00.000Z", 0, NOW_UTC, 0, "Yesterday, 09:30")
        shows("earlier this year", "2026-11-09T18:00:00.000Z", 0, NOW_UTC, 0, "Mon, Nov 9, 18:00")
        shows("the first day of this year", "2026-01-01T08:15:00.000Z", 0, NOW_UTC, 0, "Thu, Jan 1, 08:15")
        shows("last year", "2024-11-09T18:00:00.000Z", 0, NOW_UTC, 0, "Nov 9, 2024, 18:00")
        shows("the last day of last year", "2025-12-31T23:59:00.000Z", 0, NOW_UTC, 0, "Dec 31, 2025, 23:59")
        shows("tomorrow", "2026-11-12T10:00:00.000Z", 0, NOW_UTC, 0, "Thu, Nov 12, 10:00")
    }

    @Test
    fun showsEntryAndDeviceOffsets() {
        shows("a +14:00 entry that is already today there", "2026-11-10T20:00:00.000Z", 840, NOW_UTC, 0, "Today, 10:00")
        shows("a -12:00 entry that is still yesterday there", "2026-11-11T08:00:00.000Z", -720, NOW_UTC, 0, "Yesterday, 20:00")
        shows("a device ahead of UTC across midnight", "2026-11-11T12:00:00.000Z", 0, "2026-11-11T23:30:00.000Z", 120, "Yesterday, 12:00")
        shows("a device behind UTC before midnight UTC", "2026-11-10T15:00:00.000Z", 0, "2026-11-11T02:00:00.000Z", -300, "Today, 15:00")
        shows("the entry own offset, not the device offset", "2026-11-11T23:30:00.000Z", 60, NOW_UTC, 0, "Thu, Nov 12, 00:30")
    }

    @Test
    fun showsTheTurnOfTheYear() {
        shows("yesterday across New Year", "2026-12-31T23:00:00.000Z", 0, "2027-01-01T10:00:00.000Z", 0, "Yesterday, 23:00")
        shows("last year across New Year", "2026-12-30T10:00:00.000Z", 0, "2027-01-01T10:00:00.000Z", 0, "Dec 30, 2026, 10:00")
    }
}
