import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { StorageUnavailableError } from '../../src/adapters/storage/errors.ts';
import { createS3ObjectStore } from '../../src/adapters/storage/s3-object-store.ts';
import type { ObjectStore } from '../../src/adapters/storage/object-store.ts';
import { objectKey } from '../../src/domain/objects.ts';
import { randomId } from '../../src/platform/ids.ts';
import { errorCodeOf } from './auth-helpers.ts';
import { createTestDatabase, garageStorageEnv, testSettings, type TestDatabase } from './helpers.ts';
import {
  MIB,
  PART_BYTES,
  bytesOfSize,
  confirm,
  createObjectsContext,
  newOwner,
  objectRow,
  parts,
  partsOf,
  presign,
  presignBody,
  presignOf,
  putBytes,
  usedBytesOf,
  type ObjectsContext,
} from './objects-helpers.ts';

const SINGLE_BYTES = 1000;
const MULTIPART_TAIL = 123;
const FORBIDDEN = 403;
const SECOND_MS = 1000;
const HOUR_MS = 3_600_000;
const CAPPED_SECONDS = 100;
const REPEATED_PRESIGNS = 8;
const MAX_PRESIGNS = 2;
const NONEXISTENT_BUCKET = 'kodakiko-bucket-that-does-not-exist';
const UPLOAD_ID_PARAM = 'uploadId';
const EXPIRES_PARAM = 'X-Amz-Expires';

let testDatabase: TestDatabase;
let context: ObjectsContext;

beforeAll(async () => {
  testDatabase = await createTestDatabase();
  context = await createObjectsContext(testDatabase);
});

afterAll(async () => {
  await context.close();
  await testDatabase.drop();
});

describe('review 1.1 signed sizes', () => {
  it('rejects a single PUT whose size differs from the declared size', async () => {
    const { user, medias } = await newOwner(context);
    const issued = presignOf(
      await presign(
        context,
        user.sessionToken,
        presignBody({ mediaId: medias[0]!.mediaId, sizeBytes: SINGLE_BYTES }),
      ),
    );
    if (issued.mode !== 'single') throw new Error('expected single');
    expect((await putBytes(issued.url, bytesOfSize(SINGLE_BYTES - 1))).status).toBe(FORBIDDEN);
    expect((await putBytes(issued.url, bytesOfSize(SINGLE_BYTES + 1))).status).toBe(FORBIDDEN);
    expect((await putBytes(issued.url, bytesOfSize(SINGLE_BYTES))).status).toBe(200);
  });

  it('rejects multipart parts whose size differs from the planned size', async () => {
    const { user, medias } = await newOwner(context);
    const id = randomId();
    const size = 2 * PART_BYTES + MULTIPART_TAIL;
    const issued = presignOf(
      await presign(
        context,
        user.sessionToken,
        presignBody({ id, mediaId: medias[0]!.mediaId, sizeBytes: size }),
      ),
    );
    if (issued.mode !== 'multipart') throw new Error('expected multipart');
    const [first, second, last] = issued.partUrls;
    expect((await putBytes(first!.url, bytesOfSize(PART_BYTES - 1))).status).toBe(FORBIDDEN);
    expect((await putBytes(last!.url, bytesOfSize(MULTIPART_TAIL + 1))).status).toBe(FORBIDDEN);
    expect((await putBytes(first!.url, bytesOfSize(PART_BYTES))).status).toBe(200);
    expect((await putBytes(second!.url, bytesOfSize(PART_BYTES))).status).toBe(200);
    expect((await putBytes(last!.url, bytesOfSize(MULTIPART_TAIL))).status).toBe(200);
    expect((await confirm(context, user.sessionToken, id)).status).toBe(200);
  });
});

