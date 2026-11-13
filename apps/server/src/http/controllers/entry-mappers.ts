import {
  decodeBytes,
  encodeBytes,
  type CollectionDto,
  type CollectionListResponse,
  type DiffPage,
  type EntryCreateRequest,
  type EntryDto,
  type MediaDto,
  type SyncStateResponse,
} from '@kodakiko/contract';
import type { ReadableCollection } from '../../adapters/db/collections-repository.ts';
import type { EntryRow, MediaRow } from '../../adapters/db/entries-repository.ts';
import type { CreateEntryCommand } from '../../services/entries-service.ts';
import type { CollectionPage } from '../../services/collections-service.ts';
import type { DiffView, SyncStateView } from '../../services/sync-service.ts';

export function bufferOf(encoded: string): Buffer {
  return Buffer.from(decodeBytes(encoded as Parameters<typeof decodeBytes>[0]));
}

export function toCreateEntryCommand(body: EntryCreateRequest): CreateEntryCommand {
  return {
    id: body.id,
    purpose: body.purpose,
    metadataBlob: bufferOf(body.metadataBlob),
    memberships: body.memberships.map((membership) => ({
      collectionId: membership.collectionId,
      entryKeyByCollection: bufferOf(membership.entryKeyByCollection),
    })),
    media: body.media.map((item) => ({
      mediaId: item.mediaId,
      mediaKeyByEntry: bufferOf(item.mediaKeyByEntry),
    })),
  };
}

export function toEntryDto(row: EntryRow): EntryDto {
  return {
    id: row.id,
    ownerId: row.ownerId,
    purpose: row.purpose,
    revision: row.revision,
    metadataBlob: encodeBytes(row.metadataBlob),
    trashedAt: row.trashedAt === null ? null : row.trashedAt.toISOString(),
    createdAt: row.createdAt.toISOString(),
    updatedAt: row.updatedAt.toISOString(),
  } as EntryDto;
}

export function toMediaDto(row: MediaRow): MediaDto {
  return {
    id: row.id,
    entryId: row.entryId,
    mediaKeyByEntry: encodeBytes(row.mediaKeyByEntry),
    createdAt: row.createdAt.toISOString(),
  } as MediaDto;
}

export function toCollectionDto(row: ReadableCollection): CollectionDto {
  return {
    id: row.id,
    kind: row.kind,
    role: row.role,
    ownerId: row.ownerId,
    ownerUsername: row.ownerUsername,
    keyMaterial: encodeBytes(row.keyMaterial),
    metadataBlob: encodeBytes(row.metadataBlob),
    lastSeq: row.lastSeq,
    createdAt: row.createdAt.toISOString(),
    metaUpdatedAt: row.metaUpdatedAt.toISOString(),
    recipientCount: row.recipientCount,
  } as CollectionDto;
}

export function toCollectionListResponse(page: CollectionPage): CollectionListResponse {
  return { items: page.items.map(toCollectionDto), nextCursor: page.nextCursor };
}

export function toDiffPage(view: DiffView): DiffPage {
  return {
    collectionId: view.collectionId,
    memberships: view.memberships.map((membership) => ({
      entryId: membership.entryId,
      entryKeyByCollection:
        membership.entryKeyByCollection === null
          ? null
          : encodeBytes(membership.entryKeyByCollection),
      seq: membership.seq,
      removed: membership.removed,
    })),
    entries: view.entries.map(toEntryDto),
    media: view.media.map(toMediaDto),
    objects: view.objects.map((row) => ({
      id: row.id,
      mediaId: row.mediaId,
      variant: row.variant,
      sizeBytes: row.sizeBytes,
      state: row.state,
    })),
    nextCursor: view.nextCursor,
    hasMore: view.hasMore,
    resyncRequired: view.resyncRequired,
  } as DiffPage;
}

export function toSyncStateResponse(view: SyncStateView): SyncStateResponse {
  return {
    collections: view.map((row) => ({
      id: row.id,
      kind: row.kind,
      role: row.role,
      lastSeq: row.lastSeq,
      tombstonesPrunedThroughSeq: row.tombstonesPrunedThroughSeq,
      metaUpdatedAt: row.metaUpdatedAt.toISOString(),
    })),
  } as SyncStateResponse;
}
