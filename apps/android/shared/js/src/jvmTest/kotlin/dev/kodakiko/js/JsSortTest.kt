package dev.kodakiko.js

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JsSortTest {
    private val size = 4000
    private val seeds = 1..40

    private fun inconsistent(seed: Int): (Int, Int) -> Int {
        val random = Random(seed)
        return { _, _ -> random.nextInt(-1, 2) }
    }

    @Test
    fun neverThrowsOnAnInconsistentComparatorWhileSortedWithDoes() {
        val items = (0 until size).toList()
        var sortedWithFailures = 0
        for (seed in seeds) {
            val sorted = jsStableSort(items, inconsistent(seed))
            assertEquals(items, sorted.sorted())
            val compare = inconsistent(seed)
            val thrown = runCatching { items.sortedWith(compare) }.exceptionOrNull()
            if (thrown != null) {
                assertTrue(thrown is IllegalArgumentException, "unexpected $thrown")
                sortedWithFailures += 1
            }
        }
        assertTrue(sortedWithFailures > 0, "sortedWith never threw on $seeds")
    }

    @Test
    fun neverThrowsWhenNaNDifferencesCompareAsEqual() {
        val random = Random(seeds.first)
        val times = List(size) { if (random.nextInt(4) == 0) Double.NaN else random.nextInt(50).toDouble() }
        val ids = times.indices.map { "id%05d".format(it) }.shuffled(random)
        val order: (String, String) -> Int = { left, right ->
            val difference = times[right.drop(2).toInt()] - times[left.drop(2).toInt()]
            when {
                !difference.isNaN() && difference != 0.0 -> if (difference > 0) 1 else -1
                left == right -> 0
                left < right -> -1
                else -> 1
            }
        }
        assertEquals(ids.sorted(), jsStableSort(ids, order).sorted())
    }

    @Test
    fun matchesTheStableSortForConsistentComparators() {
        for (seed in seeds) {
            val random = Random(seed)
            val items = List(random.nextInt(1, size)) { it to random.nextInt(20) }
            val byKey: (Pair<Int, Int>, Pair<Int, Int>) -> Int = { left, right -> left.second - right.second }
            assertEquals(items.sortedWith(byKey), jsStableSort(items, byKey))
            assertEquals(items.sortedWith(byKey).reversed().map { it.second }, jsStableSort(items) { left, right -> byKey(right, left) }.map { it.second })
        }
    }

    @Test
    fun handlesEmptyAndSingleInputs() {
        assertEquals(emptyList(), jsStableSort(emptyList<Int>()) { left, right -> left - right })
        assertEquals(listOf(1), jsStableSort(listOf(1)) { _, _ -> 1 })
    }
}
