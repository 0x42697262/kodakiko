package dev.kodakiko.domain

import dev.kodakiko.testing.GoldenAdapter
import dev.kodakiko.testing.GoldenReplay
import kotlin.test.Test
import kotlin.test.assertEquals

class DomainClientGoldenTest {
    private val calendarModelFunctions = setOf("canShowMonth", "monthTarget", "pagerMonths", "isValidLocalDate", "constants")

    private val calendarLabelsReplayedInTheUi = setOf("formatMonthTitle", "weekdayNames", "cellLabel")

    private val calendar = GoldenAdapter { fn, args ->
        DomainGoldenAdapter.call(if (fn == "constants") "calendar.constants" else fn, args)
    }

    @Test
    fun calendarModel() = GoldenReplay.runClient("calendar/model.json", calendar, calendarModelFunctions)

    @Test
    fun calendarModelCasesAreAllAccountedFor() {
        assertEquals(calendarModelFunctions + calendarLabelsReplayedInTheUi, GoldenReplay.clientFunctions("calendar/model.json"))
    }
}
