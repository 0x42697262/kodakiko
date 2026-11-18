package dev.kodakiko.ui.feature.calendar

import dev.kodakiko.domain.WeekStart
import dev.kodakiko.testing.GoldenAdapter
import dev.kodakiko.testing.GoldenReplay
import dev.kodakiko.testing.UnsupportedGoldenFunction
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
class CalendarLabelsGoldenTest {
    private val labelFunctions = setOf("formatMonthTitle", "weekdayNames", "cellLabel")

    private fun weekStart(index: Int): WeekStart = WeekStart.entries.single { it.dayIndex == index }

    private val adapter = GoldenAdapter { fn, args ->
        when (fn) {
            "formatMonthTitle" -> JsonPrimitive(CalendarLabels.monthTitle(args.getValue("yearMonth").jsonPrimitive.content))
            "weekdayNames" -> JsonArray(CalendarLabels.weekdayNames(weekStart(args.getValue("weekStartsOn").jsonPrimitive.int)).map(::JsonPrimitive))
            "cellLabel" -> JsonPrimitive(
                CalendarLabels.cellLabel(resources, args.getValue("localDate").jsonPrimitive.content, args.getValue("count").jsonPrimitive.int),
            )
            else -> throw UnsupportedGoldenFunction(fn)
        }
    }

    @Test
    fun theLabelCasesDeferredByTheDomainReplayMatchTheV031Texts() = GoldenReplay.runClient("calendar/model.json", adapter, labelFunctions)

    @Test
    fun theTsTestCasesReadExactlyAsV031() {
        assertEquals("November 2026", CalendarLabels.monthTitle("2026-11"))
        assertEquals("February 2024", CalendarLabels.monthTitle("2024-02"))
        assertEquals(listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"), CalendarLabels.weekdayNames(WeekStart.MONDAY))
        assertEquals(listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"), CalendarLabels.weekdayNames(WeekStart.SUNDAY))
        assertEquals("November 9, 2026, 3 entries", CalendarLabels.cellLabel(resources, "2026-11-09", 3))
        assertEquals("November 9, 2026, 1 entry", CalendarLabels.cellLabel(resources, "2026-11-09", 1))
        assertEquals("November 9, 2026, no entries", CalendarLabels.cellLabel(resources, "2026-11-09", 0))
    }

    @Test
    fun labelsStayEnglishWhateverTheDefaultLocale() {
        val saved = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            assertEquals("March 2026", CalendarLabels.monthTitle("2026-03"))
            assertEquals("Mon", CalendarLabels.weekdayNames(WeekStart.MONDAY).first())
            assertEquals("March 1, 2026, 1000 entries", CalendarLabels.cellLabel(resources, "2026-03-01", 1000))
        } finally {
            Locale.setDefault(saved)
        }
    }

    @Test
    fun weeksStartOnMondayLikeV031() {
        assertEquals(WeekStart.MONDAY, CalendarConstants.WEEK_START)
    }

    @Test
    fun everyCalendarModelFunctionIsReplayedSomewhere() {
        val domain = setOf("canShowMonth", "monthTarget", "pagerMonths", "isValidLocalDate", "constants")
        assertEquals(GoldenReplay.clientFunctions("calendar/model.json"), domain + labelFunctions)
    }
}
