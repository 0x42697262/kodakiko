import { beforeEach, describe, expect, it } from 'vitest';
import { ROUTES, encodeBytes } from '@kodakiko/contract';
import { STREAM_CHUNK_SIZE_EXPONENT, streamLayout } from '@kodakiko/core';
import type { Sodium } from '@kodakiko/core';
import type { Logger } from '../src/adapters/logger';
import { createEventBus, type AppEvents } from '../src/composition/events';
import { createPull } from '../src/services/sync/pull';
import { SYNC_LOG_EVENTS } from '../src/keys/sync-keys';
import { STORAGE_KEYS } from '../src/keys/storage-keys';
import { z } from 'zod';
import {
  createAccountFixture,
  createLocalEntry,
  createMemoryFileStore,
  createMemoryRowStore,
  createNodeSodium,
  type AccountFixture,
} from './support';
import { apiFailure, createFakeHttp, type FakeHttp } from './support/fake-http';

let sodium: Sodium;
let fixture: AccountFixture;
let fakeHttp: FakeHttp;
let changed: string[][];
let logged: Array<{ event: string; fields: Record<string, unknown> }>;

const TIME = '2026-11-12T10:00:00.000Z';
const LATER = '2026-11-13T10:00:00.000Z';
const OWNER_ID = 'OOOOOOOOOOOOOOOOOOOOOO';

function stateOf(
  collectionId: string,
  overrides: Partial<{ lastSeq: number; tombstonesPrunedThroughSeq: number; metaUpdatedAt: string; kind: string }> = {},
) {
  return {
    collections: [
      {
        id: collectionId,
        kind: 'home',
        role: 'owner',
        lastSeq: 1,
        tombstonesPrunedThroughSeq: 0,
        metaUpdatedAt: TIME,
        ...overrides,
      },
    ],
  };
}

function collectionDtoFor(collectionId: string, metaUpdatedAt = TIME) {
  return {
    id: collectionId,
    kind: 'home',
    role: 'owner',
    ownerId: OWNER_ID,
    ownerUsername: 'tester',
    keyMaterial: encodeBytes(Uint8Array.of(1, 2, 3)),
    metadataBlob: encodeBytes(Uint8Array.of(4, 5, 6)),
    lastSeq: 1,
    createdAt: TIME,
    metaUpdatedAt,
  };
}

function entryDtoFor(id: string, revision: number, blob: Uint8Array, trashedAt: string | null = null) {
  return { id, ownerId: OWNER_ID, purpose: 'journal', revision, metadataBlob: encodeBytes(blob), trashedAt, createdAt: TIME, updatedAt: TIME };
}

function mediaDtoFor(id: string, entryId: string) {
  return { id, entryId, mediaKeyByEntry: encodeBytes(Uint8Array.of(7, 7)), createdAt: TIME };
}

function objectDtoFor(id: string, mediaId: string, variant: 'original' | 'thumbnail', plaintext: number) {
  return {
    id,
    mediaId,
    variant,
    sizeBytes: streamLayout(plaintext, STREAM_CHUNK_SIZE_EXPONENT).ciphertextSize,
    state: 'uploaded',
  };
}

function page(
  collectionId: string,
  overrides: Partial<{
    memberships: unknown[];
    entries: unknown[];
    media: unknown[];
    objects: unknown[];
    nextCursor: number;
    hasMore: boolean;
    resyncRequired: boolean;
  }> = {},
) {
  return {
    collectionId,
    memberships: [],
    entries: [],
    media: [],
    objects: [],
    nextCursor: 1,
    hasMore: false,
    resyncRequired: false,
    ...overrides,
  };
}

function membershipOf(entryId: string, seq: number, removed = false) {
  return {
    entryId,
    entryKeyByCollection: removed ? null : encodeBytes(Uint8Array.of(9, 9, 9)),
    seq,
    removed,
  };
}

function diffPath(collectionId: string, since: number) {
  return `${ROUTES.collectionDiff(collectionId)}?since=${since}&limit=500`;
}

function puller() {
  const events = createEventBus<AppEvents>();
  events.on('entriesChanged', (ids) => changed.push([...ids]));
  const log: Logger = {
    warn: (event, fields) => {
      logged.push({ event, fields: { ...fields } });
    },
  };
  return createPull({ http: fakeHttp.http, rowStore: fixture.rowStore, fileStore: fixture.fileStore, events, log });
}

