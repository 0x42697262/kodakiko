import { beforeEach, describe, expect, it } from 'vitest';
import { EntryMetadataV1, ROUTES, encodeBytes } from '@kodakiko/contract';
import {
  AD_CONTEXTS,
  InvalidEditError,
  bytesEqual,
  openJson,
  sealJson,
  unwrapKey,
  type Sodium,
} from '@kodakiko/core';
import {
  buildJpeg,
  buildTiff,
  exifApp1,
  jfifApp0,
} from '../../../packages/core/test/media/builders';
import { createEventBus, type AppEvents } from '../src/composition/events';
import { EDIT_RETRY_ATTEMPTS } from '../src/constants/entry-constants';
import {
  EmptyTrayError,
  EntryChangedError,
  ReadOnlyEntryError,
  TrayChangedError,
} from '../src/services/entries/entries-service';
import { AccountLockedError } from '../src/services/keys/entry-keys';
import { EXECUTORS } from '../src/services/sync/executors';
import type { ExecutorDeps } from '../src/services/sync/executors/executor-types';
import { createNodeSodium } from './support';
import {
  FIRST_NOW_ISO,
  STEP_MS,
  createEntriesHarness,
  fakeJpeg,
  type EntriesHarness,
} from './support/entries-harness';
import { createFakeHttp } from './support/fake-http';

let sodium: Sodium;
let harness: EntriesHarness;

const NO_MIGRATIONS = new Map<number, (raw: Record<string, unknown>) => Record<string, unknown>>();
const OTHER_USER = 'someone-else-locally';
const SERVER_OWNER_ID = 'OOOOOOOOOOOOOOOOOOOOOO';
const EARLIER_TIME = { takenAtUtc: '2026-05-01T08:00:00.000Z', utcOffsetMinutes: 120 };
const PARIS = { latitude: 48.8584, longitude: 2.2945 };
const COORDINATE_PRECISION = 5;
const PHOTO_BYTES = 300;

beforeEach(async () => {
  sodium = await createNodeSodium();
  harness = await createEntriesHarness(sodium);
});

async function metadataOf(entryId: string) {
  return harness.service.readMetadata(entryId);
}

async function setAttempts(kind: string, attempts: number): Promise<void> {
  await harness.fixture.rowStore.transaction(async (tx) => {
    for (const item of await tx.outbox.all()) {
      if (item.kind === kind) await tx.outbox.update({ ...item, attempts });
    }
  });
}

async function outbox() {
  return harness.fixture.rowStore.transaction((tx) => tx.outbox.all());
}

async function writeConcurrentNote(entryId: string, note: string): Promise<void> {
  const entryKey = await harness.entryKeys.entryKey(entryId);
  const row = await harness.entryRow(entryId);
  const current = openJson(
    sodium,
    entryKey,
    row.metadataBlob,
    EntryMetadataV1,
    1,
    NO_MIGRATIONS,
    AD_CONTEXTS.entryMetadata,
    entryId,
  );
  const next = { ...current, note };
  const blob = sealJson(
    sodium,
    entryKey,
    next,
    AD_CONTEXTS.entryMetadata,
    entryId,
  );
  await harness.fixture.rowStore.transaction(async (tx) => {
    const fresh = await tx.entries.get(entryId);
    if (fresh !== null) await tx.entries.put({ ...fresh, metadataBlob: blob });
  });
}

async function rowsOf(entryId: string) {
  return harness.fixture.rowStore.transaction(async (tx) => {
    const media = await tx.media.byEntry(entryId);
    const objects = (await Promise.all(media.map((row) => tx.objects.byMedia(row.id)))).flat();
    return {
      entry: await tx.entries.get(entryId),
      memberships: await tx.memberships.byEntry(entryId),
      media,
      objects,
    };
  });
}

