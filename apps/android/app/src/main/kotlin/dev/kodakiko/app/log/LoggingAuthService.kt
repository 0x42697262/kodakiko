package dev.kodakiko.app.log

import dev.kodakiko.log.DebugLog
import dev.kodakiko.log.LogEvents
import dev.kodakiko.log.LogFields
import dev.kodakiko.log.LogLevel
import dev.kodakiko.log.LogServices
import dev.kodakiko.log.LogValues
import dev.kodakiko.log.typeOf
import dev.kodakiko.service.account.AuthService

class LoggingAuthService(private val inner: AuthService, private val log: DebugLog) : AuthService by inner {
    override suspend fun restore() = logged(AppLogConstants.METHOD_RESTORE) { inner.restore() }

    override suspend fun unlockWithPassword(password: String) = logged(AppLogConstants.METHOD_PASSWORD) { inner.unlockWithPassword(password) }

    override suspend fun unlockWithDevice(): Boolean = logged(AppLogConstants.METHOD_DEVICE) { inner.unlockWithDevice() }

    private suspend fun <T> logged(method: String, block: suspend () -> T): T {
        val started = System.nanoTime()
        log.record(LogLevel.INFO, LogServices.AUTH, LogEvents.UNLOCK_START, mapOf(LogFields.METHOD to method))
        try {
            val result = block()
            log.record(
                LogLevel.INFO,
                LogServices.AUTH,
                LogEvents.UNLOCK_END,
                mapOf(LogFields.METHOD to method, LogFields.OUTCOME to LogValues.OK, LogFields.DURATION_MS to elapsedMs(started)),
            )
            return result
        } catch (failure: Throwable) {
            log.record(
                LogLevel.WARN,
                LogServices.AUTH,
                LogEvents.UNLOCK_END,
                mapOf(LogFields.METHOD to method, LogFields.OUTCOME to LogValues.FAILED, LogFields.ERROR to typeOf(failure), LogFields.DURATION_MS to elapsedMs(started)),
            )
            throw failure
        }
    }
}
