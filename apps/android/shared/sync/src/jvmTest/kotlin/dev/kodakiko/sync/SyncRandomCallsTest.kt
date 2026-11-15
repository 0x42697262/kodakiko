package dev.kodakiko.sync

import dev.kodakiko.testing.GoldenReplay
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SyncRandomCallsTest {
    @Test
    fun failuresConsumeExactlyTheRecordedRandomValues() {
        val cases = GoldenReplay.cases("sync/failures.json")
        assertTrue(cases.isNotEmpty())
        for (case in cases) {
            val name = case.getValue("name").jsonPrimitive.content
            val replayed = SyncGoldenAdapter.replay(case.getValue("fn").jsonPrimitive.content, case.getValue("args").jsonObject)
            assertTrue(GoldenReplay.equivalent(case.getValue("result"), replayed.result), name)
            assertEquals(case.getValue("randomCalls").jsonPrimitive.int, replayed.randomCalls, name)
        }
    }
}