async function rows() {
  return fixture.rowStore.transaction(async (tx) => ({
    collections: await tx.collections.all(),
    entries: await tx.entries.all(),
    memberships: await tx.memberships.all(),
    media: await tx.media.all(),
    objects: await tx.objects.all(),
  }));
}

beforeEach(async () => {
  sodium = await createNodeSodium();
  fixture = await createAccountFixture(sodium, createMemoryRowStore(), createMemoryFileStore());
  await fixture.rowStore.transaction((tx) => tx.collections.delete(fixture.homeCollectionId));
  fakeHttp = createFakeHttp();
  changed = [];
  logged = [];
});

describe('pull: first sync of a device', () => {
  it('refetches the collection and applies the first diff page in one go', async () => {
    const collectionId = fixture.homeCollectionId;
    const entryId = fixture.randomId();
    const mediaId = fixture.randomId();
    const originalId = fixture.randomId();
    const thumbnailId = fixture.randomId();
    const blob = Uint8Array.of(1, 1, 1);
    fakeHttp.on('GET', ROUTES.syncState(), () => stateOf(collectionId));
    fakeHttp.on('GET', ROUTES.collection(collectionId), () => collectionDtoFor(collectionId));
    fakeHttp.on('GET', diffPath(collectionId, 0), () =>
      page(collectionId, {
        memberships: [membershipOf(entryId, 1)],
        entries: [entryDtoFor(entryId, 1, blob)],
        media: [mediaDtoFor(mediaId, entryId)],
        objects: [objectDtoFor(originalId, mediaId, 'original', 1000), objectDtoFor(thumbnailId, mediaId, 'thumbnail', 64)],
        nextCursor: 1,
      }),
    );
    const result = await puller().run();
    expect(result.pulledEntries).toBe(1);
    const state = await rows();
    expect(state.collections[0]).toMatchObject({ id: collectionId, kind: 'home', role: 'owner', ownerUsername: 'tester', metaUpdatedAt: TIME, cursorSeq: 1 });
    expect(state.entries[0]).toMatchObject({ id: entryId, revision: 1, baseRevision: 1, localState: 'synced', trashedAt: null });
    expect([...(state.entries[0]?.baseMetadataBlob ?? [])]).toEqual([...blob]);
    expect(state.memberships.map((row) => [row.collectionId, row.entryId, row.seq])).toEqual([[collectionId, entryId, 1]]);
    expect(state.media.map((row) => row.id)).toEqual([mediaId]);
    expect(state.objects.map((row) => [row.id, row.uploadState, row.localState]).sort()).toEqual(
      [[originalId, 'uploaded', 'missing'], [thumbnailId, 'uploaded', 'missing']].sort(),
    );
    expect(state.objects.find((row) => row.id === originalId)?.plaintextSize).toBe(1000);
    expect(changed.flat()).toContain(entryId);
  });

  it('does not ask for diffs when every collection is current', async () => {
    const collectionId = fixture.homeCollectionId;
    await fixture.rowStore.transaction((tx) =>
      tx.collections.put({ id: collectionId, kind: 'home', ownerId: OWNER_ID, ownerUsername: 't', role: 'owner', keyMaterial: Uint8Array.of(1), metadataBlob: Uint8Array.of(1), metaUpdatedAt: TIME, serverLastSeq: 4, cursorSeq: 4 }),
    );
    fakeHttp.on('GET', ROUTES.syncState(), () => stateOf(collectionId, { lastSeq: 4 }));
    const result = await puller().run();
    expect(result.pulledEntries).toBe(0);
    expect(fakeHttp.requests).toHaveLength(1);
  });

  it('ignores link collections', async () => {
    fakeHttp.on('GET', ROUTES.syncState(), () => stateOf(fixture.randomId(), { kind: 'link', lastSeq: 5 }));
    await puller().run();
    expect(fakeHttp.requests).toHaveLength(1);
    expect((await rows()).collections).toEqual([]);
  });
});

