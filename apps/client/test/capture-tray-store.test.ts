import { beforeEach, describe, expect, it } from 'vitest';
import type { FileStore } from '../src/adapters/filestore/filestore';
import { createCaptureTrayStore } from '../src/features/capture/capture-tray-store';
import type { ImportedMedia } from '../src/services/media/import-service';
import { createMemoryFileStore, descriptorOf } from './support';

const ACCOUNT_KEY = 'host/user';
const KEY_LENGTH = 32;
const NOW_ISO = '2026-11-12T10:00:00.000Z';
const THUMBNAIL_BYTES = Uint8Array.of(1, 2, 3, 4);

let fileStore: FileStore;

beforeEach(async () => {
  fileStore = createMemoryFileStore();
  await fileStore.open(ACCOUNT_KEY);
});

function idFor(prefix: string, number: number): string {
  return `${prefix}${String(number).padStart(22 - prefix.length, '0')}`;
}

async function* oneChunk(): AsyncGenerator<Uint8Array> {
  yield Uint8Array.of(9, 9, 9);
}

async function storedItem(number: number, withPlainThumbnail = true): Promise<ImportedMedia> {
  const mediaId = idFor('m', number);
  const originalObjectId = idFor('o', number);
  const thumbnailObjectId = idFor('t', number);
  await fileStore.writeObject(originalObjectId, oneChunk());
  await fileStore.writeObject(thumbnailObjectId, oneChunk());
  if (withPlainThumbnail) await fileStore.writePlainThumbnail(mediaId, THUMBNAIL_BYTES);
  return {
    mediaId,
    mediaKey: new Uint8Array(KEY_LENGTH).fill(number),
    originalObjectId,
    thumbnailObjectId,
    originalCiphertextBytes: 43,
    thumbnailCiphertextBytes: 43,
    descriptor: descriptorOf(mediaId, 3),
    time: { capturedAtUtc: NOW_ISO, utcOffsetMinutes: 0, source: 'now' },
    location: null,
    thumbnailBytes: THUMBNAIL_BYTES,
  };
}

function idsOf(items: readonly ImportedMedia[]): string[] {
  return items.map((item) => item.mediaId);
}

