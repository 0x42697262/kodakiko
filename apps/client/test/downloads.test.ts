import { beforeEach, describe, expect, it } from 'vitest';
import type { Sodium } from '@kodakiko/core';
import { createNodeSodium } from '../../../packages/core/test/sodium-node';
import { DownloadHttpError } from '../src/adapters/filestore/download-errors';
import { ApiError, type HttpClient } from '../src/adapters/http/http';
import { createMemoryFileStore } from '../src/adapters/memory/memory-file-store';
import type { ObjectRow } from '../src/adapters/rowstore/rowstore';
import { createDownloader, type Downloader } from '../src/services/downloads/downloader';
import { createDownloadingOriginalSource, type OriginalsService } from '../src/services/originals/originals-service';
import { createCachingThumbnailSource, type ThumbnailCacheService } from '../src/services/thumbnails/thumbnail-cache';
import { createWifiUploadPolicy } from '../src/services/downloads/upload-policy';
import { createEntriesHarness, fakeJpeg, type EntriesHarness } from './support/entries-harness';

type BucketFailure = 'notFound' | 'serverError' | 'wrongSize';

type FakeBackend = {
  readonly http: HttpClient;
  readonly bucket: Map<string, Uint8Array>;
  readonly failures: Map<string, BucketFailure[]>;
  readonly urlRequests: Map<string, number>;
};

function createFakeBackend(): FakeBackend {
  const bucket = new Map<string, Uint8Array>();
  const failures = new Map<string, BucketFailure[]>();
  const urlRequests = new Map<string, number>();
  const http: HttpClient = {
    request: async (call) => {
      const match = /\/objects\/([^/]+)\/download$/.exec(call.path);
      if (call.method !== 'GET' || match === null) throw new Error(`unexpected ${call.method} ${call.path}`);
      const id = match[1] ?? '';
      urlRequests.set(id, (urlRequests.get(id) ?? 0) + 1);
      if (failures.get(id)?.[0] === 'notFound') throw new ApiError(404, 'NOT_FOUND', null, null);
      return call.responseSchema.parse({
        url: `https://bucket.example/${id}?n=${urlRequests.get(id)}`,
        expiresAt: '2030-01-01T00:00:00.000Z',
      });
    },
  };
  return { http, bucket, failures, urlRequests };
}

function bucketFetcher(backend: FakeBackend) {
  return async (url: string): Promise<Uint8Array> => {
    const id = new URL(url).pathname.slice(1);
    const failure = backend.failures.get(id)?.shift();
    if (failure === 'serverError') throw new DownloadHttpError(503);
    const bytes = backend.bucket.get(id);
    if (bytes === undefined) throw new DownloadHttpError(404);
    return failure === 'wrongSize' ? bytes.slice(1) : bytes.slice();
  };
}


let sodium: Sodium;
let harness: EntriesHarness;
let backend: FakeBackend;
let downloader: Downloader;
let thumbnails: ThumbnailCacheService;
let originals: OriginalsService;
let settings: { encryptThumbnails: boolean; originalCacheCapBytes: number };
let sleeps: number[];

async function objectRows(mediaId: string): Promise<readonly ObjectRow[]> {
  return harness.fixture.rowStore.transaction((tx) => tx.objects.byMedia(mediaId));
}

async function moveToServer(entryId: string): Promise<void> {
  const metadata = await harness.service.readMetadata(entryId);
  for (const media of metadata.media) {
    for (const row of await objectRows(media.mediaId)) {
      const size = (await harness.fixture.fileStore.size(row.id)) ?? 0;
      backend.bucket.set(row.id, await harness.fixture.fileStore.readRange(row.id, 0, size));
      await harness.fixture.fileStore.delete(row.id);
      await harness.fixture.rowStore.transaction((tx) =>
        tx.objects.put({ ...row, localState: 'missing', uploadState: 'uploaded' }),
      );
    }
    await harness.fixture.fileStore.deletePlainThumbnail(media.mediaId);
  }
  await harness.markSynced(entryId);
}

