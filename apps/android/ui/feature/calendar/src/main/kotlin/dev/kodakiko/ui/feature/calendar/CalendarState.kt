package dev.kodakiko.ui.feature.calendar

import androidx.compose.runtime.Immutable
import dev.kodakiko.ui.core.navigation.Route

@Immutable
data class CalendarDay(val count: Int, val coverEntryId: String, val coverMediaId: String?)

enum class CalendarJumpError {
    INVALID,
    EMPTY,
}

@Immutable
data class CalendarJump(val text: String, val error: CalendarJumpError? = null)

@Immutable
data class CalendarState(
    val today: String,
    val month: String,
    val months: List<String>,
    val days: Map<String, CalendarDay>,
    val canGoNext: Boolean,
    val jump: CalendarJump?,
    val pullToSync: Boolean,
    val refreshing: Boolean,
)

sealed interface CalendarAction {
    data object Previous : CalendarAction

    data object Next : CalendarAction

    data object Today : CalendarAction

    data object OpenJump : CalendarAction

    data object DismissJump : CalendarAction

    data class JumpText(val text: String) : CalendarAction

    data object SubmitJump : CalendarAction

    data class Settled(val month: String) : CalendarAction

    data class OpenDay(val localDate: String) : CalendarAction

    data object PullToSync : CalendarAction
}

sealed interface CalendarEvent {
    data class Open(val route: Route) : CalendarEvent
}