describe('review 1.3 URL lifetime versus row lifetime', () => {
  it('sets delete_after at least one URL lifetime ahead on a size mismatch, even with zero grace', async () => {
    const zeroGrace = await createObjectsContext(testDatabase, { env: { OBJECT_GRACE_DAYS: '0' } });
    try {
      const { user, medias } = await newOwner(zeroGrace);
      const id = randomId();
      await presign(
        zeroGrace,
        user.sessionToken,
        presignBody({ id, mediaId: medias[0]!.mediaId, sizeBytes: SINGLE_BYTES }),
      );
      const row = await objectRow(zeroGrace.db, id);
      const mismatched = await zeroGrace.store.presignPut(row!.objectKey, SINGLE_BYTES - 1, 60);
      expect((await putBytes(mismatched, bytesOfSize(SINGLE_BYTES - 1))).status).toBe(200);
      const result = await confirm(zeroGrace, user.sessionToken, id);
      expect(errorCodeOf(result)).toBe('UPLOAD_SIZE_MISMATCH');
      const after = await objectRow(zeroGrace.db, id);
      const minimum =
        zeroGrace.clock.now().getTime() + zeroGrace.settings.storage.uploadUrlTtlSeconds * SECOND_MS;
      expect(after?.state).toBe('deleting');
      expect(after?.deleteAfter?.getTime()).toBeGreaterThanOrEqual(minimum);
    } finally {
      await zeroGrace.close();
    }
  });

  it('caps the URL lifetime of a re-issue at the time left before the row expires', async () => {
    const { user, medias } = await newOwner(context, 2);
    const hours = context.settings.storage.pendingUploadHours;
    const singleId = randomId();
    const multipartId = randomId();
    const singleBody = presignBody({ id: singleId, mediaId: medias[0]!.mediaId, sizeBytes: SINGLE_BYTES });
    const multipartBody = presignBody({
      id: multipartId,
      mediaId: medias[1]!.mediaId,
      sizeBytes: PART_BYTES + 1,
    });
    await presign(context, user.sessionToken, singleBody);
    await presign(context, user.sessionToken, multipartBody);
    const createdAt = new Date(context.clock.now().getTime() - hours * HOUR_MS + CAPPED_SECONDS * SECOND_MS);
    await context.db
      .updateTable('objects')
      .set({ createdAt })
      .where('id', 'in', [singleId, multipartId])
      .execute();

    const single = presignOf(await presign(context, user.sessionToken, singleBody));
    const multipart = presignOf(await presign(context, user.sessionToken, multipartBody));
    if (single.mode !== 'single' || multipart.mode !== 'multipart') throw new Error('modes');
    const maxExpiry = context.clock.now().getTime() + CAPPED_SECONDS * SECOND_MS;
    expect(Number(new URL(single.url).searchParams.get(EXPIRES_PARAM))).toBeLessThanOrEqual(CAPPED_SECONDS);
    expect(new Date(single.expiresAt).getTime()).toBeLessThanOrEqual(maxExpiry);
    for (const entry of multipart.partUrls) {
      expect(Number(new URL(entry.url).searchParams.get(EXPIRES_PARAM))).toBeLessThanOrEqual(CAPPED_SECONDS);
    }
    expect(new Date(multipart.expiresAt).getTime()).toBeLessThanOrEqual(maxExpiry);
  });

  it('refuses a re-issue with 409 ID_CONFLICT once the row is past its pending window', async () => {
    const { user, medias } = await newOwner(context);
    const id = randomId();
    const body = presignBody({ id, mediaId: medias[0]!.mediaId, sizeBytes: SINGLE_BYTES });
    await presign(context, user.sessionToken, body);
    const hours = context.settings.storage.pendingUploadHours;
    await context.db
      .updateTable('objects')
      .set({ createdAt: new Date(context.clock.now().getTime() - hours * HOUR_MS - SECOND_MS) })
      .where('id', '=', id)
      .execute();
    const result = await presign(context, user.sessionToken, body);
    expect(result.status).toBe(409);
    expect(errorCodeOf(result)).toBe('ID_CONFLICT');
  });
});

describe('review 1.4 missing bucket', () => {
  it('reports a missing bucket as storage unavailable, not as a missing object', async () => {
    const store = createS3ObjectStore(
      testSettings({ ...garageStorageEnv(), S3_BUCKET: NONEXISTENT_BUCKET }).storage,
    );
    await expect(store.getObjectStream(`v1/${randomId()}/${randomId()}`, null)).rejects.toBeInstanceOf(
      StorageUnavailableError,
    );
  });
});

describe('review 1.5 conditional multipart upload id', () => {
  it('aborts the loser of two concurrent upload creations and serves the stored id', async () => {
    const created: string[] = [];
    const aborted: string[] = [];
    let started = 0;
    let release!: () => void;
    const gate = new Promise<void>((resolve) => {
      release = resolve;
    });
    const racing = await createObjectsContext(testDatabase, {
      wrapStore: (store): ObjectStore => ({
        ...store,
        createMultipartUpload: async (key) => {
          const uploadId = await store.createMultipartUpload(key);
          created.push(uploadId);
          started += 1;
          if (started === 2) release();
          await gate;
          return uploadId;
        },
        abortMultipartUpload: async (key, uploadId) => {
          aborted.push(uploadId);
          await store.abortMultipartUpload(key, uploadId);
        },
      }),
    });
    try {
      const { user, medias } = await newOwner(racing);
      const id = randomId();
      const size = 2 * PART_BYTES + MULTIPART_TAIL;
      await racing.db
        .insertInto('objects')
        .values({
          id,
          ownerId: user.userId,
          mediaId: medias[0]!.mediaId,
          variant: 'original',
          objectKey: objectKey(user.userId, id),
          sizeBytes: size,
          state: 'pending',
          multipartUploadId: null,
          partSizeBytes: PART_BYTES,
          createdAt: racing.clock.now(),
          uploadedAt: null,
          deleteAfter: null,
        })
        .execute();
      await racing.db.updateTable('users').set({ usedBytes: size }).where('id', '=', user.userId).execute();
      const body = presignBody({ id, mediaId: medias[0]!.mediaId, sizeBytes: size });
      const results = await Promise.all([
        presign(racing, user.sessionToken, body),
        presign(racing, user.sessionToken, body),
      ]);
      expect(results.map((result) => result.status)).toEqual([200, 200]);
      const stored = (await objectRow(racing.db, id))?.multipartUploadId;
      expect(created).toHaveLength(2);
      expect(created).toContain(stored);
      expect(aborted).toHaveLength(1);
      expect(aborted[0]).not.toBe(stored);
      for (const result of results) {
        const issued = presignOf(result);
        if (issued.mode !== 'multipart') throw new Error('expected multipart');
        for (const entry of issued.partUrls) {
          expect(new URL(entry.url).searchParams.get(UPLOAD_ID_PARAM)).toBe(stored);
        }
      }
    } finally {
      await racing.close();
    }
  });
});

