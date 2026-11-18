package dev.kodakiko.data.log

import dev.kodakiko.log.DebugLogInfo
import dev.kodakiko.log.LogLevel
import dev.kodakiko.log.LogServices
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class DebugLogFileTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val clock = Clock.fixed(Instant.parse("2026-11-18T04:13:00Z"), ZoneOffset.UTC)

    private val directory: File get() = File(temp.root, DebugLogConstants.DIRECTORY)

    private fun file(defaultOn: Boolean) = DebugLogFile(directory, defaultOn, clock)

    private fun line(level: String, service: String, message: String) = "2026-11-18 04:13:00.000  ${level.padEnd(LEVEL_WIDTH)}  ${service.padEnd(SERVICE_WIDTH)}  $message"

    private fun toggled(on: Boolean) = line("INFO", LogServices.LOG, "set_debug_logging enabled=$on outcome=ok")

    @Test
    fun withoutAFlagTheDefaultDecidesAndRecordWritesOnlyWhenOn() {
        val on = file(defaultOn = true)
        assertTrue(on.isEnabled)
        on.record(LogLevel.INFO, LogServices.SYNC, "sync_pass pushed=2")
        assertEquals(listOf(line("INFO", LogServices.SYNC, "sync_pass pushed=2")), on.lines())
        on.clear()
        val off = file(defaultOn = false)
        assertFalse(off.isEnabled)
        off.record(LogLevel.INFO, LogServices.SYNC, "sync_pass pushed=2")
        assertEquals(emptyList<String>(), off.lines())
    }

    @Test
    fun recordNowWritesEvenWhenLoggingIsOff() {
        val off = file(defaultOn = false)
        off.recordNow(LogLevel.ERROR, LogServices.CRASH, "uncaught type=IllegalStateException")
        assertEquals(listOf(line("ERROR", LogServices.CRASH, "uncaught type=IllegalStateException")), off.lines())
        assertFalse(off.isEnabled)
    }

    @Test
    fun turningOffWritesTheSwitchLineFirstAndLeavesAnOffFlagThatBeatsTheDefault() {
        val log = file(defaultOn = true)
        log.setEnabled(false)
        assertFalse(log.isEnabled)
        assertEquals(listOf(toggled(false)), log.lines())
        assertTrue(File(directory, DebugLogConstants.FLAG_OFF).exists())
        assertFalse(File(directory, DebugLogConstants.FLAG_ON).exists())
        assertFalse(file(defaultOn = true).isEnabled)
        log.setEnabled(false)
        assertEquals(listOf(toggled(false)), log.lines())
    }

    @Test
    fun turningOnLeavesAnOnFlagThatBeatsTheDefaultAndWritesTheSwitchLine() {
        val log = file(defaultOn = false)
        log.setEnabled(true)
        assertTrue(log.isEnabled)
        assertEquals(listOf(toggled(true)), log.lines())
        assertTrue(File(directory, DebugLogConstants.FLAG_ON).exists())
        assertFalse(File(directory, DebugLogConstants.FLAG_OFF).exists())
        assertTrue(file(defaultOn = false).isEnabled)
    }

    @Test
    fun emptyMessagesWriteNothing() {
        val log = file(defaultOn = true)
        log.record(LogLevel.INFO, LogServices.APP, "  ")
        log.recordNow(LogLevel.INFO, LogServices.APP, "")
        assertEquals("", log.read())
    }

    @Test
    fun aFileOverTheLimitIsTrimmedToItsNewestWholeLines() {
        val log = file(defaultOn = true)
        val count = (DebugLogConstants.MAX_BYTES / LINE_BYTES + 2).toInt()
        repeat(count) { log.record(LogLevel.DEBUG, LogServices.APP, numbered(it)) }
        val size = File(directory, DebugLogConstants.FILE_NAME).length()
        assertTrue("size $size", size <= DebugLogConstants.TRIM_TO_BYTES + LINE_BYTES)
        assertTrue("size $size", size < DebugLogConstants.MAX_BYTES)
        val lines = log.lines()
        assertTrue(lines.all { it.startsWith("2026-11-18 04:13:00.000  DEBUG  app") })
        assertEquals(numbered(count - 1), lines.last().substringAfterLast("  "))
        val first = lines.first().substringAfterLast("  ").substringBefore(":").toInt()
        assertEquals((first until count).map(::numbered), lines.map { it.substringAfterLast("  ") })
        assertTrue(first > 0)
    }

    @Test
    fun aSnapshotIsTheTailFromAWholeLine() {
        val log = file(defaultOn = true)
        repeat(SNAPSHOT_LINES) { log.record(LogLevel.INFO, LogServices.APP, numbered(it)) }
        assertEquals(log.read(), log.snapshot(DebugLogConstants.MAX_BYTES))
        val tail = log.snapshot(LINE_BYTES * 2 + LINE_BYTES / 2).lines().filter { it.isNotBlank() }
        assertEquals(listOf(numbered(SNAPSHOT_LINES - 2), numbered(SNAPSHOT_LINES - 1)), tail.map { it.substringAfterLast("  ") })
        assertEquals("", file(defaultOn = true).also { it.clear() }.snapshot(LINE_BYTES))
    }

    @Test
    fun infoCountsLinesAndBytesAndClearEmptiesTheLog() {
        val log = file(defaultOn = true)
        assertEquals(DebugLogInfo(enabled = true, sizeBytes = 0, entries = 0), log.info())
        log.record(LogLevel.INFO, LogServices.APP, "app_start")
        log.record(LogLevel.WARN, LogServices.SYNC, "sync_warning")
        val text = log.read()
        assertEquals(DebugLogInfo(enabled = true, sizeBytes = text.toByteArray().size.toLong(), entries = 2), log.info())
        val copy = ByteArrayOutputStream()
        log.writeTo(copy)
        assertEquals(text, copy.toString(Charsets.UTF_8))
        log.clear()
        assertEquals(DebugLogInfo(enabled = true, sizeBytes = 0, entries = 0), log.info())
        assertEquals(emptyList<String>(), log.lines())
    }

    private fun numbered(position: Int): String = position.toString().padStart(NUMBER_WIDTH, '0') + ":" + "x".repeat(FILLER)

    private companion object {
        const val LEVEL_WIDTH = 5
        const val SERVICE_WIDTH = 9
        const val NUMBER_WIDTH = 6
        const val FILLER = 1_000
        const val LINE_BYTES = 1_051L
        const val SNAPSHOT_LINES = 5
    }
}
