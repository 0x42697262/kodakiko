import {
  NOTE_PREVIEW_CHARS,
  createGalleryIndex,
  localDateOf,
  type GalleryIndex,
  type IndexedEntry,
  MONTH_DAY_START,
  type Sodium,
} from '@kodakiko/core';
import { z } from 'zod';
import type { Clock } from '../../adapters/clock';
import type {
  EntryRow,
  MediaRow,
  MembershipRow,
  ObjectRow,
  RowStore,
  RowStoreTx,
} from '../../adapters/rowstore/rowstore';
import type { AppEventBus } from '../../composition/events';
import { TAG_SUGGESTIONS_MAX } from '../../constants/entry-constants';
import {
  INDEX_BUILD_YIELD_EVERY,
  INDEX_UPDATE_DEBOUNCE_MS,
  UNKNOWN_HOME_COLLECTION_ID,
} from '../../constants/gallery-constants';
import { STORAGE_KEYS } from '../../keys/storage-keys';
import { openEntryMetadata } from '../entries/entries-service';
import { AccountLockedError, type EntryKeys } from '../keys/entry-keys';
import type { TagSuggestionSource } from '../tags/tag-suggestions';

export type IndexBuildReport = {
  readonly entries: number;
  readonly unreadable: number;
  readonly durationMs: number;
};

export type IndexStatus = 'idle' | 'building' | 'ready';

export type IndexService = {
  readonly build: () => Promise<IndexBuildReport>;
  readonly index: () => GalleryIndex | null;
  readonly status: () => IndexStatus;
  readonly subscribe: (listener: () => void) => () => void;
  readonly clear: () => void;
};

export type IndexServiceDeps = {
  readonly sodium: Sodium;
  readonly rowStore: RowStore;
  readonly entryKeys: Pick<EntryKeys, 'entryKey'>;
  readonly events: AppEventBus;
  readonly clock: Clock;
  readonly account: () => { readonly userId: string } | null;
  readonly onBuilt?: (report: IndexBuildReport) => void;
};

export type IndexRows = {
  readonly entries: readonly EntryRow[];
  readonly memberships: readonly MembershipRow[];
  readonly media: readonly MediaRow[];
  readonly objects: readonly ObjectRow[];
};

type Metadata = ReturnType<typeof openEntryMetadata>;

export type MetadataOpener = (row: EntryRow) => Promise<Metadata>;

export type IndexedRowsCount = { readonly entries: number; readonly unreadable: number };

function groupBy<Row>(rows: readonly Row[], key: (row: Row) => string): Map<string, Row[]> {
  const groups = new Map<string, Row[]>();
  for (const row of rows) {
    const group = groups.get(key(row)) ?? [];
    group.push(row);
    groups.set(key(row), group);
  }
  return groups;
}

function yieldToEventLoop(): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, 0));
}

function notePreviewOf(note: string): string {
  return Array.from(note).slice(0, NOTE_PREVIEW_CHARS).join('');
}

function isPending(row: EntryRow, objects: readonly ObjectRow[]): boolean {
  return row.localState !== 'synced' || objects.some((object) => object.uploadState !== 'uploaded');
}

function placeholderEntry(
  row: EntryRow,
  collectionIds: readonly string[],
  isOwn: boolean,
  pending: boolean,
): IndexedEntry {
  const localDate = localDateOf(row.createdAt, 0);
  return {
    id: row.id,
    isOwn,
    collectionIds,
    trashed: row.trashedAt !== null,
    unreadable: true,
    takenAtUtc: row.createdAt,
    utcOffsetMinutes: 0,
    localDate,
    monthDay: monthDayOf(localDate),
    tags: [],
    coverMediaId: null,
    mediaCount: 0,
    hasVideo: false,
    notePreview: '',
    pending,
  };
}

function monthDayOf(localDate: string): string {
  return localDate.slice(MONTH_DAY_START);
}

