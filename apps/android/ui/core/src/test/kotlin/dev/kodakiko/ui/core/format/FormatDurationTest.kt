package dev.kodakiko.ui.core.format

import dev.kodakiko.ui.core.format.DeviceSettingsMatrix.assertEverywhere
import org.junit.Test

class FormatDurationTest {
    private fun check(milliseconds: Long, text: String) = assertEverywhere(text, "formatDuration($milliseconds)") { formatDuration(milliseconds) }

    @Test
    fun formatsTheTsCases() {
        check(0, "00:00")
        check(999, "00:00")
        check(1000, "00:01")
        check(59_999, "00:59")
        check(60_000, "01:00")
        check(605_000, "10:05")
        check(-5, "00:00")
    }

    @Test
    fun edgeCasesComputedWithTheV031Function() {
        check(20_500, "00:20")
        check(65_000, "01:05")
        check(3_600_000, "60:00")
        check(6_000_000, "100:00")
    }
}