describe('pull: home collection id', () => {
  async function storedHomeId() {
    return fixture.rowStore.kvGetJson(STORAGE_KEYS.accountHomeCollectionId, z.string());
  }

  it('records the owned home collection id when the key is missing', async () => {
    const collectionId = fixture.homeCollectionId;
    fakeHttp.on('GET', ROUTES.syncState(), () => stateOf(collectionId));
    fakeHttp.on('GET', ROUTES.collection(collectionId), () => collectionDtoFor(collectionId));
    fakeHttp.on('GET', diffPath(collectionId, 0), () => page(collectionId));
    expect(await storedHomeId()).toBeNull();
    await puller().run();
    expect(await storedHomeId()).toBe(collectionId);
  });

  it('keeps an existing home collection id', async () => {
    const collectionId = fixture.homeCollectionId;
    const existing = fixture.randomId();
    await fixture.rowStore.kvSetJson(STORAGE_KEYS.accountHomeCollectionId, existing);
    fakeHttp.on('GET', ROUTES.syncState(), () => stateOf(collectionId));
    fakeHttp.on('GET', ROUTES.collection(collectionId), () => collectionDtoFor(collectionId));
    fakeHttp.on('GET', diffPath(collectionId, 0), () => page(collectionId));
    await puller().run();
    expect(await storedHomeId()).toBe(existing);
  });

  it('does not record a shared collection', async () => {
    const collectionId = fixture.randomId();
    fakeHttp.on('GET', ROUTES.syncState(), () => stateOf(collectionId, { kind: 'shared' }));
    fakeHttp.on('GET', ROUTES.collection(collectionId), () => ({
      ...collectionDtoFor(collectionId),
      kind: 'shared',
      role: 'viewer',
    }));
    fakeHttp.on('GET', diffPath(collectionId, 0), () => page(collectionId));
    await puller().run();
    expect(await storedHomeId()).toBeNull();
  });
});

describe('pull: collections', () => {
  it('refetches a collection whose metaUpdatedAt changed', async () => {
    const collectionId = fixture.homeCollectionId;
    await fixture.rowStore.transaction((tx) =>
      tx.collections.put({ id: collectionId, kind: 'home', ownerId: OWNER_ID, ownerUsername: 't', role: 'owner', keyMaterial: Uint8Array.of(1), metadataBlob: Uint8Array.of(1), metaUpdatedAt: TIME, serverLastSeq: 0, cursorSeq: 0 }),
    );
    fakeHttp.on('GET', ROUTES.syncState(), () => stateOf(collectionId, { lastSeq: 0, metaUpdatedAt: LATER }));
    fakeHttp.on('GET', ROUTES.collection(collectionId), () => collectionDtoFor(collectionId, LATER));
    await puller().run();
    const [row] = (await rows()).collections;
    expect(row?.metaUpdatedAt).toBe(LATER);
    expect([...(row?.keyMaterial ?? [])]).toEqual([1, 2, 3]);
  });

  it('drops a collection that disappeared remotely with its orphaned entries and files', async () => {
    const local = await createLocalEntry(fixture, { mediaBytes: [Uint8Array.of(1)], note: '', tags: [], enqueue: false });
    await fixture.rowStore.transaction((tx) =>
      tx.collections.put({ id: fixture.homeCollectionId, kind: 'home', ownerId: OWNER_ID, ownerUsername: 't', role: 'owner', keyMaterial: Uint8Array.of(1), metadataBlob: Uint8Array.of(1), metaUpdatedAt: TIME, serverLastSeq: 1, cursorSeq: 1 }),
    );
    fakeHttp.on('GET', ROUTES.syncState(), () => ({ collections: [] }));
    await puller().run();
    const state = await rows();
    expect(state.collections).toEqual([]);
    expect(state.entries).toEqual([]);
    expect(state.objects).toEqual([]);
    expect(await fixture.fileStore.size(local.originalObjectIds[0] ?? '')).toBeNull();
    expect(changed.flat()).toContain(local.entryId);
  });
});

