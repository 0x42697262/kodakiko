package dev.kodakiko.ui.feature.gallery

import dev.kodakiko.index.GalleryFilter
import dev.kodakiko.index.GalleryScope
import dev.kodakiko.index.GalleryView
import dev.kodakiko.index.TagCount
import dev.kodakiko.index.orderTags
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GalleryModelTest {
    private val empty = GalleryFilters.EMPTY

    @Test
    fun theEmptyFilterIsTheHomeJournalWithNothingNarrowed() {
        assertEquals(GalleryFilter(GalleryScope.Home, GalleryView.JOURNAL, emptyList(), null, null, ""), empty)
    }

    @Test
    fun detectsActiveFilters() {
        assertFalse(GalleryFilters.isActive(empty))
        assertTrue(GalleryFilters.isActive(empty.copy(tags = listOf("a"))))
        assertTrue(GalleryFilters.isActive(empty.copy(fromDate = "2026-01-01")))
        assertTrue(GalleryFilters.isActive(empty.copy(toDate = "2026-01-01")))
        assertTrue(GalleryFilters.isActive(empty.copy(text = "lake")))
        assertFalse(GalleryFilters.isActive(empty.copy(text = "   ")))
    }

    @Test
    fun validatesTheDateRangeLikeV031() {
        val cases = listOf(
            Triple("", "", null),
            Triple("2026-01-01", "", null),
            Triple("", "2026-01-01", null),
            Triple("2026-01-01", "2026-01-01", null),
            Triple("2026-01-01", "2026-02-01", null),
            Triple("2026-02-01", "2026-01-01", DateRangeProblem.START_AFTER_END),
            Triple("2026-02-30", "", DateRangeProblem.INVALID_DATE),
            Triple("", "26-01-01", DateRangeProblem.INVALID_DATE),
            Triple("2026-1-1", "", DateRangeProblem.INVALID_DATE),
        )
        for ((from, to, expected) in cases) assertEquals("$from..$to", expected, GalleryFilters.validateDateRange(from, to))
    }

    @Test
    fun theFilterStoreKeepsTheFilterNotifiesAndResets() = runTest {
        val store = GalleryFilterStore()
        val seen = mutableListOf<Int>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { store.filter.collect { seen += it.tags.size } }
        assertEquals(empty, store.filter.value)
        store.set(store.filter.value.copy(tags = listOf("a", "b")))
        store.reset()
        job.cancel()
        store.set(store.filter.value.copy(tags = listOf("c")))
        assertEquals(listOf(0, 2, 0), seen)
        assertEquals(listOf("c"), store.filter.value.tags)
    }

    @Test
    fun hidesBarsWhenScrollingDownShowsThemScrollingUpOrNearTheTopAndIgnoresSmallMoves() {
        val threshold = GalleryBars.SCROLL_HIDE_THRESHOLD
        assertEquals(24f, threshold)
        assertEquals(true, GalleryBars.scrollDirectionHides(100f, 200f))
        assertEquals(false, GalleryBars.scrollDirectionHides(200f, 100f))
        assertNull(GalleryBars.scrollDirectionHides(300f, 300f + threshold / 2))
        assertEquals(false, GalleryBars.scrollDirectionHides(200f, threshold))
    }

    @Test
    fun ordersTagsByTheSavedOrderKeepsEmptyOrderedTagsAndAppendsTheRestAlphabetically() {
        val counted = listOf(TagCount("beach", 3), TagCount("city", 1), TagCount("zoo", 2))
        assertEquals(
            listOf(TagCount("zoo", 2), TagCount("later", 0), TagCount("beach", 3), TagCount("city", 1)),
            orderTags(counted, listOf("zoo", "later", "beach")),
        )
        assertEquals(counted, orderTags(listOf(TagCount("zoo", 2), TagCount("city", 1), TagCount("beach", 3)), emptyList()))
    }
}