function readableEntry(
  row: EntryRow,
  metadata: Metadata,
  collectionIds: readonly string[],
  isOwn: boolean,
  pending: boolean,
): IndexedEntry {
  const localDate = localDateOf(metadata.takenAtUtc, metadata.utcOffsetMinutes);
  return {
    id: row.id,
    isOwn,
    collectionIds,
    trashed: row.trashedAt !== null,
    unreadable: false,
    takenAtUtc: metadata.takenAtUtc,
    utcOffsetMinutes: metadata.utcOffsetMinutes,
    localDate,
    monthDay: monthDayOf(localDate),
    tags: metadata.tags,
    coverMediaId: metadata.media[0]?.mediaId ?? null,
    mediaCount: metadata.media.length,
    hasVideo: metadata.media.some((media) => media.kind === 'video'),
    favorite: metadata.favorite === true,
    notePreview: notePreviewOf(metadata.note),
    pending,
  };
}

export async function indexEntryRows(
  rows: IndexRows,
  userId: string,
  open: MetadataOpener,
  accept: (entry: IndexedEntry, noteText: string) => void,
): Promise<IndexedRowsCount> {
  const membershipsByEntry = groupBy(rows.memberships, (row) => row.entryId);
  const mediaByEntry = groupBy(rows.media, (row) => row.entryId);
  const objectsByMedia = groupBy(rows.objects, (row) => row.mediaId);
  let unreadable = 0;
  for (const [position, row] of rows.entries.entries()) {
    if (position > 0 && position % INDEX_BUILD_YIELD_EVERY === 0) await yieldToEventLoop();
    const collectionIds = (membershipsByEntry.get(row.id) ?? []).map((item) => item.collectionId);
    const objects = (mediaByEntry.get(row.id) ?? []).flatMap(
      (media) => objectsByMedia.get(media.id) ?? [],
    );
    const isOwn = row.ownerId === userId;
    const pending = isPending(row, objects);
    let metadata: Metadata | null = null;
    try {
      metadata = await open(row);
    } catch (error) {
      if (error instanceof AccountLockedError) throw error;
    }
    if (metadata === null) {
      unreadable += 1;
      accept(placeholderEntry(row, collectionIds, isOwn, pending), '');
    } else {
      accept(readableEntry(row, metadata, collectionIds, isOwn, pending), metadata.note);
    }
  }
  return { entries: rows.entries.length, unreadable };
}

async function readAllRows(tx: RowStoreTx): Promise<IndexRows> {
  const entries = await tx.entries.all();
  const memberships = await tx.memberships.all();
  const media = await tx.media.all();
  const objects = await tx.objects.all();
  return { entries, memberships, media, objects };
}

async function readRowsFor(tx: RowStoreTx, entryIds: readonly string[]) {
  const entries: EntryRow[] = [];
  const memberships: MembershipRow[] = [];
  const media: MediaRow[] = [];
  const objects: ObjectRow[] = [];
  const missing: string[] = [];
  for (const entryId of entryIds) {
    const row = await tx.entries.get(entryId);
    if (row === null) {
      missing.push(entryId);
      continue;
    }
    entries.push(row);
    memberships.push(...(await tx.memberships.byEntry(entryId)));
    const mediaRows = await tx.media.byEntry(entryId);
    media.push(...mediaRows);
    for (const mediaRow of mediaRows) objects.push(...(await tx.objects.byMedia(mediaRow.id)));
  }
  return { rows: { entries, memberships, media, objects }, missing };
}

