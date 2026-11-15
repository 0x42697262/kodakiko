package dev.kodakiko.testing

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GoldenReplayTest {
    private fun json(text: String) = Json.parseToJsonElement(text)

    @Test
    fun numbersCompareByValueAndStringsByContent() {
        assertTrue(GoldenReplay.equivalent(json("5"), json("5.0")))
        assertTrue(GoldenReplay.equivalent(json("{\"a\":[1,\"x\",null]}"), json("{\"a\":[1.0,\"x\",null]}")))
        assertFalse(GoldenReplay.equivalent(json("\"5\""), json("5")))
        assertFalse(GoldenReplay.equivalent(json("{\"a\":1}"), json("{\"a\":1,\"b\":null}")))
        assertFalse(GoldenReplay.equivalent(json("[1,2]"), json("[2,1]")))
        assertFalse(GoldenReplay.equivalent(json("null"), json("0")))
    }

    @Test
    fun aWrongResultOrAnUnsupportedFunctionFails() {
        val path = "domain/tags.json"
        assertFailsWith<GoldenFailure> { GoldenReplay.run(path) { _, _ -> JsonPrimitive("wrong") } }
        assertFailsWith<GoldenFailure> { GoldenReplay.run(path) { fn, _ -> throw UnsupportedGoldenFunction(fn) } }
    }

    @Test
    fun errorsAreEncodedLikeTheTypeScriptGoldens() {
        class InvalidEditError(message: String) : Exception(message)
        assertTrue(GoldenReplay.equivalent(json("{\"error\":\"InvalidEditError\",\"message\":\"m\"}"), GoldenReplay.error(InvalidEditError("m"))))
    }
}
