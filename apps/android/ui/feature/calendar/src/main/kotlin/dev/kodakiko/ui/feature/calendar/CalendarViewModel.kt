package dev.kodakiko.ui.feature.calendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.kodakiko.data.db.DeviceSettings
import dev.kodakiko.data.secure.AccountMode
import dev.kodakiko.domain.canShowMonth
import dev.kodakiko.domain.isValidYearMonth
import dev.kodakiko.domain.monthTarget
import dev.kodakiko.domain.pagerMonths
import dev.kodakiko.domain.yearMonthOf
import dev.kodakiko.index.GalleryFilter
import dev.kodakiko.index.GalleryScope
import dev.kodakiko.index.GalleryView
import dev.kodakiko.js.jsTrim
import dev.kodakiko.platform.device.HapticKind
import dev.kodakiko.ui.core.navigation.Route
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private data class CalendarUi(val month: String, val jump: CalendarJump? = null, val refreshing: Boolean = false)

private class JournalDays(val days: Map<String, CalendarDay>, val monthsWithEntries: Set<String>)

class CalendarViewModel(private val deps: CalendarDeps) : ViewModel() {
    private val eventChannel = Channel<CalendarEvent>(Channel.UNLIMITED)
    private val ui = MutableStateFlow(CalendarUi(month = yearMonthOf(deps.clock.todayLocalDate())))

    val events: Flow<CalendarEvent> = eventChannel.receiveAsFlow()

    val state: StateFlow<CalendarState> = combine(deps.index.version, ui, deps.accountMode, deps.settings) { _, current, mode, settings ->
        build(current, mode, settings)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(CalendarConstants.STATE_SUBSCRIPTION_MS),
        build(ui.value, deps.accountMode.value, deps.settings.value),
    )

    fun onAction(action: CalendarAction) {
        when (action) {
            CalendarAction.Previous -> move(CalendarConstants.PREVIOUS_MONTH)
            CalendarAction.Next -> move(CalendarConstants.NEXT_MONTH)
            CalendarAction.Today -> ui.update { it.copy(month = currentMonth()) }
            is CalendarAction.Settled -> ui.update { it.copy(month = action.month) }
            CalendarAction.OpenJump -> ui.update { it.copy(jump = CalendarJump(text = it.month)) }
            CalendarAction.DismissJump -> ui.update { it.copy(jump = null) }
            is CalendarAction.JumpText -> ui.update { current -> current.copy(jump = current.jump?.copy(text = action.text)) }
            CalendarAction.SubmitJump -> submitJump()
            is CalendarAction.OpenDay -> openDay(action.localDate)
            CalendarAction.PullToSync -> pullToSync()
        }
    }

    private fun currentMonth(): String = yearMonthOf(deps.clock.todayLocalDate())

    private fun journalDays(): JournalDays {
        val index = deps.index.index() ?: return JournalDays(emptyMap(), emptySet())
        val sections = index.query(JOURNAL)
        val days = LinkedHashMap<String, CalendarDay>()
        for (section in sections) {
            val coverEntryId = section.entryIds.firstOrNull() ?: continue
            days[section.localDate] = CalendarDay(section.entryIds.size, coverEntryId, index.get(coverEntryId)?.coverMediaId)
        }
        return JournalDays(days, sections.mapTo(HashSet()) { yearMonthOf(it.localDate) })
    }

    private fun build(current: CalendarUi, mode: AccountMode, settings: DeviceSettings): CalendarState {
        val today = deps.clock.todayLocalDate()
        val thisMonth = yearMonthOf(today)
        val journal = journalDays()
        return CalendarState(
            today = today,
            month = current.month,
            months = pagerMonths(thisMonth, journal.monthsWithEntries, current.month),
            days = journal.days,
            canGoNext = monthTarget(current.month, CalendarConstants.NEXT_MONTH, thisMonth, journal.monthsWithEntries) != null,
            jump = current.jump,
            pullToSync = syncAvailable(mode, settings),
            refreshing = current.refreshing,
        )
    }

    private fun syncAvailable(mode: AccountMode, settings: DeviceSettings): Boolean = mode == AccountMode.SERVER && !settings.syncPaused

    private fun move(delta: Long) {
        val withEntries = journalDays().monthsWithEntries
        val thisMonth = currentMonth()
        ui.update { current -> current.copy(month = monthTarget(current.month, delta, thisMonth, withEntries) ?: current.month) }
    }

    private fun submitJump() {
        val jump = ui.value.jump ?: return
        val value = jsTrim(jump.text)
        val error = when {
            !isValidYearMonth(value) -> CalendarJumpError.INVALID
            !canShowMonth(value, currentMonth(), journalDays().monthsWithEntries) -> CalendarJumpError.EMPTY
            else -> null
        }
        if (error != null) {
            ui.update { it.copy(jump = jump.copy(error = error)) }
            return
        }
        ui.update { it.copy(month = value, jump = null) }
    }

    private fun openDay(localDate: String) {
        if (localDate !in journalDays().days) return
        eventChannel.trySend(CalendarEvent.Open(Route.Day(localDate)))
    }

    private fun pullToSync() {
        if (!syncAvailable(deps.accountMode.value, deps.settings.value) || ui.value.refreshing) return
        deps.haptics(HapticKind.REFRESH)
        ui.update { it.copy(refreshing = true) }
        viewModelScope.launch {
            try {
                deps.sync.syncNow()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
            } finally {
                ui.update { it.copy(refreshing = false) }
            }
        }
    }

    private companion object {
        val JOURNAL = GalleryFilter(GalleryScope.Home, GalleryView.JOURNAL, emptyList(), null, null, "")
    }
}
