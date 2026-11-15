package dev.kodakiko.service.index

import dev.kodakiko.crypto.createSodium
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import kotlin.time.Duration.Companion.minutes

class IndexBenchmarkTest {
    private val sodium = createSodium()
    private val now: () -> Long = { System.currentTimeMillis() }
    private val nowIso: () -> String = { Instant.ofEpochMilli(System.currentTimeMillis()).toString() }

    @Test
    fun sealsSyntheticEntriesInMemoryDecryptsThemThroughTheIndexPathAndReportsTimings() = runTest {
        val result = runIndexBenchmark(IndexBenchmarkDeps(sodium = sodium, randomId = randomIdOf(sodium), now = now, nowIso = nowIso, count = 50))
        assertEquals(50, result.entries)
        assertEquals(0, result.unreadable)
        assertTrue(result.buildMs >= 0)
        assertTrue(result.queryMs >= 0)
        assertTrue(result.entriesPerSecond > 0)
        assertTrue(result.withinBudget)
    }

    @Test
    fun recordsTheBuildTimeOfTwentyThousandEntries() = runTest(timeout = 10.minutes) {
        assertEquals(TWENTY_THOUSAND, IndexServiceConstants.INDEX_BENCHMARK_ENTRIES)
        assertEquals(2_000L, IndexServiceConstants.UNLOCK_BUDGET_MS)
        val result = runIndexBenchmark(
            IndexBenchmarkDeps(sodium = sodium, randomId = randomIdOf(sodium), now = now, nowIso = nowIso, count = IndexServiceConstants.INDEX_BENCHMARK_ENTRIES),
        )
        println(
            "Index benchmark: ${result.entries} entries built in ${result.buildMs} ms " +
                "(${result.entriesPerSecond} entries/s), query ${result.queryMs} ms, within the " +
                "${IndexServiceConstants.UNLOCK_BUDGET_MS} ms unlock budget: ${result.withinBudget}",
        )
        assertEquals(TWENTY_THOUSAND, result.entries)
        assertEquals(0, result.unreadable)
        assertTrue(result.buildMs >= 0)
        assertTrue(result.entriesPerSecond > 0)
        assertEquals(result.buildMs <= IndexServiceConstants.UNLOCK_BUDGET_MS, result.withinBudget)
    }

    private companion object {
        const val TWENTY_THOUSAND = 20_000
    }
}