describe('createFromTray', () => {
  it('writes exactly one entry, one membership, N media, 2N objects and one createEntry item', async () => {
    const entryId = await harness.createEntry([fakeJpeg(PHOTO_BYTES), fakeJpeg(PHOTO_BYTES, 9)], {
      note: 'Picnic',
      tags: ['family', 'beach'],
    });

    const rows = await rowsOf(entryId);
    expect(rows.entry).toMatchObject({
      ownerId: harness.fixture.userId,
      purpose: 'journal',
      revision: 1,
      baseMetadataBlob: null,
      baseRevision: 0,
      serverPendingBlob: null,
      serverPendingRevision: null,
      localState: 'pending_create',
      trashedAt: null,
      createdAt: FIRST_NOW_ISO,
      updatedAt: FIRST_NOW_ISO,
    });
    expect(rows.memberships).toHaveLength(1);
    expect(rows.memberships[0]).toMatchObject({
      collectionId: harness.fixture.homeCollectionId,
      seq: 0,
    });
    expect(rows.media).toHaveLength(2);
    expect(rows.objects).toHaveLength(4);
    for (const object of rows.objects) {
      expect(object).toMatchObject({
        localState: 'present',
        uploadState: 'local',
        uploadJobId: null,
        lastAccessAt: Date.parse(FIRST_NOW_ISO),
      });
      expect(await harness.fixture.fileStore.size(object.id)).toBe(object.sizeBytes);
      expect(object.plaintextSize).toBeGreaterThan(0);
    }
    const [item, ...others] = await outbox();
    expect(others).toEqual([]);
    expect(item).toMatchObject({
      kind: 'createEntry',
      payload: { entryId },
      state: 'queued',
      attempts: 0,
      lastErrorCode: null,
    });
  });

  it('seals metadata that opens with the entry key from the membership wrap', async () => {
    const entryId = await harness.createEntry([fakeJpeg(PHOTO_BYTES)], {
      note: 'Picnic',
      tags: ['b', 'a'],
    });
    const rows = await rowsOf(entryId);
    const collectionKey = harness.fixture.homeCollectionKey;
    const entryKey = unwrapKey(
      sodium,
      collectionKey,
      rows.memberships[0]?.entryKeyWrap ?? new Uint8Array(),
      AD_CONTEXTS.entryKeyByCollection,
      `${harness.fixture.homeCollectionId}/${entryId}`,
    );
    const metadata = openJson(
      sodium,
      entryKey,
      rows.entry?.metadataBlob ?? new Uint8Array(),
      EntryMetadataV1,
      1,
      NO_MIGRATIONS,
      AD_CONTEXTS.entryMetadata,
      entryId,
    );
    expect(metadata).toMatchObject({
      note: 'Picnic',
      tags: ['a', 'b'],
      createdAtUtc: FIRST_NOW_ISO,
      editedAtUtc: FIRST_NOW_ISO,
    });
    expect(metadata.media.map((item) => item.mediaId).sort()).toEqual(
      rows.media.map((row) => row.id).sort(),
    );
    for (const media of rows.media) {
      const mediaKey = unwrapKey(
        sodium,
        entryKey,
        media.mediaKeyWrap,
        AD_CONTEXTS.mediaKeyByEntry,
        `${entryId}/${media.id}`,
      );
      expect(mediaKey).toHaveLength(32);
    }
  });

  it('clears the tray without deleting the files, and notifies', async () => {
    const entryId = await harness.createEntry([fakeJpeg(PHOTO_BYTES)]);
    expect(harness.tray.items).toHaveLength(0);
    const rows = await rowsOf(entryId);
    for (const object of rows.objects) {
      expect(await harness.fixture.fileStore.size(object.id)).not.toBeNull();
    }
    expect(harness.notified.count).toBe(1);
    expect(harness.changed).toEqual([[entryId]]);
  });

  it('keeps the tray order as the metadata media order', async () => {
    const first = await harness.importIntoTray(fakeJpeg(PHOTO_BYTES, 1));
    const second = await harness.importIntoTray(fakeJpeg(PHOTO_BYTES, 2));
    harness.tray.reorder(1, 0);
    const entryId = await harness.service.createFromTray({ note: '', tags: [], time: null });
    expect((await metadataOf(entryId)).media.map((item) => item.mediaId)).toEqual([second, first]);
  });

  it('uses the time given by the user instead of the computed one', async () => {
    await harness.importIntoTray(fakeJpeg(PHOTO_BYTES));
    const entryId = await harness.service.createFromTray({ note: '', tags: [], time: EARLIER_TIME });
    expect(await metadataOf(entryId)).toMatchObject(EARLIER_TIME);
  });

  it('fails with the locked error when the journal is locked', async () => {
    await harness.importIntoTray(fakeJpeg(PHOTO_BYTES));
    harness.lock();
    await expect(
      harness.service.createFromTray({ note: '', tags: [], time: null }),
    ).rejects.toBeInstanceOf(AccountLockedError);
    expect(await outbox()).toHaveLength(0);
  });

  it('uses a location given by the user and removes it when given null', async () => {
    const tiff = buildTiff({ dateTimeOriginal: '2026:05:01 10:00:00', offsetTimeOriginal: '+02:00', gps: PARIS });
    const photo = buildJpeg({ segments: [jfifApp0(), exifApp1(tiff)], scanBytes: 256 });
    await harness.importIntoTray(photo);
    const removed = await harness.service.createFromTray({ note: '', tags: [], time: null, location: null });
    expect((await metadataOf(removed)).location).toBeNull();

    await harness.importIntoTray(photo);
    const chosen = { latitude: 1.25, longitude: 2.5, accuracyMeters: 10, source: 'device' as const };
    const placed = await harness.service.createFromTray({ note: '', tags: [], time: null, location: chosen });
    expect((await metadataOf(placed)).location).toEqual(chosen);
  });

  it('refuses when the tray is cleared while it resolves keys, and writes nothing', async () => {
    await harness.importIntoTray(fakeJpeg(PHOTO_BYTES));
    harness.hooks.beforeKey = async () => {
      harness.hooks.beforeKey = null;
      await harness.tray.clear(true);
    };
    await expect(
      harness.service.createFromTray({ note: '', tags: [], time: null }),
    ).rejects.toBeInstanceOf(TrayChangedError);
    expect(await outbox()).toHaveLength(0);
    expect(await harness.fixture.rowStore.transaction((tx) => tx.entries.all())).toHaveLength(0);
  });

  it('refuses when an item joins the tray while it resolves keys', async () => {
    await harness.importIntoTray(fakeJpeg(PHOTO_BYTES, 1));
    const late = fakeJpeg(PHOTO_BYTES, 2);
    harness.hooks.beforeKey = async () => {
      harness.hooks.beforeKey = null;
      await harness.importIntoTray(late);
    };
    await expect(
      harness.service.createFromTray({ note: '', tags: [], time: null }),
    ).rejects.toBeInstanceOf(TrayChangedError);
    expect(harness.tray.items).toHaveLength(2);
    expect(await outbox()).toHaveLength(0);
  });

  it('puts the items back with their files when the write fails', async () => {
    await harness.importIntoTray(fakeJpeg(PHOTO_BYTES, 1));
    await harness.importIntoTray(fakeJpeg(PHOTO_BYTES, 2));
    const before = harness.tray.items.map((item) => item.mediaId);
    harness.hooks.failEnqueue = true;
    await expect(harness.service.createFromTray({ note: '', tags: [], time: null })).rejects.toThrow();
    expect(harness.tray.items.map((item) => item.mediaId)).toEqual(before);
    for (const item of harness.tray.items) {
      expect(await harness.fixture.fileStore.size(item.originalObjectId)).not.toBeNull();
      expect(item.mediaKey.some((byte) => byte !== 0)).toBe(true);
    }
    expect(await harness.fixture.rowStore.transaction((tx) => tx.entries.all())).toHaveLength(0);
  });

  it('refuses an empty tray', async () => {
    await expect(
      harness.service.createFromTray({ note: '', tags: [], time: null }),
    ).rejects.toBeInstanceOf(EmptyTrayError);
    expect(await outbox()).toHaveLength(0);
  });

  it('B-021: takes the entry time and location from a photo with EXIF date and GPS', async () => {
    const tiff = buildTiff({
      dateTimeOriginal: '2026:05:01 10:00:00',
      offsetTimeOriginal: '+02:00',
      gps: PARIS,
    });
    const photo = buildJpeg({ segments: [jfifApp0(), exifApp1(tiff)], scanBytes: 256 });
    const entryId = await harness.createEntry([photo]);
    const metadata = await metadataOf(entryId);
    expect(metadata.takenAtUtc).toBe('2026-05-01T08:00:00.000Z');
    expect(metadata.utcOffsetMinutes).toBe(120);
    expect(metadata.location).toMatchObject({ accuracyMeters: null, source: 'exif' });
    expect(metadata.location?.latitude).toBeCloseTo(PARIS.latitude, COORDINATE_PRECISION);
    expect(metadata.location?.longitude).toBeCloseTo(PARIS.longitude, COORDINATE_PRECISION);
  });

  it('B-021: falls back to the device location of the tray, source device', async () => {
    await harness.importIntoTray(fakeJpeg(PHOTO_BYTES));
    harness.tray.setDeviceLocation({ point: PARIS, accuracyMeters: 25 });
    const entryId = await harness.service.createFromTray({ note: '', tags: [], time: null });
    expect((await metadataOf(entryId)).location).toEqual({ ...PARIS, accuracyMeters: 25, source: 'device' });
  });
});

