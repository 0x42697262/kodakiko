import { EntryMetadataV1 } from '@kodakiko/contract';
import {
  AD_CONTEXTS,
  InvalidEditError,
  KEY_BYTES,
  addTag,
  applyEntryEdit,
  buildNewEntryMetadata,
  bytesEqual,
  currentTimeZone,
  metadataEquivalent,
  normalizeTag,
  openJson,
  removeTag,
  sealJson,
  wipe,
  wrapKey,
  type EntryEdit,
  type Sodium,
} from '@kodakiko/core';
import { z } from 'zod';
import type { Clock } from '../../adapters/clock';
import type { FileStore } from '../../adapters/filestore/filestore';
import type {
  EntryRow,
  MediaRow,
  ObjectRow,
  RowStore,
  RowStoreTx,
} from '../../adapters/rowstore/rowstore';
import type { AppEventBus } from '../../composition/events';
import { CONFIG_FALLBACK } from '../../constants/auth-constants';
import { EDIT_RETRY_ATTEMPTS } from '../../constants/entry-constants';
import type { CaptureTrayStore } from '../../features/capture/capture-tray-store';
import { STORAGE_KEYS } from '../../keys/storage-keys';
import { EN } from '../../text/en';
import { AccountLockedError, type EntryKeys } from '../keys/entry-keys';
import type { ConfigService } from '../config/config-service';
import type { ImportedMedia } from '../media/import-service';
import { removeEntryRows, removeMediaRows, deleteFiles } from '../sync/local-delete';
import { entryIdOfPayload } from '../sync/outbox-rows';
import type { SyncService } from '../sync/sync-service';

type Metadata = z.infer<typeof EntryMetadataV1>;
type LocationV1 = NonNullable<Metadata['location']>;
export type MetadataEdit = Omit<EntryEdit, 'addMedia' | 'removeMediaId'>;

const METADATA_SCHEMA_VERSION = 1;
const NO_MIGRATIONS = new Map<number, (raw: Record<string, unknown>) => Record<string, unknown>>();
const FIRST_REVISION = 1;
const NEVER_POSTED_REVISION = 0;
const NOT_STARTED_SEQ = 0;

export class EmptyTrayError extends Error {
  constructor() {
    super(EN['entries.emptyTray']);
  }
}

export class ReadOnlyEntryError extends Error {
  constructor() {
    super(EN['entries.readOnly']);
  }
}

export class EntryNotFoundError extends Error {
  constructor() {
    super(EN['entries.notFound']);
  }
}

export class TrayChangedError extends Error {
  constructor() {
    super(EN['entries.trayChanged']);
  }
}

export class EntryChangedError extends Error {
  constructor() {
    super(EN['entries.changedElsewhere']);
  }
}

export class HomeCollectionMissingError extends Error {
  constructor() {
    super(EN['entries.homeMissing']);
  }
}

export type BulkResult = { readonly changed: number; readonly skipped: number };

export type CreateFromTrayInput = {
  readonly note: string;
  readonly tags: readonly string[];
  readonly time: { readonly takenAtUtc: string; readonly utcOffsetMinutes: number } | null;
  readonly location?: LocationV1 | null;
};

export type EntriesService = {
  readonly createFromTray: (input: CreateFromTrayInput) => Promise<string>;
  readonly edit: (entryId: string, edit: MetadataEdit) => Promise<void>;
  readonly addMedia: (entryId: string, items: readonly ImportedMedia[]) => Promise<void>;
  readonly removeMedia: (entryId: string, mediaId: string) => Promise<void>;
  readonly trash: (entryIds: readonly string[]) => Promise<BulkResult>;
  readonly restore: (entryIds: readonly string[]) => Promise<BulkResult>;
  readonly deletePermanently: (entryIds: readonly string[]) => Promise<BulkResult>;
  readonly addTagToMany: (entryIds: readonly string[], tag: string) => Promise<BulkResult>;
  readonly removeTagFromMany: (entryIds: readonly string[], tag: string) => Promise<BulkResult>;
  readonly readMetadata: (entryId: string) => Promise<Metadata>;
};

export type EntriesServiceDeps = {
  readonly sodium: Sodium;
  readonly rowStore: RowStore;
  readonly fileStore: FileStore;
  readonly entryKeys: EntryKeys;
  readonly captureTray: CaptureTrayStore;
  readonly config: Pick<ConfigService, 'get'>;
  readonly sync: Pick<SyncService, 'notifyLocalWrite' | 'isEntryInFlight'>;
  readonly events: AppEventBus;
  readonly clock: Clock;
  readonly randomId: () => string;
  readonly account: () => { readonly userId: string } | null;
};

