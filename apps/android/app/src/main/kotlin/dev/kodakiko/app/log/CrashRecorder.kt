package dev.kodakiko.app.log

import android.os.Looper
import dev.kodakiko.data.log.DebugLogConstants
import dev.kodakiko.data.log.DebugLogFile
import dev.kodakiko.log.DebugLogFormat
import dev.kodakiko.log.LogEvents
import dev.kodakiko.log.LogFields
import dev.kodakiko.log.LogLevel
import dev.kodakiko.log.LogServices
import dev.kodakiko.log.typeOf

object CrashRecorder {
    fun install(file: DebugLogFile) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, failure ->
            record(file, thread, failure)
            previous?.uncaughtException(thread, failure)
        }
    }

    private fun record(file: DebugLogFile, thread: Thread, failure: Throwable) {
        try {
            val where = if (thread === Looper.getMainLooper().thread) AppLogConstants.MAIN_THREAD else AppLogConstants.BACKGROUND_THREAD
            write(file, DebugLogFormat.event(LogEvents.UNCAUGHT, mapOf(LogFields.THREAD to where, LogFields.TYPE to typeOf(failure))))
            frames(file, failure)
            var cause = failure.cause
            var depth = 0
            while (cause != null && cause !== failure && depth < DebugLogConstants.MAX_CAUSES) {
                write(file, DebugLogFormat.event(LogEvents.CAUSED_BY, mapOf(LogFields.TYPE to typeOf(cause))))
                frames(file, cause)
                cause = cause.cause
                depth += 1
            }
        } catch (_: Throwable) {
        }
    }

    private fun frames(file: DebugLogFile, failure: Throwable) {
        failure.stackTrace.take(DebugLogConstants.MAX_CRASH_FRAMES).forEach { element ->
            write(file, DebugLogFormat.frameMessage(element.toString()))
        }
    }

    private fun write(file: DebugLogFile, message: String) {
        file.recordNow(LogLevel.ERROR, LogServices.CRASH, message)
    }
}
