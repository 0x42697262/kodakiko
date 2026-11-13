import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { Sodium } from '@kodakiko/core';
import { createEventBus, type AppEvents, type AppEventBus } from '../src/composition/events';
import { INDEX_UPDATE_DEBOUNCE_MS } from '../src/constants/gallery-constants';
import { STORAGE_KEYS } from '../src/keys/storage-keys';
import {
  createIndexService,
  createIndexTagSuggestionSource,
  type IndexBuildReport,
  type IndexService,
} from '../src/services/index/index-service';
import { createEntryKeys } from '../src/services/keys/entry-keys';
import { TAG_SUGGESTIONS_MAX } from '../src/constants/entry-constants';
import {
  createAccountFixture,
  createLocalEntry,
  createMemoryFileStore,
  createMemoryRowStore,
  createNodeSodium,
  sealEntryMetadata,
  type AccountFixture,
  type LocalEntry,
} from './support';

const HOME_SCOPE = { kind: 'home' } as const;
const JOURNAL = {
  scope: HOME_SCOPE,
  view: 'journal',
  tags: [],
  fromDate: null,
  toDate: null,
  text: '',
} as const;
const WAIT_MS = 2_000;

let sodium: Sodium;
let fixture: AccountFixture;
let events: AppEventBus;
let service: IndexService;
let reports: IndexBuildReport[];
let entries: LocalEntry[];
let locked: boolean;

function bytesOf(size: number, fill: number): Uint8Array {
  return new Uint8Array(size).fill(fill);
}

async function tamper(entryId: string): Promise<void> {
  await fixture.rowStore.transaction(async (tx) => {
    const row = await tx.entries.get(entryId);
    if (row === null) throw new Error('missing entry');
    const blob = row.metadataBlob.slice();
    blob[blob.length - 1] = (blob[blob.length - 1] ?? 0) ^ 1;
    await tx.entries.put({ ...row, metadataBlob: blob });
  });
}

function journalIds(): string[] {
  return (service.index()?.query(JOURNAL) ?? []).flatMap((section) => [...section.entryIds]);
}

beforeEach(async () => {
  sodium ??= await createNodeSodium();
  const rowStore = createMemoryRowStore();
  fixture = await createAccountFixture(sodium, rowStore, createMemoryFileStore());
  await rowStore.kvSetJson(STORAGE_KEYS.accountHomeCollectionId, fixture.homeCollectionId);
  locked = false;
  events = createEventBus<AppEvents>();
  reports = [];
  const entryKeys = createEntryKeys({
    sodium,
    rowStore,
    account: () => (locked ? null : fixture.keys),
  });
  service = createIndexService({
    sodium,
    rowStore,
    entryKeys,
    events,
    clock: { now: () => Date.now(), nowIso: () => new Date().toISOString() },
    account: () => (locked ? null : { userId: fixture.userId }),
    onBuilt: (report) => reports.push(report),
  });
  entries = [];
  for (const [position, tags] of [['beach'], ['beach', 'lake'], ['city']].entries()) {
    entries.push(
      await createLocalEntry(fixture, {
        mediaBytes: [bytesOf(32, position + 1)],
        note: `note number ${position}`,
        tags,
      }),
    );
  }
});

describe('index service: build', () => {
  it('builds over 3 entries and reports one tampered blob as unreadable', async () => {
    await tamper(entries[2]?.entryId ?? '');
    expect(service.status()).toBe('idle');
    expect(service.index()).toBeNull();

    const building = service.build();
    expect(service.status()).toBe('building');
    const report = await building;

    expect(report.entries).toBe(3);
    expect(report.unreadable).toBe(1);
    expect(report.durationMs).toBeGreaterThanOrEqual(0);
    expect(reports).toEqual([report]);
    expect(service.status()).toBe('ready');
    const index = service.index();
    expect(index?.size()).toBe(3);
    const unreadable = index?.get(entries[2]?.entryId ?? '');
    expect(unreadable?.unreadable).toBe(true);
    expect(unreadable?.tags).toEqual([]);
    const readable = index?.get(entries[1]?.entryId ?? '');
    expect(readable).toMatchObject({
      unreadable: false,
      isOwn: true,
      tags: ['beach', 'lake'],
      collectionIds: [fixture.homeCollectionId],
      coverMediaId: entries[1]?.mediaIds[0],
      mediaCount: 1,
      hasVideo: false,
      notePreview: 'note number 1',
      pending: true,
      localDate: '2026-11-12',
      monthDay: '11-12',
    });
    expect(journalIds()).toHaveLength(3);
    expect(index?.query({ ...JOURNAL, tags: ['beach'] }).flatMap((section) => section.entryIds)).toHaveLength(2);
  });

  it('marks an entry not pending once it is synced and every object is uploaded', async () => {
    const target = entries[0]?.entryId ?? '';
    await fixture.rowStore.transaction(async (tx) => {
      const row = await tx.entries.get(target);
      if (row !== null) await tx.entries.put({ ...row, localState: 'synced' });
      for (const mediaId of entries[0]?.mediaIds ?? []) {
        for (const object of await tx.objects.byMedia(mediaId)) {
          await tx.objects.put({ ...object, uploadState: 'uploaded' });
        }
      }
    });
    await service.build();
    expect(service.index()?.get(target)?.pending).toBe(false);
    expect(service.index()?.get(entries[1]?.entryId ?? '')?.pending).toBe(true);
  });

  it('notifies subscribers on status changes', async () => {
    const statuses: string[] = [];
    const unsubscribe = service.subscribe(() => statuses.push(service.status()));
    await service.build();
    unsubscribe();
    expect(statuses).toContain('building');
    expect(statuses[statuses.length - 1]).toBe('ready');
  });
});

