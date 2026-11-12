import {
  decodeBytes,
  type CollectionDto,
  type DiffPage,
  type EntryDto,
  type MediaDto,
  type ObjectDto,
} from '@kodakiko/contract';
import {
  PULL_PAGE_LIMIT,
  STREAM_CHUNK_SIZE_EXPONENT,
  decidePulledEntry,
  layoutFromCiphertextSize,
  planCollections,
  type RemoteCollectionState,
} from '@kodakiko/core';
import type { FileStore } from '../../adapters/filestore/filestore';
import type { HttpClient } from '../../adapters/http/http';
import type { Logger } from '../../adapters/logger';
import type { EntryRow, ObjectRow, RowStore, RowStoreTx } from '../../adapters/rowstore/rowstore';
import type { AppEventBus } from '../../composition/events';
import { SYNC_LOG_EVENTS } from '../../keys/sync-keys';
import { EN } from '../../text/en';
import { entryIdOfPayload } from './outbox-rows';
import { deleteFiles, removeEntryRows, removeMediaRows } from './local-delete';
import { SYNC_CALLS } from './sync-calls';

const MAX_RESYNC_RESTARTS = 3;

export type PullResult = { readonly pulledEntries: number };

export type PullDeps = {
  readonly http: HttpClient;
  readonly rowStore: RowStore;
  readonly fileStore: FileStore;
  readonly events: AppEventBus;
  readonly log: Logger;
};

export type Pull = { readonly run: () => Promise<PullResult> };

type PageOutcome = { readonly changed: ReadonlySet<string>; readonly files: readonly string[]; readonly pulled: number };

function plaintextSizeOf(sizeBytes: number): number {
  try {
    return layoutFromCiphertextSize(sizeBytes, STREAM_CHUNK_SIZE_EXPONENT).plaintextSize;
  } catch {
    return 0;
  }
}

function groupBy<Item>(items: readonly Item[], keyOf: (item: Item) => string): Map<string, Item[]> {
  const groups = new Map<string, Item[]>();
  for (const item of items) {
    const key = keyOf(item);
    groups.set(key, [...(groups.get(key) ?? []), item]);
  }
  return groups;
}