describe('edit', () => {
  it('queues one updateEntryMeta for two offline edits of a synced entry', async () => {
    const entryId = await harness.createEntry([fakeJpeg(PHOTO_BYTES)], { note: 'one' });
    await harness.markSynced(entryId);
    harness.clock.advance(STEP_MS);
    await harness.service.edit(entryId, { note: 'two' });
    harness.clock.advance(STEP_MS);
    await harness.service.edit(entryId, { note: 'three' });

    expect(await harness.outboxKinds()).toEqual(['updateEntryMeta']);
    const row = await harness.entryRow(entryId);
    expect(row.localState).toBe('pending_update');
    expect(row.updatedAt).toBe(new Date(Date.parse(FIRST_NOW_ISO) + 2 * STEP_MS).toISOString());
    expect((await metadataOf(entryId)).note).toBe('three');
  });

  it('keeps pending_create and queues one updateEntryMeta behind createEntry', async () => {
    const entryId = await harness.createEntry([fakeJpeg(PHOTO_BYTES)], { note: 'one' });
    await harness.service.edit(entryId, { note: 'two' });
    await harness.service.edit(entryId, { tags: ['x'] });

    expect(await harness.outboxKinds()).toEqual(['createEntry', 'updateEntryMeta']);
    expect((await harness.entryRow(entryId)).localState).toBe('pending_create');
  });

  it('writes nothing for an equivalent edit', async () => {
    const entryId = await harness.createEntry([fakeJpeg(PHOTO_BYTES)], { note: 'same', tags: ['a'] });
    await harness.markSynced(entryId);
    const before = await harness.entryRow(entryId);
    const notifiedBefore = harness.notified.count;
    const changedBefore = harness.changed.length;
    harness.clock.advance(STEP_MS);

    await harness.service.edit(entryId, { note: 'same', tags: ['a'] });

    const after = await harness.entryRow(entryId);
    expect(after).toEqual(before);
    expect(await outbox()).toHaveLength(0);
    expect(harness.notified.count).toBe(notifiedBefore);
    expect(harness.changed).toHaveLength(changedBefore);
  });

  it('stamps editedAtUtc and notifies on a real edit', async () => {
    const entryId = await harness.createEntry([fakeJpeg(PHOTO_BYTES)]);
    harness.clock.advance(STEP_MS);
    await harness.service.edit(entryId, { note: 'changed' });
    const later = new Date(Date.parse(FIRST_NOW_ISO) + STEP_MS).toISOString();
    expect(await metadataOf(entryId)).toMatchObject({
      note: 'changed',
      createdAtUtc: FIRST_NOW_ISO,
      editedAtUtc: later,
    });
    expect(harness.changed.at(-1)).toEqual([entryId]);
  });

  it('refuses a viewer', async () => {
    const entryId = await harness.createEntry([fakeJpeg(PHOTO_BYTES)]);
    await harness.fixture.rowStore.transaction(async (tx) => {
      const row = await tx.entries.get(entryId);
      if (row !== null) await tx.entries.put({ ...row, ownerId: OTHER_USER });
    });
    await expect(harness.service.edit(entryId, { note: 'nope' })).rejects.toBeInstanceOf(
      ReadOnlyEntryError,
    );
    expect(await harness.outboxKinds()).toEqual(['createEntry']);
  });

  it('refuses an entry whose only membership is in a shared collection with role viewer', async () => {
    const entryId = await harness.createEntry([fakeJpeg(PHOTO_BYTES)]);
    const sharedId = harness.fixture.randomId();
    await harness.fixture.rowStore.transaction(async (tx) => {
      const row = await tx.entries.get(entryId);
      const membership = (await tx.memberships.byEntry(entryId))[0];
      if (row === null || membership === undefined) throw new Error('fixture entry missing');
      await tx.entries.put({ ...row, ownerId: OTHER_USER, localState: 'synced' });
      await tx.memberships.delete([membership.collectionId, entryId]);
      await tx.collections.put({
        id: sharedId,
        kind: 'shared',
        ownerId: OTHER_USER,
        ownerUsername: 'someone',
        role: 'viewer',
        keyMaterial: Uint8Array.of(1),
        metadataBlob: Uint8Array.of(1),
        metaUpdatedAt: FIRST_NOW_ISO,
        serverLastSeq: 0,
        cursorSeq: 0,
      });
      await tx.memberships.put({ ...membership, collectionId: sharedId });
    });
    await expect(harness.service.edit(entryId, { note: 'nope' })).rejects.toBeInstanceOf(
      ReadOnlyEntryError,
    );
    await expect(harness.service.removeMedia(entryId, 'AAAAAAAAAAAAAAAAAAAAAA')).rejects.toBeInstanceOf(
      ReadOnlyEntryError,
    );
    await expect(harness.service.addMedia(entryId, [])).rejects.toBeInstanceOf(ReadOnlyEntryError);
  });

  it('refuses addMedia passed to edit', async () => {
    const entryId = await harness.createEntry([fakeJpeg(PHOTO_BYTES), fakeJpeg(PHOTO_BYTES, 2)]);
    const descriptor = (await metadataOf(entryId)).media[0];
    const change = { addMedia: [{ ...descriptor, mediaId: 'AAAAAAAAAAAAAAAAAAAAAA' }] };
    await expect(
      harness.service.edit(entryId, change as unknown as Parameters<typeof harness.service.edit>[1]),
    ).rejects.toBeInstanceOf(InvalidEditError);
    expect((await metadataOf(entryId)).media).toHaveLength(2);
    expect(await harness.outboxKinds()).toEqual(['createEntry']);
  });

  it('refuses removeMediaId passed to edit', async () => {
    const entryId = await harness.createEntry([fakeJpeg(PHOTO_BYTES), fakeJpeg(PHOTO_BYTES, 2)]);
    const mediaId = (await rowsOf(entryId)).media[0]?.id ?? '';
    const change = { removeMediaId: mediaId };
    await expect(
      harness.service.edit(entryId, change as unknown as Parameters<typeof harness.service.edit>[1]),
    ).rejects.toBeInstanceOf(InvalidEditError);
    expect((await metadataOf(entryId)).media).toHaveLength(2);
    expect(await harness.outboxKinds()).toEqual(['createEntry']);
  });

  it('re-reads and keeps a change that landed between its read and its write', async () => {
    const entryId = await harness.createEntry([fakeJpeg(PHOTO_BYTES)], { note: 'base' });
    await harness.markSynced(entryId);
    harness.hooks.beforeKey = async () => {
      harness.hooks.beforeKey = null;
      await writeConcurrentNote(entryId, 'from elsewhere');
    };
    await harness.service.edit(entryId, { tags: ['x'] });
    expect(await metadataOf(entryId)).toMatchObject({ note: 'from elsewhere', tags: ['x'] });
  });

  it('gives up after EDIT_RETRY_ATTEMPTS when the entry keeps changing', async () => {
    const entryId = await harness.createEntry([fakeJpeg(PHOTO_BYTES)], { note: 'base' });
    await harness.markSynced(entryId);
    let round = 0;
    harness.hooks.beforeKey = async () => {
      round += 1;
      await writeConcurrentNote(entryId, `change ${round}`);
    };
    await expect(harness.service.edit(entryId, { tags: ['x'] })).rejects.toBeInstanceOf(
      EntryChangedError,
    );
    expect(round).toBe(EDIT_RETRY_ATTEMPTS);
    harness.hooks.beforeKey = null;
    expect((await metadataOf(entryId)).tags).toEqual([]);
  });

  it('removeMedia and addMedia also keep a concurrent change', async () => {
    const entryId = await harness.createEntry([fakeJpeg(PHOTO_BYTES, 1), fakeJpeg(PHOTO_BYTES, 2)]);
    await harness.markSynced(entryId);
    const mediaId = (await rowsOf(entryId)).media[0]?.id ?? '';
    harness.hooks.beforeKey = async () => {
      harness.hooks.beforeKey = null;
      await writeConcurrentNote(entryId, 'kept 1');
    };
    await harness.service.removeMedia(entryId, mediaId);
    expect(await metadataOf(entryId)).toMatchObject({ note: 'kept 1' });
    expect((await metadataOf(entryId)).media).toHaveLength(1);

    await harness.importIntoTray(fakeJpeg(PHOTO_BYTES, 3));
    harness.hooks.beforeKey = async () => {
      harness.hooks.beforeKey = null;
      await writeConcurrentNote(entryId, 'kept 2');
    };
    await harness.service.addMedia(entryId, [...harness.tray.items]);
    expect(await metadataOf(entryId)).toMatchObject({ note: 'kept 2' });
    expect((await metadataOf(entryId)).media).toHaveLength(2);
  });

  it('refuses an invalid edit of the media order', async () => {
    const entryId = await harness.createEntry([fakeJpeg(PHOTO_BYTES)]);
    await expect(harness.service.edit(entryId, { mediaOrder: ['AAAAAAAAAAAAAAAAAAAAAA'] })).rejects.toBeInstanceOf(
      InvalidEditError,
    );
  });

  it('fails with the locked error when the journal is locked', async () => {
    const entryId = await harness.createEntry([fakeJpeg(PHOTO_BYTES)]);
    harness.lock();
    await expect(harness.service.edit(entryId, { note: 'x' })).rejects.toBeInstanceOf(
      AccountLockedError,
    );
  });
});

