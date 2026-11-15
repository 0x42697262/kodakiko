package dev.kodakiko.service.sync

import dev.kodakiko.data.db.CollectionKind
import dev.kodakiko.data.db.CollectionRole
import dev.kodakiko.data.db.EntryLocalState
import dev.kodakiko.data.db.EntryPurpose
import dev.kodakiko.data.db.ObjectVariant
import dev.kodakiko.contract.CollectionKind as WireCollectionKind
import dev.kodakiko.contract.CollectionRole as WireCollectionRole
import dev.kodakiko.contract.EntryPurpose as WireEntryPurpose
import dev.kodakiko.contract.ObjectVariant as WireObjectVariant
import dev.kodakiko.sync.EntryLocalState as PlanEntryLocalState

internal fun WireCollectionKind.toRow(): CollectionKind = when (this) {
    WireCollectionKind.HOME -> CollectionKind.HOME
    WireCollectionKind.SHARED -> CollectionKind.SHARED
    WireCollectionKind.LINK -> CollectionKind.LINK
}

internal fun WireCollectionRole.toRow(): CollectionRole = when (this) {
    WireCollectionRole.OWNER -> CollectionRole.OWNER
    WireCollectionRole.VIEWER -> CollectionRole.VIEWER
}

internal fun WireEntryPurpose.toRow(): EntryPurpose = when (this) {
    WireEntryPurpose.JOURNAL -> EntryPurpose.JOURNAL
    WireEntryPurpose.LINK_COPY -> EntryPurpose.LINK_COPY
}

internal fun EntryPurpose.toWire(): WireEntryPurpose = when (this) {
    EntryPurpose.JOURNAL -> WireEntryPurpose.JOURNAL
    EntryPurpose.LINK_COPY -> WireEntryPurpose.LINK_COPY
}

internal fun WireObjectVariant.toRow(): ObjectVariant = when (this) {
    WireObjectVariant.ORIGINAL -> ObjectVariant.ORIGINAL
    WireObjectVariant.THUMBNAIL -> ObjectVariant.THUMBNAIL
}

internal fun ObjectVariant.toWire(): WireObjectVariant = when (this) {
    ObjectVariant.ORIGINAL -> WireObjectVariant.ORIGINAL
    ObjectVariant.THUMBNAIL -> WireObjectVariant.THUMBNAIL
}

internal fun EntryLocalState.toPlan(): PlanEntryLocalState = when (this) {
    EntryLocalState.SYNCED -> PlanEntryLocalState.SYNCED
    EntryLocalState.PENDING_CREATE -> PlanEntryLocalState.PENDING_CREATE
    EntryLocalState.PENDING_UPDATE -> PlanEntryLocalState.PENDING_UPDATE
}
