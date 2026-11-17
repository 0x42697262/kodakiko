package dev.kodakiko.data.log

import dev.kodakiko.log.DebugLogFormat
import dev.kodakiko.log.DebugLogInfo
import dev.kodakiko.log.LogEvents
import dev.kodakiko.log.LogFields
import dev.kodakiko.log.LogLevel
import dev.kodakiko.log.LogServices
import dev.kodakiko.log.LogValues
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile
import java.time.Clock
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicBoolean

class DebugLogFile(private val directory: File, defaultOn: Boolean, private val clock: Clock = Clock.systemDefaultZone()) {
    private val log = File(directory, DebugLogConstants.FILE_NAME)
    private val flagOn = File(directory, DebugLogConstants.FLAG_ON)
    private val flagOff = File(directory, DebugLogConstants.FLAG_OFF)
    private val stamps: DateTimeFormatter = DateTimeFormatter.ofPattern(DebugLogConstants.STAMP_PATTERN)
    private val lock = Any()
    private val enabled = AtomicBoolean(
        when {
            flagOn.exists() -> true
            flagOff.exists() -> false
            else -> defaultOn
        },
    )

    val isEnabled: Boolean get() = enabled.get()

    fun setEnabled(on: Boolean) = synchronized(lock) {
        try {
            directory.mkdirs()
            if (on) {
                flagOn.writeText(DebugLogConstants.FLAG_CONTENT)
                flagOff.delete()
                enabled.set(true)
                write(LogLevel.INFO, LogServices.LOG, toggled(true))
            } else {
                if (enabled.get()) write(LogLevel.INFO, LogServices.LOG, toggled(false))
                flagOff.writeText(DebugLogConstants.FLAG_CONTENT)
                flagOn.delete()
                enabled.set(false)
            }
        } catch (ignored: IOException) {
            enabled.set(on)
        }
    }

    fun record(level: LogLevel, service: String, message: String) = synchronized(lock) {
        if (enabled.get()) write(level, service, message)
    }

    fun recordNow(level: LogLevel, service: String, message: String) = synchronized(lock) {
        write(level, service, message)
    }

    fun clear() = synchronized(lock) {
        try {
            directory.mkdirs()
            log.writeText("")
        } catch (ignored: IOException) {
            Unit
        }
    }

    fun read(): String = synchronized(lock) {
        try {
            if (log.exists()) log.readText() else ""
        } catch (ignored: IOException) {
            ""
        }
    }

    fun lines(): List<String> = read().lines().filter { it.isNotBlank() }

    fun info(): DebugLogInfo = synchronized(lock) {
        val entries = try {
            if (log.exists()) log.useLines { lines -> lines.count { it.isNotBlank() } } else 0
        } catch (ignored: IOException) {
            0
        }
        DebugLogInfo(enabled = enabled.get(), sizeBytes = if (log.exists()) log.length() else 0, entries = entries)
    }

    fun snapshot(maxBytes: Long): String = synchronized(lock) {
        try {
            if (!log.exists()) return@synchronized ""
            String(tail(maxBytes), Charsets.UTF_8)
        } catch (ignored: IOException) {
            ""
        }
    }

    fun writeTo(output: OutputStream) = synchronized(lock) {
        if (log.exists()) log.inputStream().use { it.copyTo(output) }
    }

    private fun toggled(on: Boolean): String =
        DebugLogFormat.event(LogEvents.LOGGING_ENABLED, mapOf(LogFields.ENABLED to on, LogFields.OUTCOME to LogValues.OK))

    private fun write(level: LogLevel, service: String, message: String) {
        val line = DebugLogFormat.line(LocalDateTime.now(clock).format(stamps), level, service, message) ?: return
        try {
            directory.mkdirs()
            log.appendText(line + DebugLogConstants.NEWLINE)
            if (log.length() > DebugLogConstants.MAX_BYTES) log.writeBytes(tail(DebugLogConstants.TRIM_TO_BYTES))
        } catch (ignored: IOException) {
            Unit
        }
    }

    private fun tail(maxBytes: Long): ByteArray {
        val bytes = RandomAccessFile(log, DebugLogConstants.READ_MODE).use { file ->
            val keep = minOf(maxBytes, file.length())
            if (keep == file.length()) return ByteArray(keep.toInt()).also(file::readFully)
            file.seek(file.length() - keep)
            ByteArray(keep.toInt()).also(file::readFully)
        }
        val firstBreak = bytes.indexOf(DebugLogConstants.NEWLINE_BYTE)
        val start = if (firstBreak >= 0) firstBreak + 1 else bytes.size
        return bytes.copyOfRange(start, bytes.size)
    }
}
