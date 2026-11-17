package dev.kodakiko.app.log

import dev.kodakiko.log.DebugLog
import dev.kodakiko.log.LogEvents
import dev.kodakiko.log.LogFields
import dev.kodakiko.log.LogLevel
import dev.kodakiko.log.LogServices
import dev.kodakiko.log.LogValues
import dev.kodakiko.log.typeOf

fun elapsedMs(startedNanos: Long): Long = (System.nanoTime() - startedNanos) / AppLogConstants.NANOS_PER_MILLI

suspend fun <T> DebugLog.timedStep(phase: String, step: String, block: suspend () -> T): T {
    val started = System.nanoTime()
    try {
        val result = block()
        record(
            LogLevel.INFO,
            LogServices.LOCK,
            LogEvents.STEP,
            mapOf(LogFields.PHASE to phase, LogFields.STEP to step, LogFields.DURATION_MS to elapsedMs(started), LogFields.OUTCOME to LogValues.OK),
        )
        return result
    } catch (failure: Throwable) {
        record(
            LogLevel.WARN,
            LogServices.LOCK,
            LogEvents.STEP,
            mapOf(
                LogFields.PHASE to phase,
                LogFields.STEP to step,
                LogFields.DURATION_MS to elapsedMs(started),
                LogFields.OUTCOME to LogValues.FAILED,
                LogFields.ERROR to typeOf(failure),
            ),
        )
        throw failure
    }
}
