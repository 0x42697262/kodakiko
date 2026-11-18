package dev.kodakiko.service.lock

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlin.coroutines.ContinuationInterceptor

class UnlockedWork(private val dispatcher: CoroutineDispatcher) {
    private val lock = Any()

    @Volatile
    private var job: CompletableJob = SupervisorJob()

    @Volatile
    private var current: CoroutineScope = CoroutineScope(job + dispatcher)

    val scope: CoroutineScope get() = current

    suspend fun <T> runUnlocked(block: suspend () -> T): T {
        val caller = currentCoroutineContext()[ContinuationInterceptor] ?: dispatcher
        val work = scope.async(caller) { block() }
        try {
            return work.await()
        } catch (cancelled: CancellationException) {
            work.cancel(cancelled)
            throw cancelled
        }
    }

    suspend fun cancelAndJoin() {
        val fenced = synchronized(lock) { job }
        fenced.cancelAndJoin()
        synchronized(lock) {
            if (job === fenced) {
                job = SupervisorJob()
                current = CoroutineScope(job + dispatcher)
            }
        }
    }
}
