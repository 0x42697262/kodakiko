package dev.kodakiko.data.log

import dev.kodakiko.log.DebugLog
import dev.kodakiko.log.DebugLogFormat
import dev.kodakiko.log.DebugLogInfo
import dev.kodakiko.log.LogLevel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream

interface DebugLogStore {
    fun info(): Flow<DebugLogInfo>

    fun lines(): Flow<List<String>>

    suspend fun setEnabled(on: Boolean)

    suspend fun clear()

    suspend fun snapshot(maxBytes: Long): String

    suspend fun writeTo(output: OutputStream, header: String)

    suspend fun copyToShareFile(directory: File, header: String): File
}

class FileDebugLog(private val file: DebugLogFile, dispatcher: CoroutineDispatcher) :
    DebugLog,
    DebugLogStore {
    private val io = dispatcher.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + io)
    private val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    override val enabled: Boolean get() = file.isEnabled

    override fun record(level: LogLevel, service: String, event: String, fields: Map<String, Any?>) {
        if (!file.isEnabled) return
        val message = DebugLogFormat.event(event, fields)
        scope.launch {
            file.record(level, service, message)
            changes.tryEmit(Unit)
        }
    }

    override fun info(): Flow<DebugLogInfo> = observe { file.info() }

    override fun lines(): Flow<List<String>> = observe { file.lines() }

    override suspend fun setEnabled(on: Boolean) = change { file.setEnabled(on) }

    override suspend fun clear() = change { file.clear() }

    override suspend fun snapshot(maxBytes: Long): String = withContext(io) { file.snapshot(maxBytes) }

    override suspend fun writeTo(output: OutputStream, header: String) = withContext(io) {
        output.write(header.toByteArray(Charsets.UTF_8))
        file.writeTo(output)
        output.flush()
    }

    override suspend fun copyToShareFile(directory: File, header: String): File = withContext(io) {
        directory.mkdirs()
        val target = File(directory, DebugLogConstants.SHARE_FILE_NAME)
        target.outputStream().use { output ->
            output.write(header.toByteArray(Charsets.UTF_8))
            file.writeTo(output)
        }
        target
    }

    fun changed() {
        changes.tryEmit(Unit)
    }

    private suspend fun change(block: () -> Unit) {
        withContext(io) { block() }
        changes.emit(Unit)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun <T> observe(block: () -> T): Flow<T> = changes.onStart { emit(Unit) }.mapLatest { withContext(io) { block() } }
}
