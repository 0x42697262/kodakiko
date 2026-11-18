package dev.kodakiko.app.sync

import android.util.Log
import dev.kodakiko.app.AppConstants
import dev.kodakiko.data.net.NetworkMonitor
import dev.kodakiko.log.DebugLog
import dev.kodakiko.log.LogLevel
import dev.kodakiko.log.LogServices
import dev.kodakiko.service.account.LifecyclePort
import dev.kodakiko.service.sync.SyncLifecycle
import dev.kodakiko.service.sync.SyncLog
import dev.kodakiko.service.sync.SyncStatus
import dev.kodakiko.service.sync.WifiSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class AppSyncLifecycle(private val lifecycle: LifecyclePort, private val network: NetworkMonitor) : SyncLifecycle {
    override fun subscribeActive(listener: (Boolean) -> Unit): () -> Unit = lifecycle.subscribeActive(listener)

    override fun subscribeOnline(listener: () -> Unit): () -> Unit {
        var online = network.current().online
        return network.subscribe { state ->
            val cameOnline = state.online && !online
            online = state.online
            if (cameOnline) listener()
        }
    }
}

class NetworkWifiSource(private val network: NetworkMonitor) : WifiSource {
    override fun isWifi(): Boolean = network.isWifi()

    override fun subscribe(listener: () -> Unit): () -> Unit = network.subscribe { listener() }
}

class AppSyncLog(private val log: DebugLog) : SyncLog {
    override fun warn(event: String, fields: Map<String, Any?>) {
        Log.w(AppConstants.LOG_TAG, event)
        log.record(LogLevel.WARN, LogServices.SYNC, event, fields)
    }
}

class AppSyncState(scope: CoroutineScope) {
    private val state = MutableStateFlow(IDLE)

    val status: StateFlow<SyncStatus> = state.asStateFlow()

    val syncedAt: StateFlow<Long?> = status.map { it.lastSuccessAt }.stateIn(scope, SharingStarted.Eagerly, null)

    fun publish(next: SyncStatus) {
        state.value = next
    }

    fun offline(): Boolean = state.value.offline

    private companion object {
        val IDLE = SyncStatus(running = false, queued = 0, dead = 0, waitingForWifi = 0, lastSuccessAt = null, offline = false)
    }
}
