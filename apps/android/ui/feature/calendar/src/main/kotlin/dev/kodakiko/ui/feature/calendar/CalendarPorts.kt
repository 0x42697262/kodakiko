package dev.kodakiko.ui.feature.calendar

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.ImageBitmap
import dev.kodakiko.data.db.DeviceSettings
import dev.kodakiko.data.secure.AccountMode
import dev.kodakiko.platform.device.HapticKind
import dev.kodakiko.service.index.IndexService
import kotlinx.coroutines.flow.StateFlow

fun interface CalendarClock {
    fun todayLocalDate(): String
}

fun interface CalendarSync {
    suspend fun syncNow()
}

fun interface CalendarThumbnails {
    suspend fun load(entryId: String, mediaId: String): ImageBitmap?
}

object NoCalendarThumbnails : CalendarThumbnails {
    override suspend fun load(entryId: String, mediaId: String): ImageBitmap? = null
}

val LocalCalendarThumbnails: ProvidableCompositionLocal<CalendarThumbnails> = staticCompositionLocalOf { NoCalendarThumbnails }

class CalendarDeps(
    val index: IndexService,
    val clock: CalendarClock,
    val sync: CalendarSync,
    val accountMode: StateFlow<AccountMode>,
    val settings: StateFlow<DeviceSettings>,
    val haptics: (HapticKind) -> Unit,
)
