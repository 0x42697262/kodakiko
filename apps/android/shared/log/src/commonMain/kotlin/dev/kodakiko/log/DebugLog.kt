package dev.kodakiko.log

enum class LogLevel {
    DEBUG,
    INFO,
    WARN,
    ERROR,
}

data class DebugLogInfo(val enabled: Boolean, val sizeBytes: Long, val entries: Int) {
    companion object {
        val EMPTY: DebugLogInfo = DebugLogInfo(enabled = false, sizeBytes = 0, entries = 0)
    }
}

interface DebugLog {
    val enabled: Boolean

    fun record(level: LogLevel, service: String, event: String, fields: Map<String, Any?> = emptyMap())
}

object NoDebugLog : DebugLog {
    override val enabled: Boolean = false

    override fun record(level: LogLevel, service: String, event: String, fields: Map<String, Any?>) = Unit
}

fun DebugLog.info(service: String, event: String, fields: Map<String, Any?> = emptyMap()) = record(LogLevel.INFO, service, event, fields)

fun DebugLog.warn(service: String, event: String, fields: Map<String, Any?> = emptyMap()) = record(LogLevel.WARN, service, event, fields)

fun DebugLog.error(service: String, event: String, fields: Map<String, Any?> = emptyMap()) = record(LogLevel.ERROR, service, event, fields)

fun typeOf(failure: Throwable): String = failure::class.simpleName ?: LogValues.UNKNOWN