beforeEach(async () => {
  sodium = await createNodeSodium();
  backend = createFakeBackend();
  harness = await createEntriesHarness(sodium, {
    fileStore: createMemoryFileStore({ fetchBytes: bucketFetcher(backend) }),
  });
  sleeps = [];
  settings = { encryptThumbnails: false, originalCacheCapBytes: 0 };
  downloader = createDownloader({
    http: backend.http,
    rowStore: harness.fixture.rowStore,
    fileStore: harness.fixture.fileStore,
    clock: harness.clock,
    sleep: async (ms) => {
      sleeps.push(ms);
    },
    isOffline: () => false,
  });
  thumbnails = createCachingThumbnailSource({
    local: harness.thumbnails,
    downloader,
    rowStore: harness.fixture.rowStore,
    fileStore: harness.fixture.fileStore,
    entryKeys: harness.entryKeys,
    sodium,
    settings: () => settings,
  });
  originals = createDownloadingOriginalSource({
    local: harness.originals,
    downloader,
    rowStore: harness.fixture.rowStore,
    fileStore: harness.fixture.fileStore,
    settings: () => settings,
    isWifi: async () => true,
  });
});

describe('downloader', () => {
  it('downloads an object once even when three callers ask at the same time', async () => {
    const entryId = await harness.createEntry([fakeJpeg(2000)]);
    await moveToServer(entryId);
    const [media] = (await harness.service.readMetadata(entryId)).media;
    const thumb = (await objectRows(media?.mediaId ?? '')).find((row) => row.variant === 'thumbnail');
    await Promise.all([1, 2, 3].map(() => downloader.fetchObject(thumb?.id ?? '', 'visible')));
    expect(backend.urlRequests.get(thumb?.id ?? '')).toBe(1);
    const after = (await objectRows(media?.mediaId ?? '')).find((row) => row.variant === 'thumbnail');
    expect(after?.localState).toBe('present');
  });

  it('marks an object unavailable on 404 and does not retry', async () => {
    const entryId = await harness.createEntry([fakeJpeg(2000)]);
    await moveToServer(entryId);
    const [media] = (await harness.service.readMetadata(entryId)).media;
    const thumb = (await objectRows(media?.mediaId ?? '')).find((row) => row.variant === 'thumbnail');
    backend.failures.set(thumb?.id ?? '', ['notFound']);
    await expect(downloader.fetchObject(thumb?.id ?? '', 'visible')).rejects.toThrow();
    expect(backend.urlRequests.get(thumb?.id ?? '')).toBe(1);
    const after = (await objectRows(media?.mediaId ?? '')).find((row) => row.variant === 'thumbnail');
    expect(after?.localState).toBe('unavailable');
  });

  it('retries a 503 twice with fresh URLs and then succeeds', async () => {
    const entryId = await harness.createEntry([fakeJpeg(2000)]);
    await moveToServer(entryId);
    const [media] = (await harness.service.readMetadata(entryId)).media;
    const thumb = (await objectRows(media?.mediaId ?? '')).find((row) => row.variant === 'thumbnail');
    backend.failures.set(thumb?.id ?? '', ['serverError', 'serverError']);
    await downloader.fetchObject(thumb?.id ?? '', 'visible');
    expect(backend.urlRequests.get(thumb?.id ?? '')).toBe(3);
    expect(sleeps).toEqual([2_000, 8_000]);
  });

  it('deletes a download whose size does not match and keeps the object missing', async () => {
    const entryId = await harness.createEntry([fakeJpeg(2000)]);
    await moveToServer(entryId);
    const [media] = (await harness.service.readMetadata(entryId)).media;
    const thumb = (await objectRows(media?.mediaId ?? '')).find((row) => row.variant === 'thumbnail');
    backend.failures.set(thumb?.id ?? '', ['wrongSize']);
    await expect(downloader.fetchObject(thumb?.id ?? '', 'visible')).rejects.toThrow();
    expect(await harness.fixture.fileStore.size(thumb?.id ?? '')).toBeNull();
    const after = (await objectRows(media?.mediaId ?? '')).find((row) => row.variant === 'thumbnail');
    expect(after?.localState).toBe('missing');
  });
});

