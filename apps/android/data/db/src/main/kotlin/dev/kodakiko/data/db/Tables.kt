package dev.kodakiko.data.db

import dev.kodakiko.data.db.sql.LegacyQueries

class CollectionTable internal constructor(private val queries: LegacyQueries) {
    fun get(id: String): CollectionRow? = queries.collectionGet(id, ::collectionRow).executeAsOneOrNull()

    fun put(row: CollectionRow) {
        queries.collectionPut(
            row.id,
            row.kind.wire,
            row.ownerId,
            row.ownerUsername,
            row.role.wire,
            row.keyMaterial,
            row.metadataBlob,
            row.metaUpdatedAt,
            row.serverLastSeq,
            row.cursorSeq,
        )
    }

    fun delete(id: String) {
        queries.collectionDelete(id)
    }

    fun all(): List<CollectionRow> = queries.collectionAll(::collectionRow).executeAsList()
}

class EntryTable internal constructor(private val queries: LegacyQueries) {
    fun get(id: String): EntryRow? = queries.entryGet(id, ::entryRow).executeAsOneOrNull()

    fun put(row: EntryRow) {
        queries.entryPut(
            row.id,
            row.ownerId,
            row.purpose.wire,
            row.revision,
            row.metadataBlob,
            row.baseMetadataBlob,
            row.baseRevision,
            row.serverPendingBlob,
            row.serverPendingRevision,
            row.trashedAt,
            row.createdAt,
            row.updatedAt,
            row.localState.wire,
        )
    }

    fun delete(id: String) {
        queries.entryDelete(id)
    }

    fun all(): List<EntryRow> = queries.entryAll(::entryRow).executeAsList()

    fun byLocalState(states: List<EntryLocalState>): List<EntryRow> =
        if (states.isEmpty()) emptyList() else queries.entryByLocalState(states.map { it.wire }, ::entryRow).executeAsList()
}

class MembershipTable internal constructor(private val queries: LegacyQueries) {
    fun get(collectionId: String, entryId: String): MembershipRow? = queries.membershipGet(collectionId, entryId, ::MembershipRow).executeAsOneOrNull()

    fun put(row: MembershipRow) {
        queries.membershipPut(row.collectionId, row.entryId, row.entryKeyWrap, row.seq)
    }

    fun delete(collectionId: String, entryId: String) {
        queries.membershipDelete(collectionId, entryId)
    }

    fun all(): List<MembershipRow> = queries.membershipAll(::MembershipRow).executeAsList()

    fun byEntry(entryId: String): List<MembershipRow> = queries.membershipByEntry(entryId, ::MembershipRow).executeAsList()

    fun byCollection(collectionId: String): List<MembershipRow> = queries.membershipByCollection(collectionId, ::MembershipRow).executeAsList()
}

class MediaTable internal constructor(private val queries: LegacyQueries) {
    fun get(id: String): MediaRow? = queries.mediaGet(id, ::MediaRow).executeAsOneOrNull()

    fun put(row: MediaRow) {
        queries.mediaPut(row.id, row.entryId, row.mediaKeyWrap)
    }

    fun delete(id: String) {
        queries.mediaDelete(id)
    }

    fun all(): List<MediaRow> = queries.mediaAll(::MediaRow).executeAsList()

    fun byEntry(entryId: String): List<MediaRow> = queries.mediaByEntry(entryId, ::MediaRow).executeAsList()
}

class ObjectTable internal constructor(private val queries: LegacyQueries) {
    fun get(id: String): ObjectRow? = queries.objectGet(id, ::objectRow).executeAsOneOrNull()

    fun put(row: ObjectRow) {
        queries.objectPut(
            row.id,
            row.mediaId,
            row.variant.wire,
            row.sizeBytes,
            row.plaintextSize,
            row.localState.wire,
            row.uploadState.wire,
            row.uploadJobId,
            row.lastAccessAt,
        )
    }

    fun delete(id: String) {
        queries.objectDelete(id)
    }

    fun all(): List<ObjectRow> = queries.objectAll(::objectRow).executeAsList()

    fun byMedia(mediaId: String): List<ObjectRow> = queries.objectByMedia(mediaId, ::objectRow).executeAsList()

    fun presentOriginalsByAccess(): List<ObjectRow> =
        queries.objectPresentOriginalsByAccess(ObjectLocalState.PRESENT.wire, ObjectVariant.ORIGINAL.wire, ::objectRow).executeAsList()
}

class OutboxTable internal constructor(private val queries: LegacyQueries) {
    fun enqueue(row: NewOutboxRow): Long {
        queries.outboxInsert(row.kind, JsonCodec.encode(row.payload), row.state.wire, row.createdAt, row.attempts, row.nextAttemptAt, row.lastErrorCode)
        return queries.lastInsertRowId().executeAsOne()
    }

    fun due(now: Long, limit: Long): List<OutboxRow> = queries.outboxDue(OutboxState.QUEUED.wire, now, limit, ::outboxRow).executeAsList()

    fun update(row: OutboxRow) {
        queries.outboxUpdate(row.kind, JsonCodec.encode(row.payload), row.state.wire, row.createdAt, row.attempts, row.nextAttemptAt, row.lastErrorCode, row.id)
    }

    fun delete(id: Long) {
        queries.outboxDelete(id)
    }

    fun countByState(): Map<OutboxState, Long> {
        val counts = OutboxState.entries.associateWith { 0L }.toMutableMap()
        for (row in queries.outboxCountByState().executeAsList()) counts[wireValueOf<OutboxState>(row.state)] = row.count
        return counts
    }

    fun all(): List<OutboxRow> = queries.outboxAll(::outboxRow).executeAsList()
}

class PinTable internal constructor(private val queries: LegacyQueries) {
    fun get(entryId: String): PinRow? = queries.pinGet(entryId, ::PinRow).executeAsOneOrNull()

    fun put(row: PinRow) {
        queries.pinPut(row.entryId, row.pinnedAt)
    }

    fun delete(entryId: String) {
        queries.pinDelete(entryId)
    }

    fun all(): List<PinRow> = queries.pinAll(::PinRow).executeAsList()
}

class RowStoreTx internal constructor(queries: LegacyQueries) {
    val collections = CollectionTable(queries)
    val entries = EntryTable(queries)
    val memberships = MembershipTable(queries)
    val media = MediaTable(queries)
    val objects = ObjectTable(queries)
    val outbox = OutboxTable(queries)
    val pins = PinTable(queries)
}