describe('review 1.9 mode and part size come from the row', () => {
  it('keeps serving a multipart row after the sizing settings changed', async () => {
    const { user, medias } = await newOwner(context);
    const id = randomId();
    const size = 2 * PART_BYTES + MULTIPART_TAIL;
    const bytes = bytesOfSize(size);
    const body = presignBody({ id, mediaId: medias[0]!.mediaId, sizeBytes: size });
    const issued = presignOf(await presign(context, user.sessionToken, body));
    if (issued.mode !== 'multipart') throw new Error('expected multipart');
    expect((await putBytes(issued.partUrls[0]!.url, bytes.subarray(0, PART_BYTES))).status).toBe(200);
    expect((await objectRow(context.db, id))?.partSizeBytes).toBe(PART_BYTES);

    const changed = await createObjectsContext(testDatabase, {
      env: {
        SINGLE_PART_MAX_BYTES: String(20 * MIB),
        MULTIPART_PART_BYTES: String(20 * MIB),
      },
    });
    try {
      const listing = partsOf(await parts(changed, user.sessionToken, id));
      expect(listing.partSizeBytes).toBe(PART_BYTES);
      expect(listing.uploadedParts).toEqual([{ partNumber: 1, sizeBytes: PART_BYTES }]);
      expect(listing.partUrls.map((entry) => entry.partNumber)).toEqual([2, 3]);
      const reissued = presignOf(await presign(changed, user.sessionToken, body));
      expect(reissued.mode).toBe('multipart');
      expect(
        (await putBytes(listing.partUrls[0]!.url, bytes.subarray(PART_BYTES, 2 * PART_BYTES))).status,
      ).toBe(200);
      expect((await putBytes(listing.partUrls[1]!.url, bytes.subarray(2 * PART_BYTES))).status).toBe(200);
      const confirmed = await confirm(changed, user.sessionToken, id);
      expect(confirmed.status).toBe(200);
      expect((await objectRow(changed.db, id))?.state).toBe('uploaded');
    } finally {
      await changed.close();
    }
  });

  it('stores the part size at presign for multipart and nothing for single', async () => {
    const { user, medias } = await newOwner(context, 2);
    const singleId = randomId();
    const multipartId = randomId();
    await presign(
      context,
      user.sessionToken,
      presignBody({ id: singleId, mediaId: medias[0]!.mediaId, sizeBytes: SINGLE_BYTES }),
    );
    await presign(
      context,
      user.sessionToken,
      presignBody({ id: multipartId, mediaId: medias[1]!.mediaId, sizeBytes: PART_BYTES + 1 }),
    );
    expect((await objectRow(context.db, singleId))?.partSizeBytes).toBeNull();
    expect((await objectRow(context.db, multipartId))?.partSizeBytes).toBe(PART_BYTES);
  });
});

describe('review 1.10 presign rate inside the reservation', () => {
  it('lets exactly MAX_PRESIGNS_PER_HOUR of many concurrent presigns through', async () => {
    const limited = await createObjectsContext(testDatabase, {
      env: { MAX_PRESIGNS_PER_HOUR: String(MAX_PRESIGNS) },
    });
    try {
      const owner = await newOwner(limited, REPEATED_PRESIGNS);
      const results = await Promise.all(
        owner.medias.map((media) =>
          presign(
            limited,
            owner.user.sessionToken,
            presignBody({ mediaId: media.mediaId, sizeBytes: SINGLE_BYTES }),
          ),
        ),
      );
      const statuses = results.map((result) => result.status);
      expect(statuses.filter((status) => status === 200)).toHaveLength(MAX_PRESIGNS);
      expect(statuses.filter((status) => status === 429)).toHaveLength(REPEATED_PRESIGNS - MAX_PRESIGNS);
      expect(await usedBytesOf(limited.db, owner.user.userId)).toBe(MAX_PRESIGNS * SINGLE_BYTES);
    } finally {
      await limited.close();
    }
  });
});
