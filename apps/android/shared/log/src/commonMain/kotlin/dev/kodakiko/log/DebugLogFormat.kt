package dev.kodakiko.log

object DebugLogFormat {
    private val forbiddenField = Regex(
        LogFormatConstants.FORBIDDEN_PREFIX + LogFormatConstants.FORBIDDEN_WORDS.joinToString(LogFormatConstants.ALTERNATION) + LogFormatConstants.FORBIDDEN_SUFFIX,
    )
    private val safeValue = Regex(LogFormatConstants.SAFE_VALUE)
    private val tokenJunk = Regex(LogFormatConstants.TOKEN_JUNK)
    private val frameJunk = Regex(LogFormatConstants.FRAME_JUNK)
    private val camelBoundary = Regex(LogFormatConstants.CAMEL_BOUNDARY)

    fun line(stamp: String, level: LogLevel, service: String, message: String): String? {
        val text = sanitize(message)
        if (text.isEmpty()) return null
        return listOf(
            stamp,
            level.name.padEnd(LogFormatConstants.LEVEL_WIDTH),
            service(service).padEnd(LogFormatConstants.SERVICE_WIDTH),
            text,
        ).joinToString(LogFormatConstants.SEPARATOR)
    }

    fun event(event: String, fields: Map<String, Any?>): String {
        val parts = mutableListOf(token(event, LogFormatConstants.FALLBACK_EVENT))
        fields.forEach { (rawKey, value) ->
            val key = token(rawKey.replace(camelBoundary, LogFormatConstants.CAMEL_REPLACEMENT), LogFormatConstants.FALLBACK_FIELD)
            if (!forbiddenField.containsMatchIn(key)) parts += key + LogFormatConstants.ASSIGN + fieldValue(value)
        }
        return parts.joinToString(LogFormatConstants.FIELD_SEPARATOR)
    }

    fun frame(element: String): String = element
        .take(LogFormatConstants.MAX_FRAME_CHARS)
        .replace(frameJunk, LogFormatConstants.REPLACEMENT)
        .ifEmpty { LogValues.UNKNOWN }

    fun frameMessage(element: String): String = LogEvents.CRASH_FRAME + LogFormatConstants.ASSIGN + frame(element)

    fun isForbidden(key: String): Boolean = forbiddenField.containsMatchIn(token(key.replace(camelBoundary, LogFormatConstants.CAMEL_REPLACEMENT), LogFormatConstants.FALLBACK_FIELD))

    private fun service(raw: String): String = raw
        .trim()
        .take(LogFormatConstants.MAX_SERVICE_CHARS)
        .lowercase()
        .replace(tokenJunk, LogFormatConstants.REPLACEMENT)
        .ifEmpty { LogFormatConstants.UNKNOWN_SERVICE }

    private fun sanitize(message: String): String = message
        .trim()
        .take(LogFormatConstants.MAX_MESSAGE_CHARS)
        .map { if (it.isISOControl()) ' ' else it }
        .joinToString("")

    private fun token(value: String, fallback: String): String =
        value.trim().lowercase().replace(tokenJunk, LogFormatConstants.REPLACEMENT).take(LogFormatConstants.MAX_TOKEN_CHARS).ifEmpty { fallback }

    private fun fieldValue(value: Any?): String = when (value) {
        null -> LogValues.NULL
        is Double -> if (value.isFinite()) value.toString() else LogValues.INVALID_NUMBER
        is Float -> if (value.isFinite()) value.toString() else LogValues.INVALID_NUMBER
        is Number, is Boolean -> value.toString()
        is Enum<*> -> safe(value.name)
        else -> safe(value.toString())
    }

    private fun safe(text: String): String = if (safeValue.matches(text)) text else LogValues.REDACTED
}
