package dev.kodakiko.js

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class JsSemanticsTest {
    @Test
    fun trimUsesTheEcmascriptWhitespaceSet() {
        assertEquals("a\u0085", jsTrim("\uFEFF\u3000a\u0085\u00A0"))
        assertEquals("\u200Bb", jsTrim("\u200Bb\t"))
        assertEquals("a b", collapseJsWhitespace("a\u2028\u2029b", " "))
    }

    @Test
    fun nfkcKeepsLoneSurrogatesAndCountsCodePoints() {
        assertEquals("fi\uD800fi", normalizeNfkc("\uFB01\uD800\uFB01"))
        assertEquals(listOf("a", "\uD83C\uDF0A", "\uDC00"), codePointsOf("a\uD83C\uDF0A\uDC00"))
    }

    @Test
    fun numberOfAStringFollowsJs() {
        assertEquals(0.0, jsStringToNumber(" "))
        assertEquals(2026.0, jsStringToNumber("2026"))
        assertEquals(26.0, jsStringToNumber("0x1A"))
        assertTrue(jsStringToNumber("20-1").isNaN())
        assertEquals(20.0, jsParseInt("20-1"))
        assertEquals("1e+21", jsonStringifyNumber(1e21))
    }

    @Test
    fun dateParseAndIsoStringFollowJs() {
        assertEquals(JsDate.parse("2026-11-11T12:00:00.000Z"), JsDate.parse("2026-11-11T13:00:00+01:00"))
        assertTrue(JsDate.parse("not a date").isNaN())
        assertEquals("2026-11-11T12:00:00.000Z", JsDate.toIsoString(JsDate.parse("2026-11-11T12:00:00Z")))
        assertEquals("Invalid time value", assertFailsWith<RangeError> { JsDate.toIsoString(Double.NaN) }.message)
    }
}
