import { beforeEach, describe, expect, it } from 'vitest';
import { ROUTES } from '@kodakiko/contract';
import type { Sodium } from '@kodakiko/core';
import { createEventBus, type AppEvents } from '../src/composition/events';
import { createUploadCoordinator } from '../src/services/sync/uploads';
import {
  createAccountFixture,
  createLocalEntry,
  createMemoryFileStore,
  createMemoryRowStore,
  createNodeSodium,
  type AccountFixture,
  type LocalEntry,
} from './support';
import { apiFailure, createFakeHttp, type FakeHttp } from './support/fake-http';
import { createFakeUploader, type FakeUploader } from './support/fake-uploader';

let sodium: Sodium;
let fixture: AccountFixture;
let local: LocalEntry;
let fakeHttp: FakeHttp;
let uploader: FakeUploader;
let allowed: boolean;
let changed: Array<readonly string[]>;
let uploadedEntries: string[];
let afterUpload: string[];
let asked: number;

const SINGLE_URL = 'https://bucket.test/single';

function presignSingle(): unknown {
  return { mode: 'single', url: SINGLE_URL, expiresAt: '2026-11-13T10:00:00.000Z' };
}

function objectIdOf(variant: 'original' | 'thumbnail'): string {
  return (variant === 'original' ? local.originalObjectIds : local.thumbnailObjectIds)[0] ?? '';
}

async function objectState(objectId: string) {
  return fixture.rowStore.transaction((tx) => tx.objects.get(objectId));
}

function coordinator() {
  const events = createEventBus<AppEvents>();
  events.on('entriesChanged', (ids) => changed.push(ids));
  const result = createUploadCoordinator({
    http: fakeHttp.http,
    rowStore: fixture.rowStore,
    uploader,
    events,
    uploadPolicy: { allowed: () => allowed },
    thumbnailCache: {
      afterUpload: (objectId) => {
        afterUpload.push(objectId);
      },
    },
    notificationPrompt: {
      ensureAsked: async () => {
        asked += 1;
      },
    },
    log: { warn: () => undefined },
  });
  result.onObjectUploaded((entryId) => uploadedEntries.push(entryId));
  return result;
}

beforeEach(async () => {
  sodium = await createNodeSodium();
  fixture = await createAccountFixture(sodium, createMemoryRowStore(), createMemoryFileStore());
  local = await createLocalEntry(fixture, { mediaBytes: [new Uint8Array(100)], note: 'n', tags: [] });
  fakeHttp = createFakeHttp();
  uploader = createFakeUploader();
  allowed = true;
  changed = [];
  uploadedEntries = [];
  afterUpload = [];
  asked = 0;
  fakeHttp.on('POST', ROUTES.objectsPresign(), () => presignSingle());
  fakeHttp.on('POST', /\/confirm$/, (request) => ({
    id: request.path.split('/').at(-2),
    mediaId: local.mediaIds[0],
    variant: 'original',
    sizeBytes: 200,
    state: 'uploaded',
  }));
});

