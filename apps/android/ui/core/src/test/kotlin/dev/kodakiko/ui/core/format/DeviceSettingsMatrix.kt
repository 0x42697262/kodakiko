package dev.kodakiko.ui.core.format

import org.junit.Assert.assertEquals
import java.util.Locale
import java.util.TimeZone

object DeviceSettingsMatrix {
    private val devices: List<Pair<Locale, String>> = listOf(
        Locale.US to "UTC",
        Locale.GERMANY to "Pacific/Kiritimati",
        Locale.forLanguageTag("ar-EG") to "America/Los_Angeles",
        Locale.forLanguageTag("hi-IN") to "Asia/Kolkata",
    )

    fun assertEverywhere(expected: String, label: String, actual: () -> String) {
        val locale = Locale.getDefault()
        val zone = TimeZone.getDefault()
        try {
            for ((deviceLocale, deviceZone) in devices) {
                Locale.setDefault(deviceLocale)
                TimeZone.setDefault(TimeZone.getTimeZone(deviceZone))
                assertEquals("$label on a $deviceLocale device in $deviceZone", expected, actual())
            }
        } finally {
            Locale.setDefault(locale)
            TimeZone.setDefault(zone)
        }
    }
}