export function createIndexService(deps: IndexServiceDeps): IndexService {
  const { sodium, rowStore, entryKeys } = deps;
  const listeners = new Set<() => void>();
  const pending = new Set<string>();
  let currentStatus: IndexStatus = 'idle';
  let current: GalleryIndex | null = null;
  let homeKnown = false;
  let generation = 0;
  let timer: ReturnType<typeof setTimeout> | null = null;
  let flushing: Promise<void> = Promise.resolve();

  function notify(): void {
    for (const listener of [...listeners]) listener();
  }

  function requireUserId(): string {
    const account = deps.account();
    if (account === null) throw new AccountLockedError();
    return account.userId;
  }

  async function resolveHome(userId: string): Promise<string | null> {
    const stored = await rowStore.kvGetJson(STORAGE_KEYS.accountHomeCollectionId, z.string());
    if (stored !== null) return stored;
    const collections = await rowStore.transaction((tx) => tx.collections.all());
    const home = collections.find(
      (collection) =>
        collection.kind === 'home' && collection.role === 'owner' && collection.ownerId === userId,
    );
    return home?.id ?? null;
  }

  const open: MetadataOpener = async (row) =>
    openEntryMetadata(sodium, await entryKeys.entryKey(row.id), row.id, row.metadataBlob);

  function schedule(): void {
    if (timer !== null) clearTimeout(timer);
    timer = setTimeout(() => {
      timer = null;
      const ids = [...pending];
      pending.clear();
      flushing = flushing.then(() => flush(ids)).catch(() => undefined);
    }, INDEX_UPDATE_DEBOUNCE_MS);
  }

  async function flush(entryIds: readonly string[]): Promise<void> {
    if (entryIds.length === 0) return;
    if (currentStatus !== 'ready' || current === null) {
      if (currentStatus === 'building') for (const id of entryIds) pending.add(id);
      return;
    }
    const started = generation;
    const userId = requireUserId();
    if (!homeKnown && (await resolveHome(userId)) !== null) {
      if (started === generation) await build();
      return;
    }
    const { rows, missing } = await rowStore.transaction((tx) => readRowsFor(tx, entryIds));
    const updates: { entry: IndexedEntry; noteText: string }[] = [];
    await indexEntryRows(rows, userId, open, (entry, noteText) => updates.push({ entry, noteText }));
    if (started !== generation || current === null) return;
    for (const entryId of missing) current.remove(entryId);
    for (const update of updates) current.upsert(update.entry, update.noteText);
    notify();
  }

  async function build(): Promise<IndexBuildReport> {
    generation += 1;
    const started = generation;
    const startedAt = deps.clock.now();
    currentStatus = 'building';
    current = null;
    notify();
    try {
      const userId = requireUserId();
      const home = await resolveHome(userId);
      const rows = await rowStore.transaction(readAllRows);
      const index = createGalleryIndex({ homeCollectionId: home ?? UNKNOWN_HOME_COLLECTION_ID });
      const counts = await indexEntryRows(rows, userId, open, (entry, noteText) =>
        index.upsert(entry, noteText),
      );
      const report = { ...counts, durationMs: deps.clock.now() - startedAt };
      if (started !== generation) return report;
      current = index;
      homeKnown = home !== null;
      currentStatus = 'ready';
      notify();
      deps.onBuilt?.(report);
      if (pending.size > 0) schedule();
      return report;
    } catch (error) {
      if (started === generation) {
        currentStatus = 'idle';
        notify();
      }
      throw error;
    }
  }

  function clear(): void {
    generation += 1;
    if (timer !== null) clearTimeout(timer);
    timer = null;
    pending.clear();
    current = null;
    homeKnown = false;
    currentStatus = 'idle';
    notify();
  }

  deps.events.on('entriesChanged', (entryIds) => {
    if (currentStatus === 'idle') return;
    for (const entryId of entryIds) pending.add(entryId);
    if (currentStatus === 'ready') schedule();
  });

  return {
    build,
    index: () => current,
    status: () => currentStatus,
    subscribe: (listener) => {
      listeners.add(listener);
      return () => {
        listeners.delete(listener);
      };
    },
    clear,
  };
}

export function createIndexTagSuggestionSource(indexService: IndexService): TagSuggestionSource {
  return {
    suggest: (prefix) => indexService.index()?.tagSuggestions(prefix, TAG_SUGGESTIONS_MAX) ?? [],
  };
}