describe('thumbnail cache', () => {
  it('plain mode keeps exactly one plain file and no encrypted copy', async () => {
    const entryId = await harness.createEntry([fakeJpeg(2000)]);
    await moveToServer(entryId);
    const [media] = (await harness.service.readMetadata(entryId)).media;
    const mediaId = media?.mediaId ?? '';
    await Promise.all([1, 2, 3].map(() => thumbnails.get(entryId, mediaId)));
    const thumb = (await objectRows(mediaId)).find((row) => row.variant === 'thumbnail');
    expect(backend.urlRequests.get(thumb?.id ?? '')).toBe(1);
    expect(harness.fixture.fileStore.plainThumbnailUri(mediaId)).not.toBeNull();
    expect(await harness.fixture.fileStore.size(thumb?.id ?? '')).toBeNull();
    expect(thumb?.localState).toBe('missing');
    expect(await thumbnails.get(entryId, mediaId)).not.toBeNull();
  });

  it('encrypted mode keeps the encrypted object only', async () => {
    settings.encryptThumbnails = true;
    const entryId = await harness.createEntry([fakeJpeg(2000)]);
    await moveToServer(entryId);
    const mediaId = (await harness.service.readMetadata(entryId)).media[0]?.mediaId ?? '';
    await thumbnails.get(entryId, mediaId);
    const thumb = (await objectRows(mediaId)).find((row) => row.variant === 'thumbnail');
    expect(harness.fixture.fileStore.plainThumbnailUri(mediaId)).toBeNull();
    expect(thumb?.localState).toBe('present');
  });

  it('afterUpload drops the encrypted copy in plain mode and keeps it in encrypted mode', async () => {
    const entryId = await harness.createEntry([fakeJpeg(2000)]);
    const mediaId = (await harness.service.readMetadata(entryId)).media[0]?.mediaId ?? '';
    const thumb = (await objectRows(mediaId)).find((row) => row.variant === 'thumbnail');
    settings.encryptThumbnails = true;
    await thumbnails.afterUpload(thumb?.id ?? '');
    expect(await harness.fixture.fileStore.size(thumb?.id ?? '')).not.toBeNull();
    settings.encryptThumbnails = false;
    await thumbnails.afterUpload(thumb?.id ?? '');
    expect(await harness.fixture.fileStore.size(thumb?.id ?? '')).toBeNull();
    expect((await objectRows(mediaId)).find((row) => row.variant === 'thumbnail')?.localState).toBe('missing');
  });

  it('prefetchAll downloads only missing thumbnails of non-trashed entries', async () => {
    const kept = await harness.createEntry([fakeJpeg(2000, 1)]);
    const trashed = await harness.createEntry([fakeJpeg(2000, 2)]);
    const local = await harness.createEntry([fakeJpeg(2000, 3)]);
    await moveToServer(kept);
    await moveToServer(trashed);
    await harness.service.trash([trashed]);
    await thumbnails.prefetchAll();
    const thumbOf = async (entryId: string) => {
      const mediaId = (await harness.service.readMetadata(entryId)).media[0]?.mediaId ?? '';
      return (await objectRows(mediaId)).find((row) => row.variant === 'thumbnail')?.id ?? '';
    };
    expect(backend.urlRequests.get(await thumbOf(kept))).toBe(1);
    expect(backend.urlRequests.has(await thumbOf(trashed))).toBe(false);
    expect(backend.urlRequests.has(await thumbOf(local))).toBe(false);
  });

  it('switching modes in both directions leaves one copy of the right kind', async () => {
    const entryId = await harness.createEntry([fakeJpeg(2000)]);
    await moveToServer(entryId);
    const mediaId = (await harness.service.readMetadata(entryId)).media[0]?.mediaId ?? '';
    await thumbnails.get(entryId, mediaId);
    settings.encryptThumbnails = true;
    await thumbnails.switchMode(true);
    let thumb = (await objectRows(mediaId)).find((row) => row.variant === 'thumbnail');
    expect(harness.fixture.fileStore.plainThumbnailUri(mediaId)).toBeNull();
    expect(thumb?.localState).toBe('present');
    settings.encryptThumbnails = false;
    await thumbnails.switchMode(false);
    thumb = (await objectRows(mediaId)).find((row) => row.variant === 'thumbnail');
    expect(harness.fixture.fileStore.plainThumbnailUri(mediaId)).not.toBeNull();
    expect(await harness.fixture.fileStore.size(thumb?.id ?? '')).toBeNull();
  });
});