describe('edit between the createEntry POST and the upload confirmation', () => {
  it('posts blob A, then patches blob B, and ends synced with base B', async () => {
    const entryId = await harness.createEntry([fakeJpeg(PHOTO_BYTES)], { note: 'A' });
    const blobA = (await harness.entryRow(entryId)).metadataBlob;
    const http = createFakeHttp();
    const events = createEventBus<AppEvents>();
    const deps: ExecutorDeps = {
      http: http.http,
      rowStore: harness.fixture.rowStore,
      fileStore: harness.fixture.fileStore,
      events,
      log: { warn: () => undefined },
      uploads: { enqueueObjectUploads: async () => undefined },
      conflict: { resolve: async () => 'adopted' },
    };
    const dto = (revision: number, blob: Uint8Array) => ({
      id: entryId,
      ownerId: SERVER_OWNER_ID,
      purpose: 'journal',
      revision,
      metadataBlob: encodeBytes(blob),
      trashedAt: null,
      createdAt: FIRST_NOW_ISO,
      updatedAt: FIRST_NOW_ISO,
    });
    http.on('POST', ROUTES.entries(), () => dto(1, blobA));
    const queued = await outbox();
    const createItem = queued.find((row) => row.kind === 'createEntry');
    if (createItem === undefined) throw new Error('createEntry item missing');

    expect(await EXECUTORS.createEntry(createItem, deps)).toBe('waiting');

    harness.clock.advance(STEP_MS);
    await harness.service.edit(entryId, { note: 'B' });
    const blobB = (await harness.entryRow(entryId)).metadataBlob;
    expect(bytesEqual(blobA, blobB)).toBe(false);
    await harness.fixture.rowStore.transaction(async (tx) => {
      for (const object of await tx.objects.all()) {
        await tx.objects.put({ ...object, uploadState: 'uploaded' });
      }
    });
    http.on('PATCH', ROUTES.entry(entryId), () => dto(2, blobB));

    expect(await EXECUTORS.createEntry(createItem, deps)).toBe('done');
    expect((await harness.entryRow(entryId)).localState).toBe('pending_update');
    const updateItem = (await outbox()).find((row) => row.kind === 'updateEntryMeta');
    if (updateItem === undefined) throw new Error('updateEntryMeta item missing');
    expect(await EXECUTORS.updateEntryMeta(updateItem, deps)).toBe('done');

    expect(http.requests.map((request) => request.method)).toEqual(['POST', 'PATCH']);
    expect(http.requests[0]?.body).toMatchObject({ metadataBlob: encodeBytes(blobA) });
    expect(http.requests[1]?.body).toEqual({ metadataBlob: encodeBytes(blobB), baseRevision: 1 });
    const final = await harness.entryRow(entryId);
    expect(final.localState).toBe('synced');
    expect(final.baseRevision).toBe(2);
    expect(bytesEqual(final.baseMetadataBlob ?? new Uint8Array(), blobB)).toBe(true);
  });
});

