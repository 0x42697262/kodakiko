package dev.kodakiko.ui.feature.settings

import android.content.res.Resources
import androidx.compose.runtime.Immutable
import androidx.lifecycle.viewModelScope
import dev.kodakiko.contract.ErrorCode
import dev.kodakiko.contract.OutboxKind
import dev.kodakiko.data.db.OutboxRow
import dev.kodakiko.data.db.OutboxState
import dev.kodakiko.data.secure.AccountMode
import dev.kodakiko.service.sync.SyncErrorCodes
import dev.kodakiko.service.sync.SyncPayloadKeys
import dev.kodakiko.service.sync.SyncStatus
import dev.kodakiko.ui.core.R
import dev.kodakiko.ui.core.navigation.Route
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@Immutable
data class SyncSettingsState(val local: Boolean, val status: SyncStatus, val syncing: Boolean, val dead: List<OutboxRow>)

sealed interface SyncSettingsAction {
    data object SyncNow : SyncSettingsAction

    data object RetryFailed : SyncSettingsAction

    data object Connect : SyncSettingsAction

    data object Back : SyncSettingsAction
}

private val KIND_LABELS: Map<String, Int> = mapOf(
    OutboxKind.CREATE_ENTRY.wire to R.string.sync_kind_create_entry,
    OutboxKind.UPDATE_ENTRY_META.wire to R.string.sync_kind_update_entry_meta,
    OutboxKind.ADD_MEDIA.wire to R.string.sync_kind_add_media,
    OutboxKind.REMOVE_MEDIA.wire to R.string.sync_kind_remove_media,
    OutboxKind.TRASH_ENTRY.wire to R.string.sync_kind_trash_entry,
    OutboxKind.RESTORE_ENTRY.wire to R.string.sync_kind_restore_entry,
    OutboxKind.DELETE_ENTRY.wire to R.string.sync_kind_delete_entry,
)

private val ERROR_LABELS: Map<String, Int> = mapOf(
    ErrorCode.QUOTA_EXCEEDED.wire to R.string.sync_error_quota,
    ErrorCode.FEATURE_DISABLED.wire to R.string.sync_error_disabled,
    ErrorCode.VALIDATION_FAILED.wire to R.string.sync_error_rejected,
    SyncErrorCodes.NETWORK to R.string.sync_error_network,
)

fun deadItemText(resources: Resources, row: OutboxRow): String {
    val kind = KIND_LABELS[row.kind]?.let(resources::getString) ?: row.kind
    val entryId = ((row.payload as? JsonObject)?.get(SyncPayloadKeys.ENTRY_ID) as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
    val error = resources.getString(row.lastErrorCode?.let(ERROR_LABELS::get) ?: R.string.sync_error_server)
    return kind + SettingsConstants.SUMMARY_DASH + entryId + SettingsConstants.SUMMARY_DASH + error
}

class SyncSettingsViewModel(deps: SettingsDeps) : DeviceSettingsViewModel(deps, Route.SettingsSync) {
    private val syncing = MutableStateFlow(false)
    private val dead = MutableStateFlow<List<OutboxRow>>(emptyList())

    val state: StateFlow<SyncSettingsState> = combine(deps.account.mode, deps.sync.status, syncing, dead) { mode, status, busy, rows ->
        SyncSettingsState(local = mode == AccountMode.LOCAL, status = status, syncing = busy, dead = rows)
    }.asState(SyncSettingsState(deps.account.mode.value == AccountMode.LOCAL, deps.sync.status.value, syncing.value, dead.value))

    init {
        viewModelScope.launch {
            deps.sync.status.map { it.dead to it.queued }.distinctUntilChanged().collect { reload() }
        }
    }

    fun onAction(action: SyncSettingsAction) {
        when (action) {
            SyncSettingsAction.SyncNow -> syncNow()
            SyncSettingsAction.RetryFailed -> viewModelScope.launch {
                attempt { deps.sync.retryDead() }
                reload()
            }
            SyncSettingsAction.Connect -> send(SettingsEvent.Open(Route.SettingsConnect))
            SyncSettingsAction.Back -> back()
        }
    }

    private fun syncNow() {
        if (syncing.value) return
        syncing.value = true
        viewModelScope.launch {
            try {
                attempt { deps.sync.syncNow() }
            } finally {
                syncing.value = false
            }
        }
    }

    private suspend fun reload() {
        attempt { deps.sync.outbox() }?.let { rows -> dead.value = rows.filter { it.state == OutboxState.DEAD } }
    }
}
