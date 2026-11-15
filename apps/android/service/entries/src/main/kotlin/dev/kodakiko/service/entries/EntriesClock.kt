package dev.kodakiko.service.entries

import dev.kodakiko.data.db.DeviceSettings
import dev.kodakiko.js.JsDate
import java.util.TimeZone

interface EntriesClock {
    fun now(): Long

    fun nowIso(): String

    fun timezoneOffsetMinutes(epochMs: Long): Long

    fun timeZone(): String?
}

object SystemEntriesClock : EntriesClock {
    override fun now(): Long = System.currentTimeMillis()

    override fun nowIso(): String = JsDate.toIsoString(now().toDouble())

    override fun timezoneOffsetMinutes(epochMs: Long): Long = 0 - TimeZone.getDefault().getOffset(epochMs) / EntriesConstants.MS_PER_MINUTE

    override fun timeZone(): String? = TimeZone.getDefault().id
}

interface DeviceSettingsSource {
    fun loaded(): Boolean

    fun get(): DeviceSettings
}