describe('trash and restore', () => {
  it('B-025: trash then restore offline leaves no outbox items of the pair', async () => {
    const entryId = await harness.createEntry([fakeJpeg(PHOTO_BYTES)]);
    await harness.markSynced(entryId);

    await harness.service.trash([entryId]);
    expect((await harness.entryRow(entryId)).trashedAt).toBe(FIRST_NOW_ISO);
    expect(await harness.outboxKinds()).toEqual(['trashEntry']);

    await harness.service.restore([entryId]);
    expect((await harness.entryRow(entryId)).trashedAt).toBeNull();
    expect(await outbox()).toHaveLength(0);
  });

  it('B-025: restore then trash offline also cancels', async () => {
    const entryId = await harness.createEntry([fakeJpeg(PHOTO_BYTES)]);
    await harness.markSynced(entryId);
    await harness.service.trash([entryId]);
    await harness.markSynced(entryId);
    await harness.service.restore([entryId]);
    expect(await harness.outboxKinds()).toEqual(['restoreEntry']);
    await harness.service.trash([entryId]);
    expect(await outbox()).toHaveLength(0);
  });

  it('queues one trashEntry for an entry that was synced', async () => {
    const entryId = await harness.createEntry([fakeJpeg(PHOTO_BYTES)]);
    await harness.markSynced(entryId);
    await harness.service.trash([entryId]);
    expect(await harness.outboxKinds()).toEqual(['trashEntry']);
    expect((await harness.entryRow(entryId)).localState).toBe('synced');
  });

  it('trashes several entries in one call and notifies once for all of them', async () => {
    const first = await harness.createEntry([fakeJpeg(PHOTO_BYTES)]);
    const second = await harness.createEntry([fakeJpeg(PHOTO_BYTES)]);
    await harness.markSynced(first);
    await harness.markSynced(second);
    harness.changed.length = 0;
    await harness.service.trash([first, second]);
    expect(await harness.outboxKinds()).toEqual(['trashEntry', 'trashEntry']);
    expect(harness.changed).toEqual([[first, second]]);
  });

  it('does not queue a second trashEntry for an entry that is already trashed', async () => {
    const entryId = await harness.createEntry([fakeJpeg(PHOTO_BYTES)]);
    await harness.markSynced(entryId);
    await harness.service.trash([entryId]);
    await harness.service.trash([entryId]);
    expect(await harness.outboxKinds()).toEqual(['trashEntry']);
  });

  it('enqueues its own kind when the opposite item was already attempted', async () => {
    const entryId = await harness.createEntry([fakeJpeg(PHOTO_BYTES)]);
    await harness.markSynced(entryId);
    await harness.service.trash([entryId]);
    await setAttempts('trashEntry', 1);
    await harness.service.restore([entryId]);
    expect(await harness.outboxKinds()).toEqual(['trashEntry', 'restoreEntry']);
  });

  it('enqueues its own kind when the opposite item is dead', async () => {
    const entryId = await harness.createEntry([fakeJpeg(PHOTO_BYTES)]);
    await harness.markSynced(entryId);
    await harness.service.trash([entryId]);
    await harness.fixture.rowStore.transaction(async (tx) => {
      for (const item of await tx.outbox.all()) await tx.outbox.update({ ...item, state: 'dead' });
    });
    await harness.service.restore([entryId]);
    expect(await harness.outboxKinds()).toEqual(['trashEntry', 'restoreEntry']);
  });

  it('enqueues its own kind when the entry is in flight', async () => {
    const entryId = await harness.createEntry([fakeJpeg(PHOTO_BYTES)]);
    await harness.markSynced(entryId);
    await harness.service.trash([entryId]);
    harness.hooks.inFlight.add(entryId);
    await harness.service.restore([entryId]);
    expect(await harness.outboxKinds()).toEqual(['trashEntry', 'restoreEntry']);
  });

  it('returns changed and skipped counts', async () => {
    const first = await harness.createEntry([fakeJpeg(PHOTO_BYTES)]);
    const second = await harness.createEntry([fakeJpeg(PHOTO_BYTES)]);
    const foreign = await harness.createEntry([fakeJpeg(PHOTO_BYTES)]);
    await harness.fixture.rowStore.transaction(async (tx) => {
      const row = await tx.entries.get(foreign);
      if (row !== null) await tx.entries.put({ ...row, ownerId: OTHER_USER });
    });
    await harness.service.trash([first]);
    expect(await harness.service.trash([first, second, foreign, 'ZZZZZZZZZZZZZZZZZZZZZZ'])).toEqual({
      changed: 1,
      skipped: 3,
    });
    expect(await harness.service.restore([first, second])).toEqual({ changed: 2, skipped: 0 });
    expect(await harness.service.deletePermanently([first, foreign])).toEqual({ changed: 1, skipped: 1 });
  });

  it('skips entries that are not owned', async () => {
    const entryId = await harness.createEntry([fakeJpeg(PHOTO_BYTES)]);
    await harness.markSynced(entryId);
    await harness.fixture.rowStore.transaction(async (tx) => {
      const row = await tx.entries.get(entryId);
      if (row !== null) await tx.entries.put({ ...row, ownerId: OTHER_USER });
    });
    await harness.service.trash([entryId]);
    expect((await harness.entryRow(entryId)).trashedAt).toBeNull();
    expect(await outbox()).toHaveLength(0);
  });
});