describe('pull: applying pages', () => {
  const collectionId = () => fixture.homeCollectionId;

  async function seedSyncedEntry(revision: number, blob: Uint8Array, localState: 'synced' | 'pending_update' = 'synced') {
    const local = await createLocalEntry(fixture, { mediaBytes: [Uint8Array.of(1)], note: '', tags: [], enqueue: false });
    await fixture.rowStore.transaction(async (tx) => {
      const entry = await tx.entries.get(local.entryId);
      if (entry !== null) await tx.entries.put({ ...entry, revision, baseRevision: revision, metadataBlob: blob, baseMetadataBlob: blob, localState });
      for (const object of await tx.objects.all()) await tx.objects.put({ ...object, uploadState: 'uploaded' });
      await tx.collections.put({ id: collectionId(), kind: 'home', ownerId: OWNER_ID, ownerUsername: 't', role: 'owner', keyMaterial: Uint8Array.of(1), metadataBlob: Uint8Array.of(1), metaUpdatedAt: TIME, serverLastSeq: 1, cursorSeq: 1 });
    });
    return local;
  }

  function serveDiff(since: number, response: unknown, lastSeq = 5) {
    fakeHttp.on('GET', ROUTES.syncState(), () => stateOf(collectionId(), { lastSeq }));
    fakeHttp.on('GET', diffPath(collectionId(), since), () => response);
  }

  it('overwrites a synced entry with a newer revision and always takes trashedAt', async () => {
    const local = await seedSyncedEntry(1, Uint8Array.of(1));
    const newer = Uint8Array.of(2, 2);
    serveDiff(1, page(collectionId(), {
      memberships: [membershipOf(local.entryId, 2)],
      entries: [entryDtoFor(local.entryId, 2, newer, LATER)],
      media: local.mediaIds.map((id) => mediaDtoFor(id, local.entryId)),
      objects: [...local.originalObjectIds.map((id) => objectDtoFor(id, local.mediaIds[0] ?? '', 'original', 1)), ...local.thumbnailObjectIds.map((id) => objectDtoFor(id, local.mediaIds[0] ?? '', 'thumbnail', 64))],
      nextCursor: 2,
    }));
    await puller().run();
    const [entry] = (await rows()).entries;
    expect([...(entry?.metadataBlob ?? [])]).toEqual([...newer]);
    expect(entry).toMatchObject({ revision: 2, baseRevision: 2, trashedAt: LATER, localState: 'synced' });
  });

  it('keeps a pending local change and stores the pulled copy as the server-pending blob', async () => {
    const local = await seedSyncedEntry(1, Uint8Array.of(1), 'pending_update');
    const mine = Uint8Array.of(5, 5);
    await fixture.rowStore.transaction(async (tx) => {
      const entry = await tx.entries.get(local.entryId);
      if (entry !== null) await tx.entries.put({ ...entry, metadataBlob: mine });
    });
    const theirs = Uint8Array.of(3, 3);
    serveDiff(1, page(collectionId(), {
      memberships: [membershipOf(local.entryId, 2)],
      entries: [entryDtoFor(local.entryId, 2, theirs, LATER)],
      media: local.mediaIds.map((id) => mediaDtoFor(id, local.entryId)),
      nextCursor: 2,
    }));
    await puller().run();
    const [entry] = (await rows()).entries;
    expect([...(entry?.metadataBlob ?? [])]).toEqual([...mine]);
    expect([...(entry?.serverPendingBlob ?? [])]).toEqual([...theirs]);
    expect(entry).toMatchObject({ serverPendingRevision: 2, trashedAt: LATER, localState: 'pending_update', baseRevision: 1 });
  });

  it('rejects a rollback, logs it and keeps the newer local blob', async () => {
    const local = await seedSyncedEntry(5, Uint8Array.of(5));
    serveDiff(1, page(collectionId(), {
      memberships: [membershipOf(local.entryId, 2)],
      entries: [entryDtoFor(local.entryId, 3, Uint8Array.of(3))],
      media: local.mediaIds.map((id) => mediaDtoFor(id, local.entryId)),
      nextCursor: 2,
    }));
    await puller().run();
    const [entry] = (await rows()).entries;
    expect([...(entry?.metadataBlob ?? [])]).toEqual([5]);
    expect(entry?.revision).toBe(5);
    expect(logged.map((line) => line.event)).toEqual([SYNC_LOG_EVENTS.rollbackRejected]);
    expect(logged[0]?.fields).toMatchObject({ entryId: local.entryId, pulledRevision: 3, baseRevision: 5 });
  });

  it('removes an entry whose last membership is removed, with media, objects and files', async () => {
    const local = await seedSyncedEntry(1, Uint8Array.of(1));
    serveDiff(1, page(collectionId(), { memberships: [membershipOf(local.entryId, 2, true)], nextCursor: 2 }));
    await puller().run();
    const state = await rows();
    expect(state.entries).toEqual([]);
    expect(state.media).toEqual([]);
    expect(state.objects).toEqual([]);
    expect(state.memberships).toEqual([]);
    expect(await fixture.fileStore.size(local.originalObjectIds[0] ?? '')).toBeNull();
  });

  it('keeps an entry that still has another membership', async () => {
    const local = await seedSyncedEntry(1, Uint8Array.of(1));
    const otherCollection = fixture.randomId();
    await fixture.rowStore.transaction((tx) => tx.memberships.put({ collectionId: otherCollection, entryId: local.entryId, entryKeyWrap: Uint8Array.of(1), seq: 1 }));
    serveDiff(1, page(collectionId(), { memberships: [membershipOf(local.entryId, 2, true)], nextCursor: 2 }));
    await puller().run();
    expect((await rows()).entries).toHaveLength(1);
  });

  it('deletes an older object row for the same media variant with its file', async () => {
    const local = await seedSyncedEntry(1, Uint8Array.of(1));
    const mediaId = local.mediaIds[0] ?? '';
    const replacement = fixture.randomId();
    serveDiff(1, page(collectionId(), {
      memberships: [membershipOf(local.entryId, 2)],
      entries: [entryDtoFor(local.entryId, 1, Uint8Array.of(1))],
      media: [mediaDtoFor(mediaId, local.entryId)],
      objects: [objectDtoFor(replacement, mediaId, 'original', 1), ...local.thumbnailObjectIds.map((id) => objectDtoFor(id, mediaId, 'thumbnail', 64))],
      nextCursor: 2,
    }));
    await puller().run();
    const ids = (await rows()).objects.map((row) => row.id);
    expect(ids).toContain(replacement);
    expect(ids).not.toContain(local.originalObjectIds[0]);
    expect(await fixture.fileStore.size(local.originalObjectIds[0] ?? '')).toBeNull();
  });

  it('deletes local media the server no longer lists when all its objects were uploaded', async () => {
    const local = await seedSyncedEntry(1, Uint8Array.of(1));
    serveDiff(1, page(collectionId(), {
      memberships: [membershipOf(local.entryId, 2)],
      entries: [entryDtoFor(local.entryId, 2, Uint8Array.of(2))],
      media: [],
      nextCursor: 2,
    }));
    await puller().run();
    const state = await rows();
    expect(state.media).toEqual([]);
    expect(state.objects).toEqual([]);
  });

  it('keeps local media whose objects are not uploaded yet', async () => {
    const local = await seedSyncedEntry(1, Uint8Array.of(1));
    await fixture.rowStore.transaction(async (tx) => {
      for (const object of await tx.objects.all()) await tx.objects.put({ ...object, uploadState: 'local' });
    });
    serveDiff(1, page(collectionId(), {
      memberships: [membershipOf(local.entryId, 2)],
      entries: [entryDtoFor(local.entryId, 2, Uint8Array.of(2))],
      media: [],
      nextCursor: 2,
    }));
    await puller().run();
    expect((await rows()).media).toHaveLength(1);
  });

  it('pages: commits the cursor per page and continues while hasMore', async () => {
    const first = fixture.randomId();
    const second = fixture.randomId();
    fakeHttp.on('GET', ROUTES.syncState(), () => stateOf(collectionId(), { lastSeq: 2 }));
    fakeHttp.on('GET', ROUTES.collection(collectionId()), () => collectionDtoFor(collectionId()));
    fakeHttp.on('GET', diffPath(collectionId(), 0), () =>
      page(collectionId(), { memberships: [membershipOf(first, 1)], entries: [entryDtoFor(first, 1, Uint8Array.of(1))], nextCursor: 1, hasMore: true }),
    );
    fakeHttp.on('GET', diffPath(collectionId(), 1), () => {
      throw apiFailure(500, 'INTERNAL');
    });
    await expect(puller().run()).rejects.toMatchObject({ status: 500 });
    expect((await rows()).collections[0]?.cursorSeq).toBe(1);
    fakeHttp.on('GET', diffPath(collectionId(), 1), () =>
      page(collectionId(), { memberships: [membershipOf(second, 2)], entries: [entryDtoFor(second, 1, Uint8Array.of(2))], nextCursor: 2 }),
    );
    await puller().run();
    expect((await rows()).entries.map((entry) => entry.id).sort()).toEqual([first, second].sort());
    expect((await rows()).collections[0]?.cursorSeq).toBe(2);
  });
});