describe('enqueueObjectUploads', () => {
  it('presigns, hands a single-part job to the uploader and marks the object uploading', async () => {
    const objectId = objectIdOf('original');
    const uploads = coordinator();
    await uploads.enqueueObjectUploads([objectId]);
    const row = await objectState(objectId);
    expect(fakeHttp.requests[0]?.body).toEqual({
      id: objectId,
      mediaId: local.mediaIds[0],
      variant: 'original',
      sizeBytes: row?.sizeBytes,
    });
    expect(uploader.jobs).toEqual([
      { objectId, parts: [{ partNumber: 0, url: SINGLE_URL, offset: 0, length: row?.sizeBytes }] },
    ]);
    expect(row?.uploadState).toBe('uploading');
    expect(changed.flat()).toContain(local.entryId);
  });

  it('builds multipart parts from partSizeBytes with a shorter last part', async () => {
    const objectId = objectIdOf('original');
    const size = (await objectState(objectId))?.sizeBytes ?? 0;
    const partSize = Math.ceil(size / 3);
    fakeHttp.on('POST', ROUTES.objectsPresign(), () => ({
      mode: 'multipart',
      partSizeBytes: partSize,
      partUrls: [1, 2, 3].map((partNumber) => ({ partNumber, url: `https://bucket.test/p${partNumber}` })),
      expiresAt: '2026-11-13T10:00:00.000Z',
    }));
    await coordinator().enqueueObjectUploads([objectId]);
    expect(uploader.jobs[0]?.parts).toEqual([
      { partNumber: 1, url: 'https://bucket.test/p1', offset: 0, length: partSize },
      { partNumber: 2, url: 'https://bucket.test/p2', offset: partSize, length: partSize },
      { partNumber: 3, url: 'https://bucket.test/p3', offset: partSize * 2, length: size - partSize * 2 },
    ]);
  });

  it('keeps an object queued and counts it as held back while the policy denies it', async () => {
    allowed = false;
    const objectId = objectIdOf('original');
    const uploads = coordinator();
    await uploads.enqueueObjectUploads([objectId]);
    expect((await objectState(objectId))?.uploadState).toBe('queued');
    expect(fakeHttp.requests).toHaveLength(0);
    expect(uploader.jobs).toHaveLength(0);
    expect(uploads.heldBackCount()).toBe(1);
    allowed = true;
    await uploads.reconcileUploads();
    expect((await objectState(objectId))?.uploadState).toBe('uploading');
    expect(uploads.heldBackCount()).toBe(0);
  });

  it('calls the notification prompt whenever it enqueues jobs, the prompt asks only once', async () => {
    const uploads = coordinator();
    await uploads.enqueueObjectUploads([objectIdOf('original')]);
    await uploads.enqueueObjectUploads([objectIdOf('thumbnail')]);
    expect(asked).toBe(2);
  });

  it('propagates presign errors so the executor can classify them', async () => {
    fakeHttp.on('POST', ROUTES.objectsPresign(), () => {
      throw apiFailure(403, 'QUOTA_EXCEEDED');
    });
    await expect(coordinator().enqueueObjectUploads([objectIdOf('original')])).rejects.toMatchObject({
      code: 'QUOTA_EXCEEDED',
    });
    expect((await objectState(objectIdOf('original')))?.uploadState).toBe('queued');
  });
});