describe('index service: incremental updates', () => {
  it('updates the tags of an edited entry within the debounce', async () => {
    await service.build();
    const target = entries[0];
    if (target === undefined) throw new Error('missing entry');
    const blob = sealEntryMetadata(fixture, target.entryKey, target.entryId, {
      ...target.metadata,
      tags: ['forest'],
      note: 'walk in the forest',
    });
    await fixture.rowStore.transaction(async (tx) => {
      const row = await tx.entries.get(target.entryId);
      if (row !== null) await tx.entries.put({ ...row, metadataBlob: blob });
    });
    const before = service.index()?.version() ?? 0;

    const emittedAt = Date.now();
    events.emit('entriesChanged', [target.entryId]);
    expect(service.index()?.get(target.entryId)?.tags).toEqual(['beach']);

    await vi.waitFor(() => expect(service.index()?.get(target.entryId)?.tags).toEqual(['forest']), {
      timeout: WAIT_MS,
      interval: 5,
    });
    expect(Date.now() - emittedAt).toBeGreaterThanOrEqual(INDEX_UPDATE_DEBOUNCE_MS - 5);
    expect(service.index()?.version()).toBeGreaterThan(before);
    expect(service.index()?.query({ ...JOURNAL, text: 'fore' }).flatMap((s) => s.entryIds)).toEqual([
      target.entryId,
    ]);
    expect(service.index()?.query({ ...JOURNAL, tags: ['beach'] }).flatMap((s) => s.entryIds)).toEqual([
      entries[1]?.entryId,
    ]);
  });

  it('removes an entry whose row is gone', async () => {
    await service.build();
    const target = entries[1]?.entryId ?? '';
    await fixture.rowStore.transaction((tx) => tx.entries.delete(target));

    events.emit('entriesChanged', [target]);

    await vi.waitFor(() => expect(service.index()?.get(target)).toBeNull(), {
      timeout: WAIT_MS,
      interval: 5,
    });
    expect(service.index()?.size()).toBe(2);
  });

  it('adds a newly created entry', async () => {
    await service.build();
    const created = await createLocalEntry(fixture, {
      mediaBytes: [bytesOf(16, 9)],
      note: 'new one',
      tags: ['fresh'],
    });
    events.emit('entriesChanged', [created.entryId]);
    await vi.waitFor(() => expect(service.index()?.get(created.entryId)?.tags).toEqual(['fresh']), {
      timeout: WAIT_MS,
      interval: 5,
    });
  });

  it('applies changes emitted while building once the build finishes', async () => {
    const building = service.build();
    const created = await createLocalEntry(fixture, {
      mediaBytes: [bytesOf(16, 4)],
      note: 'during build',
      tags: [],
    });
    events.emit('entriesChanged', [created.entryId]);
    await building;
    await vi.waitFor(() => expect(service.index()?.get(created.entryId)).not.toBeNull(), {
      timeout: WAIT_MS,
      interval: 5,
    });
  });
});

describe('index service: clear and tag suggestions', () => {
  it('clear() empties the index and ignores later changes until the next build', async () => {
    await service.build();
    service.clear();
    expect(service.status()).toBe('idle');
    expect(service.index()).toBeNull();

    events.emit('entriesChanged', [entries[0]?.entryId ?? '']);
    await new Promise((resolve) => setTimeout(resolve, INDEX_UPDATE_DEBOUNCE_MS * 2));
    expect(service.index()).toBeNull();

    await service.build();
    expect(service.index()?.size()).toBe(3);
  });

  it('a build interrupted by clear() is not installed', async () => {
    const building = service.build();
    service.clear();
    locked = true;
    await building.catch(() => undefined);
    expect(service.status()).toBe('idle');
    expect(service.index()).toBeNull();
  });

  it('rebuilds once the home collection becomes known', async () => {
    await fixture.rowStore.kvDelete(STORAGE_KEYS.accountHomeCollectionId);
    const collection = await fixture.rowStore.transaction((tx) =>
      tx.collections.get(fixture.homeCollectionId),
    );
    await fixture.rowStore.transaction((tx) => tx.collections.delete(fixture.homeCollectionId));
    await service.build();
    expect(journalIds()).toEqual([]);

    await fixture.rowStore.transaction(async (tx) => {
      if (collection !== null) await tx.collections.put(collection);
    });
    await fixture.rowStore.kvSetJson(STORAGE_KEYS.accountHomeCollectionId, fixture.homeCollectionId);
    events.emit('entriesChanged', [entries[0]?.entryId ?? '']);

    await vi.waitFor(() => expect(journalIds()).toHaveLength(3), { timeout: WAIT_MS, interval: 5 });
  });

  it('suggests own tags ranked by use through the index', async () => {
    const source = createIndexTagSuggestionSource(service);
    expect(source.suggest('')).toEqual([]);
    await service.build();
    expect(source.suggest('')).toEqual(['beach', 'city', 'lake']);
    expect(source.suggest('l')).toEqual(['lake']);
    expect(TAG_SUGGESTIONS_MAX).toBe(8);
  });
});
