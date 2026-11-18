package dev.kodakiko.ui.feature.gallery

import dev.kodakiko.domain.localPartsToUtc
import dev.kodakiko.index.GalleryFilter
import dev.kodakiko.index.GalleryScope
import dev.kodakiko.index.GalleryView
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs

enum class DateRangeProblem {
    START_AFTER_END,
    INVALID_DATE,
}

object GalleryFilters {
    val EMPTY: GalleryFilter = GalleryFilter(GalleryScope.Home, GalleryView.JOURNAL, emptyList(), null, null, "")

    fun isActive(filter: GalleryFilter): Boolean =
        filter.tags.isNotEmpty() || filter.fromDate != null || filter.toDate != null || filter.text.isNotBlank()

    fun validateDateRange(from: String, to: String): DateRangeProblem? {
        if (!validDate(from) || !validDate(to)) return DateRangeProblem.INVALID_DATE
        if (from.isNotEmpty() && to.isNotEmpty() && from > to) return DateRangeProblem.START_AFTER_END
        return null
    }

    private fun validDate(value: String): Boolean =
        value.isEmpty() || localPartsToUtc(value, GalleryConstants.DATE_MIDNIGHT, GalleryConstants.UTC_OFFSET_ZERO) != null
}

class GalleryFilterStore {
    private val state = MutableStateFlow(GalleryFilters.EMPTY)

    val filter: StateFlow<GalleryFilter> = state.asStateFlow()

    fun set(filter: GalleryFilter) {
        state.value = filter
    }

    fun reset() {
        state.value = GalleryFilters.EMPTY
    }
}

object GalleryBars {
    const val SCROLL_HIDE_THRESHOLD: Float = GalleryConstants.SCROLL_HIDE_THRESHOLD

    fun scrollDirectionHides(previousY: Float, nextY: Float): Boolean? {
        if (nextY <= SCROLL_HIDE_THRESHOLD) return false
        if (abs(nextY - previousY) < SCROLL_HIDE_THRESHOLD) return null
        return nextY > previousY
    }
}