type Mutation = 'changed' | 'unchanged' | 'skipped';

const STALE = Symbol('stale');

type Loaded = { readonly row: EntryRow; readonly entryKey: Uint8Array; readonly current: Metadata };

export function openEntryMetadata(
  sodium: Sodium,
  entryKey: Uint8Array,
  entryId: string,
  blob: Uint8Array,
): Metadata {
  return openJson(
    sodium,
    entryKey,
    blob,
    EntryMetadataV1,
    METADATA_SCHEMA_VERSION,
    NO_MIGRATIONS,
    AD_CONTEXTS.entryMetadata,
    entryId,
  );
}

function uniqueIds(entryIds: readonly string[]): string[] {
  return [...new Set(entryIds)];
}

export function createEntriesService(deps: EntriesServiceDeps): EntriesService {
  const { sodium, rowStore, fileStore, entryKeys } = deps;

  function requireUserId(): string {
    const account = deps.account();
    if (account === null) throw new AccountLockedError();
    return account.userId;
  }

  async function homeCollectionId(userId: string): Promise<string> {
    const stored = await rowStore.kvGetJson(STORAGE_KEYS.accountHomeCollectionId, z.string());
    if (stored !== null) return stored;
    const collections = await rowStore.transaction((tx) => tx.collections.all());
    const home = collections.find(
      (collection) =>
        collection.kind === 'home' && collection.role === 'owner' && collection.ownerId === userId,
    );
    if (home === undefined) throw new HomeCollectionMissingError();
    return home.id;
  }

  async function loadOwnedEntry(entryId: string, userId: string): Promise<EntryRow> {
    const row = await rowStore.transaction((tx) => tx.entries.get(entryId));
    if (row === null) throw new EntryNotFoundError();
    if (row.ownerId !== userId) throw new ReadOnlyEntryError();
    return row;
  }

  function openMetadata(entryKey: Uint8Array, entryId: string, blob: Uint8Array): Metadata {
    return openEntryMetadata(sodium, entryKey, entryId, blob);
  }

  function sealMetadata(entryKey: Uint8Array, entryId: string, metadata: Metadata): Uint8Array {
    return sealJson(sodium, entryKey, metadata, AD_CONTEXTS.entryMetadata, entryId);
  }

  async function readMetadata(entryId: string): Promise<Metadata> {
    const row = await rowStore.transaction((tx) => tx.entries.get(entryId));
    if (row === null) throw new EntryNotFoundError();
    const entryKey = await entryKeys.entryKey(entryId);
    return openMetadata(entryKey, entryId, row.metadataBlob);
  }

  function afterWrite(entryIds: readonly string[]): void {
    deps.sync.notifyLocalWrite();
    deps.events.emit('entriesChanged', entryIds);
  }

  function metadataRowFor(fresh: EntryRow, blob: Uint8Array, now: string): EntryRow {
    return {
      ...fresh,
      metadataBlob: blob,
      updatedAt: now,
      localState: fresh.localState === 'pending_create' ? 'pending_create' : 'pending_update',
    };
  }

  async function enqueue(tx: RowStoreTx, kind: string, payload: Record<string, string>) {
    await tx.outbox.enqueue({
      kind,
      payload,
      state: 'queued',
      createdAt: deps.clock.now(),
      attempts: 0,
      nextAttemptAt: 0,
      lastErrorCode: null,
    });
  }

  async function enqueueUpdateOnce(tx: RowStoreTx, entryId: string): Promise<void> {
    const queued = (await tx.outbox.all()).some(
      (item) =>
        item.kind === 'updateEntryMeta' &&
        item.state === 'queued' &&
        entryIdOfPayload(item.payload) === entryId,
    );
    if (!queued) await enqueue(tx, 'updateEntryMeta', { entryId });
  }

  async function loadForEdit(entryId: string, userId: string): Promise<Loaded> {
    const row = await loadOwnedEntry(entryId, userId);
    const entryKey = await entryKeys.entryKey(entryId);
    return { row, entryKey, current: openMetadata(entryKey, entryId, row.metadataBlob) };
  }

  async function writeIfUnchanged<Result>(
    loaded: Loaded,
    write: (tx: RowStoreTx, fresh: EntryRow) => Promise<Result>,
  ): Promise<Result | typeof STALE> {
    return rowStore.transaction(async (tx) => {
      const fresh = await tx.entries.get(loaded.row.id);
      if (fresh === null) throw new EntryNotFoundError();
      const unchanged =
        bytesEqual(fresh.metadataBlob, loaded.row.metadataBlob) &&
        fresh.baseRevision === loaded.row.baseRevision;
      return unchanged ? write(tx, fresh) : STALE;
    });
  }

  async function withRetries<Result>(
    attempt: () => Promise<Result | typeof STALE>,
  ): Promise<Result> {
    for (let round = 0; round < EDIT_RETRY_ATTEMPTS; round += 1) {
      const result = await attempt();
      if (result !== STALE) return result;
    }
    throw new EntryChangedError();
  }

  async function mutate(
    entryId: string,
    userId: string,
    build: (current: Metadata) => MetadataEdit | null,
  ): Promise<Mutation> {
    return withRetries(async () => {
      const loaded = await loadForEdit(entryId, userId);
      const edit = build(loaded.current);
      if (edit === null) return 'skipped';
      const now = deps.clock.nowIso();
      const next = applyEntryEdit(loaded.current, edit, now);
      if (metadataEquivalent(loaded.current, next)) return 'unchanged';
      const blob = sealMetadata(loaded.entryKey, entryId, next);
      return writeIfUnchanged(loaded, async (tx, fresh) => {
        await tx.entries.put(metadataRowFor(fresh, blob, now));
        await enqueueUpdateOnce(tx, entryId);
        return 'changed' as const;
      });
    });
  }

  function mediaRowsFor(
    entryId: string,
    entryKey: Uint8Array,
    items: readonly ImportedMedia[],
    now: number,
  ): { readonly media: MediaRow[]; readonly objects: ObjectRow[] } {
    const media: MediaRow[] = [];
    const objects: ObjectRow[] = [];
    for (const item of items) {
      media.push({
        id: item.mediaId,
        entryId,
        mediaKeyWrap: wrapKey(
          sodium,
          entryKey,
          item.mediaKey,
          AD_CONTEXTS.mediaKeyByEntry,
          `${entryId}/${item.mediaId}`,
        ),
      });
      objects.push(
        {
          id: item.originalObjectId,
          mediaId: item.mediaId,
          variant: 'original',
          sizeBytes: item.originalCiphertextBytes,
          plaintextSize: item.descriptor.originalPlaintextBytes,
          localState: 'present',
          uploadState: 'local',
          uploadJobId: null,
          lastAccessAt: now,
        },
        {
          id: item.thumbnailObjectId,
          mediaId: item.mediaId,
          variant: 'thumbnail',
          sizeBytes: item.thumbnailCiphertextBytes,
          plaintextSize: item.descriptor.thumbnail.plaintextBytes,
          localState: 'present',
          uploadState: 'local',
          uploadJobId: null,
          lastAccessAt: now,
        },
      );
    }
    return { media, objects };
  }

  function newEntryMetadata(input: CreateFromTrayInput, items: readonly ImportedMedia[], now: string) {
    const built = buildNewEntryMetadata({
      media: items.map((item) => item.descriptor),
      times: items.map((item) => item.time),
      note: input.note,
      tags: input.tags,
      mediaLocations: items.map((item) => item.location?.point ?? null),
      deviceLocation: deps.captureTray.deviceLocation,
      timeZone: currentTimeZone(),
      nowUtc: now,
    });
    return EntryMetadataV1.parse({
      ...built,
      ...(input.time === null
        ? {}
        : { takenAtUtc: input.time.takenAtUtc, utcOffsetMinutes: input.time.utcOffsetMinutes }),
      ...(input.location === undefined ? {} : { location: input.location }),
    });
  }

  async function restoreToTray(items: readonly ImportedMedia[], generation: number): Promise<void> {
    for (const item of items) await deps.captureTray.addImported(item, generation);
  }

  async function createFromTray(input: CreateFromTrayInput): Promise<string> {
    const userId = requireUserId();
    const tray = deps.captureTray;
    const generation = tray.generation;
    const items = tray.items;
    if (items.length === 0) throw new EmptyTrayError();
    const homeId = await homeCollectionId(userId);
    const collectionKey = await entryKeys.collectionKey(homeId);
    if (tray.generation !== generation || tray.items !== items) throw new TrayChangedError();
    const entryId = deps.randomId();
    const entryKey = sodium.randomBytes(KEY_BYTES);
    let taken: readonly ImportedMedia[] = [];
    try {
      const now = deps.clock.nowIso();
      const metadataBlob = sealMetadata(entryKey, entryId, newEntryMetadata(input, items, now));
      const entryKeyWrap = wrapKey(
        sodium,
        collectionKey,
        entryKey,
        AD_CONTEXTS.entryKeyByCollection,
        `${homeId}/${entryId}`,
      );
      const rows = mediaRowsFor(entryId, entryKey, items, deps.clock.now());
      taken = tray.take();
      await rowStore.transaction(async (tx) => {
        await tx.entries.put({
          id: entryId,
          ownerId: userId,
          purpose: 'journal',
          revision: FIRST_REVISION,
          metadataBlob,
          baseMetadataBlob: null,
          baseRevision: NEVER_POSTED_REVISION,
          serverPendingBlob: null,
          serverPendingRevision: null,
          trashedAt: null,
          createdAt: now,
          updatedAt: now,
          localState: 'pending_create',
        });
        await tx.memberships.put({
          collectionId: homeId,
          entryId,
          entryKeyWrap,
          seq: NOT_STARTED_SEQ,
        });
        for (const row of rows.media) await tx.media.put(row);
        for (const row of rows.objects) await tx.objects.put(row);
        await enqueue(tx, 'createEntry', { entryId });
      });
    } catch (error) {
      await restoreToTray(taken, generation);
      throw error;
    } finally {
      wipe(entryKey);
    }
    await tray.clear(false);
    for (const item of taken) wipe(item.mediaKey);
    afterWrite([entryId]);
    return entryId;
  }

  async function edit(entryId: string, change: MetadataEdit): Promise<void> {
    if ('addMedia' in change || 'removeMediaId' in change) {
      throw new InvalidEditError(EN['entries.mediaEditNotAllowed']);
    }
    const userId = requireUserId();
    const outcome = await mutate(entryId, userId, () => change);
    if (outcome === 'changed') afterWrite([entryId]);
  }

  async function addMedia(entryId: string, items: readonly ImportedMedia[]): Promise<void> {
    const userId = requireUserId();
    await loadOwnedEntry(entryId, userId);
    if (items.length === 0) throw new InvalidEditError(EN['entries.noMediaToAdd']);
    const limit = (deps.config.get() ?? CONFIG_FALLBACK).maxMediaPerEntry;
    await withRetries(async () => {
      const loaded = await loadForEdit(entryId, userId);
      if (loaded.current.media.length + items.length > limit) {
        throw new InvalidEditError(EN['entries.tooManyMedia'](limit));
      }
      const now = deps.clock.nowIso();
      const next = applyEntryEdit(
        loaded.current,
        { addMedia: items.map((item) => item.descriptor) },
        now,
      );
      const blob = sealMetadata(loaded.entryKey, entryId, next);
      const rows = mediaRowsFor(entryId, loaded.entryKey, items, deps.clock.now());
      return writeIfUnchanged(loaded, async (tx, fresh) => {
        await tx.entries.put(metadataRowFor(fresh, blob, now));
        for (const media of rows.media) await tx.media.put(media);
        for (const object of rows.objects) await tx.objects.put(object);
        for (const item of items) {
          await enqueue(tx, 'addMedia', { entryId, mediaId: item.mediaId });
        }
        await enqueue(tx, 'updateEntryMeta', { entryId });
        return true;
      });
    });
    for (const item of items) wipe(item.mediaKey);
    afterWrite([entryId]);
  }

  async function removeMedia(entryId: string, mediaId: string): Promise<void> {
    const userId = requireUserId();
    const objectIds = await withRetries(async () => {
      const loaded = await loadForEdit(entryId, userId);
      const now = deps.clock.nowIso();
      const next = applyEntryEdit(loaded.current, { removeMediaId: mediaId }, now);
      const blob = sealMetadata(loaded.entryKey, entryId, next);
      return writeIfUnchanged(loaded, async (tx, fresh) => {
        await tx.entries.put(metadataRowFor(fresh, blob, now));
        await enqueue(tx, 'updateEntryMeta', { entryId });
        await enqueue(tx, 'removeMedia', { entryId, mediaId });
        return removeMediaRows(tx, mediaId);
      });
    });
    entryKeys.forget(entryId, mediaId);
    await deleteFiles(fileStore, objectIds);
    await fileStore.deletePlainThumbnail(mediaId);
    afterWrite([entryId]);
  }

  async function setTrashed(entryIds: readonly string[], trashed: boolean): Promise<BulkResult> {
    const userId = requireUserId();
    const ids = uniqueIds(entryIds);
    const now = deps.clock.nowIso();
    const sameKind = trashed ? 'trashEntry' : 'restoreEntry';
    const oppositeKind = trashed ? 'restoreEntry' : 'trashEntry';
    const changed = await rowStore.transaction(async (tx) => {
      const done: string[] = [];
      for (const entryId of ids) {
        const row = await tx.entries.get(entryId);
        if (row === null || row.ownerId !== userId) continue;
        if ((row.trashedAt !== null) === trashed) continue;
        await tx.entries.put({ ...row, trashedAt: trashed ? now : null });
        const cancellable = deps.sync.isEntryInFlight(entryId)
          ? undefined
          : (await tx.outbox.all()).find(
              (item) =>
                item.kind === oppositeKind &&
                item.state === 'queued' &&
                item.attempts === 0 &&
                entryIdOfPayload(item.payload) === entryId,
            );
        if (cancellable === undefined) await enqueue(tx, sameKind, { entryId });
        else await tx.outbox.delete(cancellable.id);
        done.push(entryId);
      }
      return done;
    });
    if (changed.length > 0) afterWrite(changed);
    return { changed: changed.length, skipped: ids.length - changed.length };
  }

  async function serverMayKnow(tx: RowStoreTx, row: EntryRow): Promise<boolean> {
    if ((row.baseRevision ?? NEVER_POSTED_REVISION) >= FIRST_REVISION) return true;
    if (deps.sync.isEntryInFlight(row.id)) return true;
    return (await tx.outbox.all()).some(
      (item) =>
        item.kind === 'createEntry' && item.attempts > 0 && entryIdOfPayload(item.payload) === row.id,
    );
  }

  async function deletePermanently(entryIds: readonly string[]): Promise<BulkResult> {
    const userId = requireUserId();
    const ids = uniqueIds(entryIds);
    const removed = await rowStore.transaction(async (tx) => {
      const objectIds: string[] = [];
      const mediaIds: string[] = [];
      const done: string[] = [];
      for (const entryId of ids) {
        const row = await tx.entries.get(entryId);
        if (row === null || row.ownerId !== userId) continue;
        const queueDelete = await serverMayKnow(tx, row);
        mediaIds.push(...(await tx.media.byEntry(entryId)).map((media) => media.id));
        objectIds.push(...(await removeEntryRows(tx, entryId)));
        if (queueDelete) await enqueue(tx, 'deleteEntry', { entryId });
        done.push(entryId);
      }
      return { objectIds, mediaIds, done };
    });
    for (const entryId of removed.done) entryKeys.forget(entryId);
    await deleteFiles(fileStore, removed.objectIds);
    for (const mediaId of removed.mediaIds) await fileStore.deletePlainThumbnail(mediaId);
    if (removed.done.length > 0) afterWrite(removed.done);
    return { changed: removed.done.length, skipped: ids.length - removed.done.length };
  }

  async function bulkTags(
    entryIds: readonly string[],
    build: (tags: readonly string[]) => readonly string[] | null,
  ): Promise<BulkResult> {
    const userId = requireUserId();
    const changedIds: string[] = [];
    let skipped = 0;
    for (const entryId of uniqueIds(entryIds)) {
      try {
        const outcome = await mutate(entryId, userId, (current) => {
          const tags = build(current.tags);
          return tags === null ? null : { tags };
        });
        if (outcome === 'changed') changedIds.push(entryId);
        else skipped += 1;
      } catch (error) {
        if (!(error instanceof ReadOnlyEntryError || error instanceof EntryNotFoundError)) {
          throw error;
        }
        skipped += 1;
      }
    }
    if (changedIds.length > 0) afterWrite(changedIds);
    return { changed: changedIds.length, skipped };
  }

  return {
    createFromTray,
    edit,
    addMedia,
    removeMedia,
    trash: (entryIds) => setTrashed(entryIds, true),
    restore: (entryIds) => setTrashed(entryIds, false),
    deletePermanently,
    addTagToMany: (entryIds, tag) =>
      bulkTags(entryIds, (tags) => {
        const result = addTag(tags, tag);
        return result.rejected === null ? result.tags : null;
      }),
    removeTagFromMany: (entryIds, tag) =>
      bulkTags(entryIds, (tags) => {
        const normalized = normalizeTag(tag);
        if (normalized === null || !tags.includes(normalized)) return null;
        return removeTag(tags, normalized);
      }),
    readMetadata,
  };
}