export function createPull(deps: PullDeps): Pull {
  const { http, rowStore, fileStore, events, log } = deps;

  async function finish(changed: ReadonlySet<string>, files: readonly string[]): Promise<void> {
    await deleteFiles(fileStore, files);
    if (changed.size > 0) events.emit('entriesChanged', [...changed]);
  }

  async function dropCollection(collectionId: string): Promise<void> {
    const changed = new Set<string>();
    const files: string[] = [];
    await rowStore.transaction(async (tx) => {
      for (const membership of await tx.memberships.byCollection(collectionId)) {
        await tx.memberships.delete([membership.collectionId, membership.entryId]);
        changed.add(membership.entryId);
        if ((await tx.memberships.byEntry(membership.entryId)).length === 0) {
          files.push(...(await removeEntryRows(tx, membership.entryId)));
        }
      }
      await tx.collections.delete(collectionId);
    });
    await finish(changed, files);
  }

  async function refetchCollection(collectionId: string): Promise<void> {
    const dto: CollectionDto = await http.request(SYNC_CALLS.collection(collectionId));
    const keyMaterial = decodeBytes(dto.keyMaterial);
    const metadataBlob = decodeBytes(dto.metadataBlob);
    await rowStore.transaction(async (tx) => {
      const existing = await tx.collections.get(dto.id);
      await tx.collections.put({
        id: dto.id,
        kind: dto.kind,
        ownerId: dto.ownerId,
        ownerUsername: dto.ownerUsername,
        role: dto.role,
        keyMaterial,
        metadataBlob,
        metaUpdatedAt: dto.metaUpdatedAt,
        serverLastSeq: dto.lastSeq,
        cursorSeq: existing?.cursorSeq ?? 0,
      });
    });
  }

  async function wipeMemberships(collectionId: string): Promise<void> {
    const changed = new Set<string>();
    const files: string[] = [];
    await rowStore.transaction(async (tx) => {
      const queuedEntryIds = new Set(
        (await tx.outbox.all()).flatMap((row) => {
          const entryId = entryIdOfPayload(row.payload);
          return entryId === null ? [] : [entryId];
        }),
      );
      for (const membership of await tx.memberships.byCollection(collectionId)) {
        const entry = await tx.entries.get(membership.entryId);
        if (entry?.localState === 'pending_create') continue;
        await tx.memberships.delete([membership.collectionId, membership.entryId]);
        const keep =
          entry === null ||
          entry.localState !== 'synced' ||
          queuedEntryIds.has(membership.entryId) ||
          (await tx.memberships.byEntry(membership.entryId)).length > 0;
        if (keep) continue;
        files.push(...(await removeEntryRows(tx, membership.entryId)));
        changed.add(membership.entryId);
      }
      const collection = await tx.collections.get(collectionId);
      if (collection !== null) await tx.collections.put({ ...collection, cursorSeq: 0 });
    });
    await finish(changed, files);
  }

  function applyEntry(
    tx: RowStoreTx,
    local: EntryRow | null,
    dto: EntryDto,
    blob: Uint8Array,
  ): Promise<'applied' | 'rejected'> {
    const decision = decidePulledEntry(
      local === null ? null : { localState: local.localState, baseRevision: local.baseRevision ?? 0 },
      dto.revision,
    );
    if (decision === 'rejectRollback') {
      log.warn(SYNC_LOG_EVENTS.rollbackRejected, {
        entryId: dto.id,
        pulledRevision: dto.revision,
        baseRevision: local?.baseRevision ?? 0,
      });
      return Promise.resolve('rejected');
    }
    const base = {
      ownerId: dto.ownerId,
      purpose: dto.purpose,
      createdAt: dto.createdAt,
      updatedAt: dto.updatedAt,
      trashedAt: dto.trashedAt,
    };
    const row: EntryRow =
      decision === 'updateTrashOnly' && local !== null
        ? { ...local, trashedAt: dto.trashedAt, serverPendingBlob: blob, serverPendingRevision: dto.revision }
        : {
            id: dto.id,
            ...base,
            revision: dto.revision,
            metadataBlob: blob,
            baseMetadataBlob: blob,
            baseRevision: dto.revision,
            serverPendingBlob: null,
            serverPendingRevision: null,
            localState: 'synced',
          };
    return tx.entries.put(row).then(() => 'applied');
  }

  async function syncMedia(
    tx: RowStoreTx,
    entryId: string,
    serverMedia: readonly MediaDto[],
    files: string[],
  ): Promise<void> {
    const serverIds = new Set<string>(serverMedia.map((media) => media.id));
    for (const media of await tx.media.byEntry(entryId)) {
      if (serverIds.has(media.id)) continue;
      const objects = await tx.objects.byMedia(media.id);
      if (objects.every((object) => object.uploadState === 'uploaded')) {
        files.push(...(await removeMediaRows(tx, media.id)));
      }
    }
    for (const media of serverMedia) {
      await tx.media.put({
        id: media.id,
        entryId: media.entryId,
        mediaKeyWrap: decodeBytes(media.mediaKeyByEntry),
      });
    }
  }

  async function syncObjects(
    tx: RowStoreTx,
    objects: readonly ObjectDto[],
    files: string[],
  ): Promise<void> {
    for (const dto of objects) {
      const existing = await tx.objects.get(dto.id);
      const row: ObjectRow = {
        id: dto.id,
        mediaId: dto.mediaId,
        variant: dto.variant,
        sizeBytes: dto.sizeBytes,
        plaintextSize: plaintextSizeOf(dto.sizeBytes),
        localState: existing?.localState ?? 'missing',
        uploadState: 'uploaded',
        uploadJobId: existing?.uploadJobId ?? null,
        lastAccessAt: existing?.lastAccessAt ?? 0,
      };
      await tx.objects.put(row);
      for (const older of await tx.objects.byMedia(dto.mediaId)) {
        if (older.variant === dto.variant && older.id !== dto.id) {
          await tx.objects.delete(older.id);
          files.push(older.id);
        }
      }
    }
  }

  async function applyPage(
    collectionId: string,
    page: DiffPage,
    serverLastSeq: number,
  ): Promise<PageOutcome> {
    const keyWraps = new Map(
      page.memberships.map((membership) => [
        membership.entryId,
        membership.entryKeyByCollection === null ? null : decodeBytes(membership.entryKeyByCollection),
      ]),
    );
    const blobs = new Map(page.entries.map((entry) => [entry.id, decodeBytes(entry.metadataBlob)]));
    const mediaByEntry = groupBy(page.media, (media) => media.entryId);
    const objectsByEntry = groupBy(page.objects, (object) => {
      const owner = page.media.find((media) => media.id === object.mediaId);
      return owner?.entryId ?? '';
    });
    const changed = new Set<string>();
    const files: string[] = [];
    let pulled = 0;
    await rowStore.transaction(async (tx) => {
      for (const membership of page.memberships) {
        const wrap = keyWraps.get(membership.entryId);
        if (membership.removed || wrap === null || wrap === undefined) {
          await tx.memberships.delete([collectionId, membership.entryId]);
          changed.add(membership.entryId);
          if ((await tx.memberships.byEntry(membership.entryId)).length === 0) {
            files.push(...(await removeEntryRows(tx, membership.entryId)));
          }
        } else {
          await tx.memberships.put({
            collectionId,
            entryId: membership.entryId,
            entryKeyWrap: wrap,
            seq: membership.seq,
          });
        }
      }
      for (const dto of page.entries) {
        const blob = blobs.get(dto.id);
        if (blob === undefined) continue;
        const outcome = await applyEntry(tx, await tx.entries.get(dto.id), dto, blob);
        if (outcome === 'rejected') continue;
        pulled += 1;
        changed.add(dto.id);
        await syncMedia(tx, dto.id, mediaByEntry.get(dto.id) ?? [], files);
        await syncObjects(tx, objectsByEntry.get(dto.id) ?? [], files);
      }
      const collection = await tx.collections.get(collectionId);
      if (collection !== null) {
        await tx.collections.put({ ...collection, cursorSeq: page.nextCursor, serverLastSeq });
      }
    });
    await finish(changed, files);
    return { changed, files, pulled };
  }

  async function pullCollection(
    collectionId: string,
    resync: boolean,
    serverLastSeq: number,
  ): Promise<number> {
    let cursor = 0;
    if (resync) {
      await wipeMemberships(collectionId);
    } else {
      cursor = (await rowStore.transaction((tx) => tx.collections.get(collectionId)))?.cursorSeq ?? 0;
    }
    let pulled = 0;
    let restarts = 0;
    for (;;) {
      const page = await http.request(SYNC_CALLS.diff(collectionId, cursor, PULL_PAGE_LIMIT));
      if (page.resyncRequired) {
        restarts += 1;
        if (restarts > MAX_RESYNC_RESTARTS) throw new Error(EN['sync.resyncLoop']);
        await wipeMemberships(collectionId);
        cursor = 0;
        continue;
      }
      pulled += (await applyPage(collectionId, page, serverLastSeq)).pulled;
      cursor = page.nextCursor;
      if (!page.hasMore) break;
    }
    return pulled;
  }

  async function run(): Promise<PullResult> {
    const state = await http.request(SYNC_CALLS.syncState());
    const local = await rowStore.transaction((tx) => tx.collections.all());
    const remote: RemoteCollectionState[] = state.collections;
    const plan = planCollections(
      remote,
      local.map((row) => ({ id: row.id, cursorSeq: row.cursorSeq, metaUpdatedAt: row.metaUpdatedAt })),
    );
    for (const collectionId of plan.drop) await dropCollection(collectionId);
    for (const collectionId of plan.refetch) await refetchCollection(collectionId);
    const lastSeqOf = (collectionId: string) =>
      remote.find((collection) => collection.id === collectionId)?.lastSeq ?? 0;
    let pulledEntries = 0;
    for (const collectionId of plan.resync) {
      pulledEntries += await pullCollection(collectionId, true, lastSeqOf(collectionId));
    }
    for (const collectionId of plan.pull) {
      pulledEntries += await pullCollection(collectionId, false, lastSeqOf(collectionId));
    }
    return { pulledEntries };
  }

  return { run };
}