describe('originals', () => {
  it('downloads a missing original on open and decrypts it to the original bytes', async () => {
    const photo = fakeJpeg(5000, 4);
    const entryId = await harness.createEntry([photo]);
    await moveToServer(entryId);
    const mediaId = (await harness.service.readMetadata(entryId)).media[0]?.mediaId ?? '';
    const ref = await originals.open(entryId, mediaId);
    expect(ref).not.toBe('missing');
    if (ref === 'missing') return;
    expect(harness.sinkBytes(ref.uri)).toEqual(photo);
  });

  it('evicts the oldest unpinned uploaded originals over the cap; pinned and pending survive', async () => {
    const megabyte = 1_000_000;
    const ids: string[] = [];
    for (let fill = 1; fill <= 5; fill += 1) {
      ids.push(await harness.createEntry([fakeJpeg(3 * megabyte, fill)]));
      harness.clock.advance(1_000);
    }
    const [oldest, second, pinned, third, pending] = ids;
    for (const entryId of [oldest, second, pinned, third]) await harness.markUploaded(entryId ?? '');
    await originals.pinEntry(pinned ?? '');
    settings.originalCacheCapBytes = 10 * megabyte;
    await originals.enforceCap();
    const stateOf = async (entryId: string) => {
      const mediaId = (await harness.service.readMetadata(entryId)).media[0]?.mediaId ?? '';
      return (await objectRows(mediaId)).find((row) => row.variant === 'original')?.localState;
    };
    expect(await stateOf(oldest ?? '')).toBe('missing');
    expect(await stateOf(second ?? '')).toBe('missing');
    expect(await stateOf(pinned ?? '')).toBe('present');
    expect(await stateOf(third ?? '')).toBe('present');
    expect(await stateOf(pending ?? '')).toBe('present');
  });

  it('a cap of 0 means no limit', async () => {
    const entryId = await harness.createEntry([fakeJpeg(3_000_000)]);
    await harness.markUploaded(entryId);
    settings.originalCacheCapBytes = 0;
    await originals.enforceCap();
    const mediaId = (await harness.service.readMetadata(entryId)).media[0]?.mediaId ?? '';
    expect((await objectRows(mediaId)).find((row) => row.variant === 'original')?.localState).toBe('present');
  });

  it('local-only mode never evicts, neither by cap nor by clearing', async () => {
    const local = createDownloadingOriginalSource({
      local: harness.originals,
      downloader,
      rowStore: harness.fixture.rowStore,
      fileStore: harness.fixture.fileStore,
      settings: () => settings,
      isWifi: async () => true,
      localOnly: () => true,
    });
    const entryId = await harness.createEntry([fakeJpeg(3_000_000)]);
    await harness.markUploaded(entryId);
    settings.originalCacheCapBytes = 1;
    await local.enforceCap();
    await local.clearOriginals();
    const mediaId = (await harness.service.readMetadata(entryId)).media[0]?.mediaId ?? '';
    expect((await objectRows(mediaId)).find((row) => row.variant === 'original')?.localState).toBe('present');
  });
});

describe('upload policy', () => {
  it('holds large uploads off Wi-Fi when the setting is on and releases them on Wi-Fi', () => {
    let wifi = false;
    const listeners: (() => void)[] = [];
    const policy = createWifiUploadPolicy({
      network: {
        isWifi: async () => wifi,
        subscribe: (listener) => {
          listeners.push(listener);
          return () => undefined;
        },
      },
      settings: () => ({ largeUploadsOnWifiOnly: true, largeUploadThresholdBytes: 104_857_600 }),
      initialWifi: false,
    });
    const large = 120 * 1_048_576;
    expect(policy.allowed(large)).toBe(false);
    expect(policy.allowed(1_000)).toBe(true);
    wifi = true;
    policy.setWifi(true);
    expect(policy.allowed(large)).toBe(true);
  });
});