describe('pull: resync', () => {
  it('wipes the collection memberships, re-pulls from 0 and removes entries the server forgot', async () => {
    const kept = await createLocalEntry(fixture, { mediaBytes: [Uint8Array.of(1)], note: '', tags: [], enqueue: false });
    const forgotten = await createLocalEntry(fixture, { mediaBytes: [Uint8Array.of(2)], note: '', tags: [], enqueue: false });
    const pending = await createLocalEntry(fixture, { mediaBytes: [Uint8Array.of(3)], note: '', tags: [], enqueue: false });
    await fixture.rowStore.transaction(async (tx) => {
      for (const local of [kept, forgotten]) {
        const entry = await tx.entries.get(local.entryId);
        if (entry !== null) await tx.entries.put({ ...entry, localState: 'synced', revision: 1, baseRevision: 1, baseMetadataBlob: entry.metadataBlob });
      }
      for (const object of await tx.objects.all()) await tx.objects.put({ ...object, uploadState: 'uploaded' });
      await tx.collections.put({ id: fixture.homeCollectionId, kind: 'home', ownerId: OWNER_ID, ownerUsername: 't', role: 'owner', keyMaterial: Uint8Array.of(1), metadataBlob: Uint8Array.of(1), metaUpdatedAt: TIME, serverLastSeq: 3, cursorSeq: 3 });
    });
    const collectionId = fixture.homeCollectionId;
    fakeHttp.on('GET', ROUTES.syncState(), () => stateOf(collectionId, { lastSeq: 20, tombstonesPrunedThroughSeq: 10 }));
    const keptBlob = Uint8Array.of(8);
    fakeHttp.on('GET', diffPath(collectionId, 0), () =>
      page(collectionId, {
        memberships: [membershipOf(kept.entryId, 4)],
        entries: [entryDtoFor(kept.entryId, 1, keptBlob)],
        media: kept.mediaIds.map((id) => mediaDtoFor(id, kept.entryId)),
        objects: [objectDtoFor(kept.originalObjectIds[0] ?? '', kept.mediaIds[0] ?? '', 'original', 1), objectDtoFor(kept.thumbnailObjectIds[0] ?? '', kept.mediaIds[0] ?? '', 'thumbnail', 64)],
        nextCursor: 20,
      }),
    );
    await puller().run();
    const ids = (await rows()).entries.map((entry) => entry.id);
    expect(ids).toContain(kept.entryId);
    expect(ids).not.toContain(forgotten.entryId);
    expect(ids).toContain(pending.entryId);
    expect((await rows()).collections[0]?.cursorSeq).toBe(20);
    expect(fakeHttp.count('GET', diffPath(collectionId, 3))).toBe(0);
  });

  it('restarts from 0 when a page reports resyncRequired', async () => {
    const collectionId = fixture.homeCollectionId;
    await fixture.rowStore.transaction((tx) =>
      tx.collections.put({ id: collectionId, kind: 'home', ownerId: OWNER_ID, ownerUsername: 't', role: 'owner', keyMaterial: Uint8Array.of(1), metadataBlob: Uint8Array.of(1), metaUpdatedAt: TIME, serverLastSeq: 5, cursorSeq: 5 }),
    );
    fakeHttp.on('GET', ROUTES.syncState(), () => stateOf(collectionId, { lastSeq: 9 }));
    fakeHttp.on('GET', diffPath(collectionId, 5), () => page(collectionId, { resyncRequired: true, nextCursor: 0 }));
    fakeHttp.on('GET', diffPath(collectionId, 0), () => page(collectionId, { nextCursor: 9 }));
    await puller().run();
    expect(fakeHttp.count('GET', diffPath(collectionId, 0))).toBe(1);
    expect((await rows()).collections[0]?.cursorSeq).toBe(9);
  });

  async function seedResyncCollection() {
    const kept = await createLocalEntry(fixture, { mediaBytes: [Uint8Array.of(1)], note: '', tags: [], enqueue: false });
    const forgotten = await createLocalEntry(fixture, { mediaBytes: [Uint8Array.of(2)], note: '', tags: [], enqueue: false });
    const updating = await createLocalEntry(fixture, { mediaBytes: [Uint8Array.of(3)], note: '', tags: [], enqueue: false });
    const queued = await createLocalEntry(fixture, { mediaBytes: [Uint8Array.of(4)], note: '', tags: [], enqueue: false });
    await fixture.rowStore.transaction(async (tx) => {
      for (const local of [kept, forgotten, updating, queued]) {
        const entry = await tx.entries.get(local.entryId);
        if (entry === null) continue;
        const localState = local === updating ? 'pending_update' : 'synced';
        await tx.entries.put({ ...entry, localState, revision: 1, baseRevision: 1, baseMetadataBlob: entry.metadataBlob });
      }
      await tx.outbox.enqueue({ kind: 'trashEntry', payload: { entryId: queued.entryId }, state: 'queued', createdAt: 0, attempts: 0, nextAttemptAt: 0, lastErrorCode: null });
      await tx.collections.put({ id: fixture.homeCollectionId, kind: 'home', ownerId: OWNER_ID, ownerUsername: 't', role: 'owner', keyMaterial: Uint8Array.of(1), metadataBlob: Uint8Array.of(1), metaUpdatedAt: TIME, serverLastSeq: 3, cursorSeq: 3 });
    });
    return { kept, forgotten, updating, queued };
  }

  it('drops orphaned entries in the wipe transaction itself, so an interrupted resync leaves no stale entries', async () => {
    const { kept, forgotten, updating, queued } = await seedResyncCollection();
    const collectionId = fixture.homeCollectionId;
    fakeHttp.on('GET', ROUTES.syncState(), () => stateOf(collectionId, { lastSeq: 20, tombstonesPrunedThroughSeq: 10 }));
    fakeHttp.on('GET', diffPath(collectionId, 0), () => {
      throw apiFailure(500, 'INTERNAL');
    });
    await expect(puller().run()).rejects.toMatchObject({ status: 500 });
    const state = await rows();
    const ids = state.entries.map((entry) => entry.id);
    expect(ids).not.toContain(kept.entryId);
    expect(ids).not.toContain(forgotten.entryId);
    expect(ids).toContain(updating.entryId);
    expect(ids).toContain(queued.entryId);
    expect(state.collections[0]?.cursorSeq).toBe(0);
    expect(await fixture.fileStore.size(forgotten.originalObjectIds[0] ?? '')).toBeNull();
    expect(changed.flat()).toContain(forgotten.entryId);
  });

  it('keeps a pending entry that the server still lists after the resync', async () => {
    const { updating } = await seedResyncCollection();
    const collectionId = fixture.homeCollectionId;
    fakeHttp.on('GET', ROUTES.syncState(), () => stateOf(collectionId, { lastSeq: 20, tombstonesPrunedThroughSeq: 10 }));
    fakeHttp.on('GET', diffPath(collectionId, 0), () =>
      page(collectionId, {
        memberships: [membershipOf(updating.entryId, 4)],
        entries: [entryDtoFor(updating.entryId, 2, Uint8Array.of(9))],
        media: updating.mediaIds.map((id) => mediaDtoFor(id, updating.entryId)),
        nextCursor: 20,
      }),
    );
    await puller().run();
    const entry = (await rows()).entries.find((row) => row.id === updating.entryId);
    expect(entry?.localState).toBe('pending_update');
    expect(entry?.serverPendingRevision).toBe(2);
  });

  it('throws when the server keeps demanding a resync instead of silently giving up', async () => {
    const collectionId = fixture.homeCollectionId;
    await fixture.rowStore.transaction((tx) =>
      tx.collections.put({ id: collectionId, kind: 'home', ownerId: OWNER_ID, ownerUsername: 't', role: 'owner', keyMaterial: Uint8Array.of(1), metadataBlob: Uint8Array.of(1), metaUpdatedAt: TIME, serverLastSeq: 5, cursorSeq: 5 }),
    );
    fakeHttp.on('GET', ROUTES.syncState(), () => stateOf(collectionId, { lastSeq: 9 }));
    fakeHttp.on('GET', diffPath(collectionId, 5), () => page(collectionId, { resyncRequired: true, nextCursor: 0 }));
    fakeHttp.on('GET', diffPath(collectionId, 0), () => page(collectionId, { resyncRequired: true, nextCursor: 0 }));
    await expect(puller().run()).rejects.toBeInstanceOf(Error);
  });
});
