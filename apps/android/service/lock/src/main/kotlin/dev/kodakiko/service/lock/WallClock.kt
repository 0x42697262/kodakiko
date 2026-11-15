package dev.kodakiko.service.lock

import dev.kodakiko.js.JsDate

interface WallClock {
    fun now(): Long

    fun nowIso(): String
}

object SystemWallClock : WallClock {
    override fun now(): Long = System.currentTimeMillis()

    override fun nowIso(): String = JsDate.toIsoString(now().toDouble())
}