describe('uploader events', () => {
  async function uploading(variant: 'original' | 'thumbnail' = 'original') {
    const uploads = coordinator();
    const objectId = objectIdOf(variant);
    await uploads.enqueueObjectUploads([objectId]);
    return { uploads, objectId };
  }

  it('confirms a completed upload and marks the object uploaded', async () => {
    const { uploads, objectId } = await uploading();
    await uploads.handleEvent({ type: 'completed', objectId });
    expect(fakeHttp.count('POST', ROUTES.objectConfirm(objectId))).toBe(1);
    expect((await objectState(objectId))?.uploadState).toBe('uploaded');
    expect(uploadedEntries).toEqual([local.entryId]);
    expect(afterUpload).toEqual([]);
  });

  it('calls thumbnailCache.afterUpload for thumbnail objects', async () => {
    const { uploads, objectId } = await uploading('thumbnail');
    await uploads.handleEvent({ type: 'completed', objectId });
    expect(afterUpload).toEqual([objectId]);
  });

  it('ignores events for objects without a RowStore row', async () => {
    const uploads = coordinator();
    await uploads.handleEvent({ type: 'completed', objectId: fixture.randomId() });
    await uploads.handleEvent({ type: 'failed', objectId: fixture.randomId(), reason: 'expired' });
    expect(fakeHttp.requests).toHaveLength(0);
  });

  it('resumes a multipart upload from the missing parts on UPLOAD_INCOMPLETE', async () => {
    const { uploads, objectId } = await uploading();
    const size = (await objectState(objectId))?.sizeBytes ?? 0;
    const partSize = Math.ceil(size / 3);
    fakeHttp.on('POST', ROUTES.objectConfirm(objectId), () => {
      throw apiFailure(422, 'UPLOAD_INCOMPLETE');
    });
    fakeHttp.on('GET', ROUTES.objectParts(objectId), () => ({
      partSizeBytes: partSize,
      uploadedParts: [{ partNumber: 1, sizeBytes: partSize }],
      partUrls: [
        { partNumber: 2, url: 'https://bucket.test/p2' },
        { partNumber: 3, url: 'https://bucket.test/p3' },
      ],
      expiresAt: '2026-11-13T10:00:00.000Z',
    }));
    uploader.jobs.length = 0;
    await uploads.handleEvent({ type: 'completed', objectId });
    expect(uploader.jobs).toEqual([
      {
        objectId,
        parts: [
          { partNumber: 2, url: 'https://bucket.test/p2', offset: partSize, length: partSize },
          { partNumber: 3, url: 'https://bucket.test/p3', offset: partSize * 2, length: size - partSize * 2 },
        ],
      },
    ]);
    expect((await objectState(objectId))?.uploadState).toBe('uploading');
  });

  it('presigns again on UPLOAD_INCOMPLETE for a single-PUT object', async () => {
    const { uploads, objectId } = await uploading();
    fakeHttp.on('POST', ROUTES.objectConfirm(objectId), () => {
      throw apiFailure(422, 'UPLOAD_INCOMPLETE');
    });
    uploader.jobs.length = 0;
    const before = fakeHttp.count('POST', ROUTES.objectsPresign());
    await uploads.handleEvent({ type: 'completed', objectId });
    expect(fakeHttp.count('GET', ROUTES.objectParts(objectId))).toBe(1);
    expect(fakeHttp.count('POST', ROUTES.objectsPresign())).toBe(before + 1);
    expect(uploader.jobs).toHaveLength(1);
  });

  it('presigns again after failed expired', async () => {
    const { uploads, objectId } = await uploading();
    uploader.jobs.length = 0;
    await uploads.handleEvent({ type: 'failed', objectId, reason: 'expired' });
    expect(uploader.jobs).toHaveLength(1);
    expect((await objectState(objectId))?.uploadState).toBe('uploading');
  });

  it('marks the object and its outbox item dead after failed missingFile', async () => {
    const { uploads, objectId } = await uploading();
    await uploads.handleEvent({ type: 'failed', objectId, reason: 'missingFile' });
    expect((await objectState(objectId))?.uploadState).toBe('dead');
    const outbox = await fixture.rowStore.transaction((tx) => tx.outbox.all());
    expect(outbox[0]?.state).toBe('dead');
  });

  it('leaves the state alone after failed network or http and after a stopped timeout', async () => {
    const { uploads, objectId } = await uploading();
    await uploads.handleEvent({ type: 'failed', objectId, reason: 'network' });
    await uploads.handleEvent({ type: 'failed', objectId, reason: 'http' });
    await uploads.handleEvent({ type: 'stopped', reason: 'timeout' });
    expect((await objectState(objectId))?.uploadState).toBe('uploading');
  });

  it('marks the object dead when confirm fails with a client error other than UPLOAD_INCOMPLETE', async () => {
    const { uploads, objectId } = await uploading();
    fakeHttp.on('POST', ROUTES.objectConfirm(objectId), () => {
      throw apiFailure(422, 'UPLOAD_SIZE_MISMATCH');
    });
    await uploads.handleEvent({ type: 'completed', objectId });
    expect((await objectState(objectId))?.uploadState).toBe('dead');
  });

  it('presigns again and resubmits when confirm answers 404 NOT_FOUND', async () => {
    const { uploads, objectId } = await uploading();
    fakeHttp.on('POST', ROUTES.objectConfirm(objectId), () => {
      throw apiFailure(404, 'NOT_FOUND');
    });
    uploader.jobs.length = 0;
    const before = fakeHttp.count('POST', ROUTES.objectsPresign());
    await uploads.handleEvent({ type: 'completed', objectId });
    expect(fakeHttp.count('POST', ROUTES.objectsPresign())).toBe(before + 1);
    expect(uploader.jobs).toHaveLength(1);
    expect((await objectState(objectId))?.uploadState).toBe('uploading');
  });

  it('marks nothing and does not retry when confirm answers 401', async () => {
    const { uploads, objectId } = await uploading();
    fakeHttp.on('POST', ROUTES.objectConfirm(objectId), () => {
      throw apiFailure(401, 'UNAUTHENTICATED');
    });
    uploader.jobs.length = 0;
    const presigns = fakeHttp.count('POST', ROUTES.objectsPresign());
    await uploads.handleEvent({ type: 'completed', objectId });
    expect((await objectState(objectId))?.uploadState).toBe('uploading');
    expect((await fixture.rowStore.transaction((tx) => tx.outbox.all()))[0]?.state).toBe('queued');
    expect(uploader.jobs).toHaveLength(0);
    expect(fakeHttp.count('POST', ROUTES.objectsPresign())).toBe(presigns);
  });

  it('keeps the object uploading when confirm fails with a network error', async () => {
    const { uploads, objectId } = await uploading();
    fakeHttp.on('POST', ROUTES.objectConfirm(objectId), () => {
      throw new (class extends Error {})();
    });
    await expect(uploads.handleEvent({ type: 'completed', objectId })).rejects.toBeInstanceOf(Error);
    expect((await objectState(objectId))?.uploadState).toBe('uploading');
  });
});