describe('deletePermanently', () => {
  it('queues nothing for an entry the server never received, and deletes its files', async () => {
    const entryId = await harness.createEntry([fakeJpeg(PHOTO_BYTES)]);
    const objectIds = (await rowsOf(entryId)).objects.map((object) => object.id);
    expect(objectIds).toHaveLength(2);

    await harness.service.deletePermanently([entryId]);

    expect(await outbox()).toHaveLength(0);
    const after = await rowsOf(entryId);
    expect(after).toMatchObject({ entry: null, memberships: [], media: [], objects: [] });
    for (const objectId of objectIds) {
      expect(await harness.fixture.fileStore.size(objectId)).toBeNull();
    }
    expect(harness.changed.at(-1)).toEqual([entryId]);
  });

  it('queues one deleteEntry for a synced entry and removes rows and files', async () => {
    const entryId = await harness.createEntry([fakeJpeg(PHOTO_BYTES)]);
    const objectIds = (await rowsOf(entryId)).objects.map((object) => object.id);
    await harness.markSynced(entryId);
    await harness.service.edit(entryId, { note: 'queued edit' });

    await harness.service.deletePermanently([entryId]);

    const items = await outbox();
    expect(items.map((item) => item.kind)).toEqual(['deleteEntry']);
    expect(items[0]?.payload).toEqual({ entryId });
    expect(await rowsOf(entryId)).toMatchObject({ entry: null, media: [], objects: [] });
    for (const objectId of objectIds) {
      expect(await harness.fixture.fileStore.size(objectId)).toBeNull();
    }
  });

  it('queues deleteEntry for an entry that is pending_create but already posted', async () => {
    const entryId = await harness.createEntry([fakeJpeg(PHOTO_BYTES)]);
    await harness.fixture.rowStore.transaction(async (tx) => {
      const row = await tx.entries.get(entryId);
      if (row !== null) await tx.entries.put({ ...row, baseRevision: 1, baseMetadataBlob: row.metadataBlob });
    });
    expect((await harness.entryRow(entryId)).localState).toBe('pending_create');

    await harness.service.deletePermanently([entryId]);

    expect(await harness.outboxKinds()).toEqual(['deleteEntry']);
  });

  it('queues deleteEntry when the createEntry item was already attempted', async () => {
    const entryId = await harness.createEntry([fakeJpeg(PHOTO_BYTES)]);
    await setAttempts('createEntry', 1);
    await harness.service.deletePermanently([entryId]);
    expect(await harness.outboxKinds()).toEqual(['deleteEntry']);
  });

  it('queues deleteEntry when the entry is in flight', async () => {
    const entryId = await harness.createEntry([fakeJpeg(PHOTO_BYTES)]);
    harness.hooks.inFlight.add(entryId);
    await harness.service.deletePermanently([entryId]);
    expect(await harness.outboxKinds()).toEqual(['deleteEntry']);
  });

  it('wipes the cached entry and media keys', async () => {
    const entryId = await harness.createEntry([fakeJpeg(PHOTO_BYTES)]);
    const mediaId = (await rowsOf(entryId)).media[0]?.id ?? '';
    const entryKey = await harness.entryKeys.entryKey(entryId);
    const mediaKey = await harness.entryKeys.mediaKey(entryId, mediaId);
    await harness.service.deletePermanently([entryId]);
    expect(entryKey.every((byte) => byte === 0)).toBe(true);
    expect(mediaKey.every((byte) => byte === 0)).toBe(true);
  });

  it('deletes the plain thumbnail files too', async () => {
    const entryId = await harness.createEntry([fakeJpeg(PHOTO_BYTES)]);
    const mediaId = (await rowsOf(entryId)).media[0]?.id ?? '';
    await harness.fixture.fileStore.writePlainThumbnail(mediaId, Uint8Array.of(1));
    await harness.service.deletePermanently([entryId]);
    expect(harness.fixture.fileStore.plainThumbnailUri(mediaId)).toBeNull();
  });

  it('skips entries that are not owned', async () => {
    const entryId = await harness.createEntry([fakeJpeg(PHOTO_BYTES)]);
    await harness.fixture.rowStore.transaction(async (tx) => {
      const row = await tx.entries.get(entryId);
      if (row !== null) await tx.entries.put({ ...row, ownerId: OTHER_USER });
    });
    await harness.service.deletePermanently([entryId]);
    expect((await rowsOf(entryId)).entry).not.toBeNull();
  });
});