describe('capture tray store', () => {
  it('starts empty with no device location', () => {
    const tray = createCaptureTrayStore({ fileStore });

    expect(tray.items).toEqual([]);
    expect(tray.deviceLocation).toBeNull();
  });

  it('adds items in order', async () => {
    const tray = createCaptureTrayStore({ fileStore });
    const first = await storedItem(1);
    const second = await storedItem(2);

    tray.add(first);
    tray.add(second);

    expect(idsOf(tray.items)).toEqual([first.mediaId, second.mediaId]);
  });

  it('replaces the items array on every change so earlier snapshots stay unchanged', async () => {
    const tray = createCaptureTrayStore({ fileStore });
    const first = await storedItem(1);
    tray.add(first);
    const snapshot = tray.items;

    tray.add(await storedItem(2));

    expect(snapshot).toHaveLength(1);
    expect(tray.items).not.toBe(snapshot);
    expect(tray.items).toHaveLength(2);
  });

  it('removes an item and deletes both objects and the plain thumbnail', async () => {
    const tray = createCaptureTrayStore({ fileStore });
    const first = await storedItem(1);
    const second = await storedItem(2);
    tray.add(first);
    tray.add(second);

    await tray.remove(first.mediaId);

    expect(idsOf(tray.items)).toEqual([second.mediaId]);
    expect(await fileStore.size(first.originalObjectId)).toBeNull();
    expect(await fileStore.size(first.thumbnailObjectId)).toBeNull();
    expect(fileStore.plainThumbnailUri(first.mediaId)).toBeNull();
    expect(await fileStore.size(second.originalObjectId)).not.toBeNull();
    expect(await fileStore.size(second.thumbnailObjectId)).not.toBeNull();
    expect(fileStore.plainThumbnailUri(second.mediaId)).not.toBeNull();
  });

  it('removes an item that has no plain thumbnail', async () => {
    const tray = createCaptureTrayStore({ fileStore });
    const only = await storedItem(1, false);
    tray.add(only);

    await tray.remove(only.mediaId);

    expect(tray.items).toEqual([]);
    expect(await fileStore.size(only.originalObjectId)).toBeNull();
  });

  it.each([
    [0, 2, ['b', 'c', 'a']],
    [2, 0, ['c', 'a', 'b']],
    [0, 1, ['b', 'a', 'c']],
    [1, 1, ['a', 'b', 'c']],
  ] as const)('moves the item at %i to position %i', async (from, to, expected) => {
    const tray = createCaptureTrayStore({ fileStore });
    const items = [await storedItem(1), await storedItem(2), await storedItem(3)];
    const names = new Map(items.map((item, index) => [item.mediaId, ['a', 'b', 'c'][index] ?? '']));
    for (const item of items) tray.add(item);

    tray.reorder(from, to);

    expect(idsOf(tray.items).map((id) => names.get(id))).toEqual(expected);
  });

  it('clears the list and deletes every file when asked to', async () => {
    const tray = createCaptureTrayStore({ fileStore });
    const items = [await storedItem(1), await storedItem(2)];
    for (const item of items) tray.add(item);

    await tray.clear(true);

    expect(tray.items).toEqual([]);
    expect((await fileStore.listSizes()).size).toBe(0);
    for (const item of items) expect(fileStore.plainThumbnailUri(item.mediaId)).toBeNull();
  });

  it('clears the list but keeps the files when they now belong to an entry', async () => {
    const tray = createCaptureTrayStore({ fileStore });
    const items = [await storedItem(1), await storedItem(2)];
    for (const item of items) tray.add(item);

    await tray.clear(false);

    expect(tray.items).toEqual([]);
    expect((await fileStore.listSizes()).size).toBe(4);
    for (const item of items) expect(fileStore.plainThumbnailUri(item.mediaId)).not.toBeNull();
  });

  it('increments the generation on every clear, not on add or remove', async () => {
    const tray = createCaptureTrayStore({ fileStore });
    const first = await storedItem(1);
    const start = tray.generation;

    tray.add(first);
    await tray.remove(first.mediaId);
    expect(tray.generation).toBe(start);

    await tray.clear(false);
    expect(tray.generation).toBeGreaterThan(start);
    const afterFirst = tray.generation;

    await tray.clear(true);
    expect(tray.generation).toBeGreaterThan(afterFirst);
  });

  it('adds an import that finishes in the current generation', async () => {
    const tray = createCaptureTrayStore({ fileStore });
    const item = await storedItem(1);

    const added = await tray.addImported(item, tray.generation);

    expect(added).toBe(true);
    expect(idsOf(tray.items)).toEqual([item.mediaId]);
  });

  it('deletes the files and wipes the key of an import that finishes after a clear', async () => {
    const tray = createCaptureTrayStore({ fileStore });
    const item = await storedItem(1);
    const started = tray.generation;
    await tray.clear(true);

    const added = await tray.addImported(item, started);

    expect(added).toBe(false);
    expect(tray.items).toEqual([]);
    expect(await fileStore.size(item.originalObjectId)).toBeNull();
    expect(await fileStore.size(item.thumbnailObjectId)).toBeNull();
    expect(fileStore.plainThumbnailUri(item.mediaId)).toBeNull();
    expect([...item.mediaKey].every((byte) => byte === 0)).toBe(true);
  });

  it('rejects an import while the account is not unlocked', async () => {
    let unlocked = false;
    const tray = createCaptureTrayStore({ fileStore, isUnlocked: () => unlocked });
    const item = await storedItem(1);

    const added = await tray.addImported(item, tray.generation);

    expect(added).toBe(false);
    expect(tray.items).toEqual([]);
    expect(await fileStore.size(item.originalObjectId)).toBeNull();
    expect([...item.mediaKey].every((byte) => byte === 0)).toBe(true);

    unlocked = true;
    const second = await storedItem(2);
    expect(await tray.addImported(second, tray.generation)).toBe(true);
  });

  it('keeps the device location of the capture session', () => {
    const tray = createCaptureTrayStore({ fileStore });
    const location = { point: { latitude: 48.8584, longitude: 2.2945 }, accuracyMeters: 12 };

    tray.setDeviceLocation(location);
    expect(tray.deviceLocation).toEqual(location);

    tray.setDeviceLocation(null);
    expect(tray.deviceLocation).toBeNull();
  });

  it('notifies subscribers on add, remove, reorder and clear and stops after unsubscribe', async () => {
    const tray = createCaptureTrayStore({ fileStore });
    let notifications = 0;
    const unsubscribe = tray.subscribe(() => {
      notifications += 1;
    });
    const first = await storedItem(1);
    const second = await storedItem(2);

    const counts: number[] = [];

    tray.add(first);
    counts.push(notifications);
    tray.add(second);
    counts.push(notifications);
    tray.reorder(0, 1);
    counts.push(notifications);
    await tray.remove(first.mediaId);
    counts.push(notifications);
    await tray.clear(true);
    counts.push(notifications);

    expect(counts[0]).toBeGreaterThan(0);
    for (let index = 1; index < counts.length; index += 1) {
      expect(counts[index]).toBeGreaterThan(counts[index - 1] ?? 0);
    }

    unsubscribe();
    const before = notifications;
    tray.add(await storedItem(3));
    expect(notifications).toBe(before);
  });
});
