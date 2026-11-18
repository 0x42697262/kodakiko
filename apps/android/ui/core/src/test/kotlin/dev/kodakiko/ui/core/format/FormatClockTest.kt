package dev.kodakiko.ui.core.format

import dev.kodakiko.data.db.TimeFormat
import dev.kodakiko.ui.core.format.DeviceSettingsMatrix.assertEverywhere
import org.junit.Test

class FormatClockTest {
    private fun check(time: String, format: TimeFormat, text: String) =
        assertEverywhere(text, "formatClock($time, $format)") { formatClock(time, format) }

    @Test
    fun keeps24HourTimesAndConvertsTo12HourWithAmAndPm() {
        check("17:40", TimeFormat.HOURS_24, "17:40")
        check("17:40", TimeFormat.HOURS_12, "5:40 PM")
        check("00:05", TimeFormat.HOURS_12, "12:05 AM")
        check("12:00", TimeFormat.HOURS_12, "12:00 PM")
    }

    @Test
    fun edgeCasesComputedWithTheV031Function() {
        check("00:00", TimeFormat.HOURS_12, "12:00 AM")
        check("11:59", TimeFormat.HOURS_12, "11:59 AM")
        check("12:59", TimeFormat.HOURS_12, "12:59 PM")
        check("13:00", TimeFormat.HOURS_12, "1:00 PM")
        check("23:59", TimeFormat.HOURS_12, "11:59 PM")
        check("09:05", TimeFormat.HOURS_12, "9:05 AM")
        check("00:00", TimeFormat.HOURS_24, "00:00")
        check("09:05", TimeFormat.HOURS_24, "09:05")
        check("23:59", TimeFormat.HOURS_24, "23:59")
    }
}
