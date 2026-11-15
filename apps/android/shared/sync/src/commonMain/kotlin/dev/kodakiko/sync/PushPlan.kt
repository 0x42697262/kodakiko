package dev.kodakiko.sync

import dev.kodakiko.contract.OutboxKind
import dev.kodakiko.contract.WireEnum

enum class OutboxItemState(override val wire: String) : WireEnum {
    QUEUED("queued"),
    DEAD("dead"),
}

data class OutboxItemView(
    val id: Long,
    val kind: OutboxKind,
    val entryId: String?,
    val state: OutboxItemState,
    val nextAttemptAt: Double,
)

fun planPushBatch(items: List<OutboxItemView>, inFlightEntryIds: Set<String>, now: Double, capacity: Int): List<Long> {
    val seenEntryIds = HashSet<String>()
    val selected = mutableListOf<Long>()
    for (item in items.sortedBy { it.id }) {
        val blocked = item.entryId != null && (item.entryId in seenEntryIds || item.entryId in inFlightEntryIds)
        val due = item.state == OutboxItemState.QUEUED && item.nextAttemptAt <= now
        if (!blocked && due && selected.size < capacity) selected.add(item.id)
        if (item.entryId != null) seenEntryIds.add(item.entryId)
    }
    return selected
}