describe('removeMedia', () => {
  it('B-027: keeps two photos when one of three is removed', async () => {
    const entryId = await harness.createEntry([
      fakeJpeg(PHOTO_BYTES, 1),
      fakeJpeg(PHOTO_BYTES, 2),
      fakeJpeg(PHOTO_BYTES, 3),
    ]);
    const before = await rowsOf(entryId);
    const removedId = before.media[1]?.id ?? '';
    const removedObjects = before.objects.filter((object) => object.mediaId === removedId);
    await harness.markSynced(entryId);

    await harness.service.removeMedia(entryId, removedId);

    const after = await rowsOf(entryId);
    expect(after.media.map((row) => row.id)).not.toContain(removedId);
    expect(after.media).toHaveLength(2);
    expect((await metadataOf(entryId)).media).toHaveLength(2);
    expect(removedObjects).toHaveLength(2);
    for (const object of removedObjects) {
      expect(await harness.fixture.fileStore.size(object.id)).toBeNull();
    }
    const items = await outbox();
    expect(items.map((item) => item.kind)).toEqual(['updateEntryMeta', 'removeMedia']);
    expect(items[1]?.payload).toEqual({ entryId, mediaId: removedId });
  });

  it('wipes the cached key of the removed media only', async () => {
    const entryId = await harness.createEntry([fakeJpeg(PHOTO_BYTES, 1), fakeJpeg(PHOTO_BYTES, 2)]);
    const [removed, kept] = (await rowsOf(entryId)).media.map((row) => row.id);
    const removedKey = await harness.entryKeys.mediaKey(entryId, removed ?? '');
    const keptKey = await harness.entryKeys.mediaKey(entryId, kept ?? '');
    await harness.service.removeMedia(entryId, removed ?? '');
    expect(removedKey.every((byte) => byte === 0)).toBe(true);
    expect(keptKey.some((byte) => byte !== 0)).toBe(true);
  });

  it('B-027: refuses to remove the last media item', async () => {
    const entryId = await harness.createEntry([fakeJpeg(PHOTO_BYTES)]);
    const mediaId = (await rowsOf(entryId)).media[0]?.id ?? '';
    await expect(harness.service.removeMedia(entryId, mediaId)).rejects.toBeInstanceOf(
      InvalidEditError,
    );
    const after = await rowsOf(entryId);
    expect(after.media).toHaveLength(1);
    expect(after.objects).toHaveLength(2);
    expect(await harness.outboxKinds()).toEqual(['createEntry']);
  });
});

