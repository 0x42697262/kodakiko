package dev.kodakiko.js

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class JsFixedAndSliceTest {
    @Test
    fun toFixedRoundsTheExactBinaryValueHalfUp() {
        assertEquals("1.00001", jsToFixed(1.000005, 5))
        assertEquals("0.00000", jsToFixed(0.0000025, 5))
        assertEquals("180.00000", jsToFixed(179.999995, 5))
        assertEquals("1.00", jsToFixed(1.005, 2))
        assertEquals("1.4", jsToFixed(1.45, 1))
        assertEquals("1", jsToFixed(0.5, 0))
        assertEquals("3", jsToFixed(2.5, 0))
        assertEquals("-2", jsToFixed(-1.5, 0))
        assertEquals("37.38610", jsToFixed(37.3861, 5))
        assertEquals("-122.08390", jsToFixed(-122.0839, 5))
        assertEquals("0.00", jsToFixed(Double.MIN_VALUE, 2))
        assertEquals("1234567890123456768.0", jsToFixed(1234567890123456789.0, 1))
        assertEquals("0.1000000000000000055511151231257827", jsToFixed(0.1, 34))
    }

    @Test
    fun toFixedKeepsTheSignOfTinyNegativesButNotOfNegativeZero() {
        assertEquals("-0.00000", jsToFixed(-0.000001, 5))
        assertEquals("0.00000", jsToFixed(0.0, 5))
        assertEquals("0.00000", jsToFixed(-0.0, 5))
    }

    @Test
    fun toFixedFallsBackToToStringForLargeAndNonFiniteValues() {
        assertEquals("1e+21", jsToFixed(1e21, 2))
        assertEquals("-1e+21", jsToFixed(-1e21, 2))
        assertEquals("NaN", jsToFixed(Double.NaN, 5))
        assertEquals("Infinity", jsToFixed(Double.POSITIVE_INFINITY, 5))
        assertEquals("-Infinity", jsToFixed(Double.NEGATIVE_INFINITY, 5))
        assertFailsWith<RangeError> { jsToFixed(1.0, 101) }
    }

    @Test
    fun sliceClampsLikeJs() {
        assertEquals("", jsSlice("---\n---\n", 4, 3))
        assertEquals("cd", jsSlice("abcd", 2, 10))
        assertEquals("c", jsSlice("abcd", -2, -1))
        assertEquals("abcd", jsSlice("abcd", -10))
        assertEquals("", jsSlice("abcd", 5))
    }
}
