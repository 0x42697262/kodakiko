package dev.kodakiko.data.net

import dev.kodakiko.testing.GoldenReplay
import org.junit.Assert.assertEquals
import org.junit.Test

class ApiCallsGoldenTest {
    @Test
    fun everyCallMatchesTheV031CallTable() = GoldenReplay.runClient("api/calls.json", ApiCallsAdapter)

    @Test
    fun everyV031CallIsPorted() {
        val names = GoldenReplay.clientFunctions("api/calls.json")
        assertEquals(setOf("call"), names)
        assertEquals(ApiCallsAdapter.portedCalls(), GoldenReplay.clientCaseNames("api/calls.json").map { it.substringBefore('#') }.toSet())
    }
}