describe('addMedia', () => {
  it('queues addMedia items and then one updateEntryMeta', async () => {
    const entryId = await harness.createEntry([fakeJpeg(PHOTO_BYTES, 1)]);
    await harness.markSynced(entryId);
    await harness.importIntoTray(fakeJpeg(PHOTO_BYTES, 2));
    await harness.importIntoTray(fakeJpeg(PHOTO_BYTES, 3));
    const items = [...harness.tray.items];

    await harness.service.addMedia(entryId, items);

    const queued = await outbox();
    expect(queued.map((row) => row.kind)).toEqual(['addMedia', 'addMedia', 'updateEntryMeta']);
    expect(queued.slice(0, 2).map((row) => row.payload)).toEqual(
      items.map((item) => ({ entryId, mediaId: item.mediaId })),
    );
    const rows = await rowsOf(entryId);
    expect(rows.media).toHaveLength(3);
    expect(rows.objects).toHaveLength(6);
    const added = items.map((item) => item.mediaId);
    expect((await metadataOf(entryId)).media.map((item) => item.mediaId).slice(1)).toEqual(added);
    expect((await harness.entryRow(entryId)).localState).toBe('pending_update');
  });

  it('wraps each new media key with the entry key and wipes the tray copy after commit', async () => {
    const entryId = await harness.createEntry([fakeJpeg(PHOTO_BYTES, 1)]);
    const mediaId = await harness.importIntoTray(fakeJpeg(PHOTO_BYTES, 2));
    const items = [...harness.tray.items];
    const keyCopy = items[0]?.mediaKey.slice() ?? new Uint8Array();

    await harness.service.addMedia(entryId, items);

    const row = (await rowsOf(entryId)).media.find((media) => media.id === mediaId);
    const entryKey = await harness.entryKeys.entryKey(entryId);
    const unwrapped = unwrapKey(
      sodium,
      entryKey,
      row?.mediaKeyWrap ?? new Uint8Array(),
      AD_CONTEXTS.mediaKeyByEntry,
      `${entryId}/${mediaId}`,
    );
    expect([...unwrapped]).toEqual([...keyCopy]);
    expect(items[0]?.mediaKey.every((byte) => byte === 0)).toBe(true);
  });

  it('refuses an empty list of media', async () => {
    const entryId = await harness.createEntry([fakeJpeg(PHOTO_BYTES, 1)]);
    await expect(harness.service.addMedia(entryId, [])).rejects.toBeInstanceOf(InvalidEditError);
    expect(await harness.outboxKinds()).toEqual(['createEntry']);
  });

  it('refuses media beyond maxMediaPerEntry', async () => {
    const entryId = await harness.createEntry([fakeJpeg(PHOTO_BYTES, 1), fakeJpeg(PHOTO_BYTES, 2)]);
    harness.hooks.maxMediaPerEntry = 3;
    await harness.importIntoTray(fakeJpeg(PHOTO_BYTES, 3));
    await harness.importIntoTray(fakeJpeg(PHOTO_BYTES, 4));
    await expect(harness.service.addMedia(entryId, [...harness.tray.items])).rejects.toBeInstanceOf(
      InvalidEditError,
    );
    expect((await rowsOf(entryId)).media).toHaveLength(2);
    await harness.service.addMedia(entryId, harness.tray.items.slice(0, 1));
    expect((await rowsOf(entryId)).media).toHaveLength(3);
  });
});

describe('tag edits on many entries', () => {
  it('B-023: adds a tag to 3 entries where one already has it: changed 2, skipped 1', async () => {
    const first = await harness.createEntry([fakeJpeg(PHOTO_BYTES)], { tags: ['a'] });
    const second = await harness.createEntry([fakeJpeg(PHOTO_BYTES)], { tags: ['trip', 'a'] });
    const third = await harness.createEntry([fakeJpeg(PHOTO_BYTES)]);
    for (const id of [first, second, third]) await harness.markSynced(id);

    const result = await harness.service.addTagToMany([first, second, third], ' Trip ');

    expect(result).toEqual({ changed: 2, skipped: 1 });
    for (const id of [first, second, third]) {
      expect((await metadataOf(id)).tags).toContain('trip');
    }
    expect((await metadataOf(first)).tags).toEqual(['a', 'trip']);
    const queued = await outbox();
    expect(queued.map((row) => row.kind)).toEqual(['updateEntryMeta', 'updateEntryMeta']);
    expect(queued.map((row) => row.payload)).toEqual([{ entryId: first }, { entryId: third }]);
  });

  it('skips entries that are not owned', async () => {
    const owned = await harness.createEntry([fakeJpeg(PHOTO_BYTES)]);
    const foreign = await harness.createEntry([fakeJpeg(PHOTO_BYTES)]);
    await harness.fixture.rowStore.transaction(async (tx) => {
      const row = await tx.entries.get(foreign);
      if (row !== null) await tx.entries.put({ ...row, ownerId: OTHER_USER });
    });
    expect(await harness.service.addTagToMany([owned, foreign], 'x')).toEqual({
      changed: 1,
      skipped: 1,
    });
  });

  it('removes a tag from the entries that have it and skips the others', async () => {
    const first = await harness.createEntry([fakeJpeg(PHOTO_BYTES)], { tags: ['a', 'b'] });
    const second = await harness.createEntry([fakeJpeg(PHOTO_BYTES)], { tags: ['b'] });
    const third = await harness.createEntry([fakeJpeg(PHOTO_BYTES)], { tags: ['c'] });
    for (const id of [first, second, third]) await harness.markSynced(id);

    const result = await harness.service.removeTagFromMany([first, second, third], 'B');

    expect(result).toEqual({ changed: 2, skipped: 1 });
    expect((await metadataOf(first)).tags).toEqual(['a']);
    expect((await metadataOf(second)).tags).toEqual([]);
    expect((await metadataOf(third)).tags).toEqual(['c']);
  });

  it('skips every entry for a blank tag', async () => {
    const first = await harness.createEntry([fakeJpeg(PHOTO_BYTES)]);
    expect(await harness.service.addTagToMany([first], '   ')).toEqual({ changed: 0, skipped: 1 });
    expect(await harness.service.removeTagFromMany([first], '   ')).toEqual({ changed: 0, skipped: 1 });
  });

  it('skips an entry whose tag list is full', async () => {
    const full = Array.from({ length: 50 }, (_, index) => `t${String(index).padStart(2, '0')}`);
    const entryId = await harness.createEntry([fakeJpeg(PHOTO_BYTES)], { tags: full });
    expect(await harness.service.addTagToMany([entryId], 'extra')).toEqual({ changed: 0, skipped: 1 });
  });
});

describe('readMetadata', () => {
  it('opens the local blob of an entry', async () => {
    const entryId = await harness.createEntry([fakeJpeg(PHOTO_BYTES)], { note: 'hello' });
    expect((await metadataOf(entryId)).note).toBe('hello');
  });
});
