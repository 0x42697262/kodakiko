package dev.kodakiko.sync

import dev.kodakiko.contract.WireEnum

enum class EntryLocalState(override val wire: String) : WireEnum {
    SYNCED("synced"),
    PENDING_CREATE("pending_create"),
    PENDING_UPDATE("pending_update"),
}

data class LocalEntryState(val localState: EntryLocalState, val baseRevision: Long)

enum class PulledEntryDecision(override val wire: String) : WireEnum {
    INSERT("insert"),
    OVERWRITE("overwrite"),
    UPDATE_TRASH_ONLY("updateTrashOnly"),
    REJECT_ROLLBACK("rejectRollback"),
}

fun decidePulledEntry(local: LocalEntryState?, pulledRevision: Long): PulledEntryDecision {
    if (local == null) return PulledEntryDecision.INSERT
    if (pulledRevision < local.baseRevision) return PulledEntryDecision.REJECT_ROLLBACK
    return if (local.localState == EntryLocalState.SYNCED) PulledEntryDecision.OVERWRITE else PulledEntryDecision.UPDATE_TRASH_ONLY
}
