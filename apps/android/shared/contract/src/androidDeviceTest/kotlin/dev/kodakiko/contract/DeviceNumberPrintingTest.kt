package dev.kodakiko.contract

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeviceNumberPrintingTest {
    private val javaScriptOutput = listOf(
        0.1 to "0.1",
        1e21 to "1e+21",
        1e20 to "100000000000000000000",
        1.5e-7 to "1.5e-7",
        1e-7 to "1e-7",
        0.000001 to "0.000001",
        123456789.123 to "123456789.123",
        52.37403 to "52.37403",
        4.88969 to "4.88969",
        -0.0 to "0",
        5e-324 to "5e-324",
        1.7976931348623157e308 to "1.7976931348623157e+308",
        1.23e-18 to "1.23e-18",
        100.0 to "100",
        2.5 to "2.5",
        0.30000000000000004 to "0.30000000000000004",
        -48.85840 to "-48.8584",
    )

    @Test
    fun doublesPrintLikeJavaScriptOnTheDevice() {
        for ((value, expected) in javaScriptOutput) {
            assertEquals("$value", expected, ContractJson.stringify(JsonPrimitive(value)))
        }
    }
}