describe('reconcileUploads', () => {
  it('confirms uploading objects whose uploader status is completed or unknown', async () => {
    const uploads = coordinator();
    const objectId = objectIdOf('original');
    await uploads.enqueueObjectUploads([objectId]);
    uploader.setStatus(objectId, 'completed');
    await uploads.reconcileUploads();
    expect((await objectState(objectId))?.uploadState).toBe('uploaded');

    const thumbnailId = objectIdOf('thumbnail');
    await uploads.enqueueObjectUploads([thumbnailId]);
    uploader.setStatus(thumbnailId, null);
    await uploads.reconcileUploads();
    expect((await objectState(thumbnailId))?.uploadState).toBe('uploaded');
  });

  it('leaves objects the uploader is still working on', async () => {
    const uploads = coordinator();
    const objectId = objectIdOf('original');
    await uploads.enqueueObjectUploads([objectId]);
    uploader.setStatus(objectId, 'uploading');
    await uploads.reconcileUploads();
    expect(fakeHttp.count('POST', ROUTES.objectConfirm(objectId))).toBe(0);
  });

  it('tries to confirm failed uploads and resumes them on UPLOAD_INCOMPLETE', async () => {
    const uploads = coordinator();
    const objectId = objectIdOf('original');
    await uploads.enqueueObjectUploads([objectId]);
    uploader.setStatus(objectId, 'failed');
    fakeHttp.on('POST', ROUTES.objectConfirm(objectId), () => {
      throw apiFailure(422, 'UPLOAD_INCOMPLETE');
    });
    uploader.jobs.length = 0;
    await uploads.reconcileUploads();
    expect(uploader.jobs).toHaveLength(1);
  });

  it('keeps going when one object fails to reconcile', async () => {
    const uploads = coordinator();
    await uploads.enqueueObjectUploads([objectIdOf('original'), objectIdOf('thumbnail')]);
    fakeHttp.on('POST', ROUTES.objectConfirm(objectIdOf('original')), () => {
      throw new Error('boom');
    });
    uploader.setStatus(objectIdOf('original'), 'completed');
    uploader.setStatus(objectIdOf('thumbnail'), 'completed');
    await uploads.reconcileUploads();
    expect((await objectState(objectIdOf('thumbnail')))?.uploadState).toBe('uploaded');
  });

  it('retries enqueueing for queued objects and swallows presign errors', async () => {
    const objectId = objectIdOf('original');
    await fixture.rowStore.transaction(async (tx) => {
      const row = await tx.objects.get(objectId);
      if (row !== null) await tx.objects.put({ ...row, uploadState: 'queued' });
    });
    fakeHttp.on('POST', ROUTES.objectsPresign(), () => {
      throw apiFailure(403, 'QUOTA_EXCEEDED');
    });
    const uploads = coordinator();
    await expect(uploads.reconcileUploads()).resolves.toBeUndefined();
    expect((await objectState(objectId))?.uploadState).toBe('queued');
  });
});
