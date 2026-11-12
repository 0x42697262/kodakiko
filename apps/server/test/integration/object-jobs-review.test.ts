import { afterAll, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { abortStaleUploadsJob } from '../../src/jobs/abort-stale-uploads.ts';
import { collectObjectsJob } from '../../src/jobs/collect-objects.ts';
import { reconcileUsageJob } from '../../src/jobs/reconcile-usage.ts';
import type { ObjectStore } from '../../src/adapters/storage/object-store.ts';
import { objectKey } from '../../src/domain/objects.ts';
import { randomId } from '../../src/platform/ids.ts';
import { createInterceptedDatabase } from './intercepting-database.ts';
import { createTestDatabase, type TestDatabase } from './helpers.ts';
import {
  PART_BYTES,
  bytesOfSize,
  createObjectsContext,
  newOwner,
  objectRow,
  presign,
  presignBody,
  uploadSingle,
  usedBytesOf,
  type ObjectsContext,
} from './objects-helpers.ts';

const HOUR_MS = 3_600_000;
const SECOND_MS = 1000;
const YEAR_MS = 365 * 86_400_000;
const SIZE_BYTES = 1000;
const OTHER_BYTES = 500;
const CORRUPT_USED_BYTES = 999_999;
const LOCK_WAIT_MS = 300;
const COMPLETED_UPLOAD_ID = 'completed-upload-id';
const quiet = { info: () => undefined, warn: () => undefined };

let testDatabase: TestDatabase;
let context: ObjectsContext;

beforeAll(async () => {
  testDatabase = await createTestDatabase();
  context = await createObjectsContext(testDatabase, { env: { OBJECT_GRACE_DAYS: '0' } });
});

beforeEach(async () => {
  await context.db.deleteFrom('objects').execute();
});

afterAll(async () => {
  await context.close();
  await testDatabase.drop();
});

function run(
  job: { run: (input: { db: ObjectsContext['db']; now: Date; signal: AbortSignal }) => Promise<unknown> },
  now: Date = new Date(),
  db: ObjectsContext['db'] = context.db,
) {
  return job.run({ db, now, signal: new AbortController().signal });
}

describe('review 1.2 abort-stale claims the row before aborting', () => {
  it('does not abort the upload of a row that was confirmed after it was selected', async () => {
    const { user, medias } = await newOwner(context);
    const id = randomId();
    await presign(
      context,
      user.sessionToken,
      presignBody({ id, mediaId: medias[0]!.mediaId, sizeBytes: PART_BYTES + 1 }),
    );
    const now = new Date();
    await context.db
      .updateTable('objects')
      .set({ createdAt: new Date(now.getTime() - YEAR_MS) })
      .where('id', '=', id)
      .execute();
    const aborted: string[] = [];
    const spyStore: ObjectStore = {
      ...context.store,
      abortMultipartUpload: async (key, uploadId) => {
        aborted.push(uploadId);
        await context.store.abortMultipartUpload(key, uploadId);
      },
    };
    const intercepted = createInterceptedDatabase(testDatabase.url, async () => {
      await context.db
        .updateTable('objects')
        .set({ state: 'uploaded', uploadedAt: now })
        .where('id', '=', id)
        .execute();
    });
    try {
      const result = await run(abortStaleUploadsJob(context.settings, spyStore, quiet), now, intercepted);
      expect(result).toEqual({ processed: 0, remaining: false });
    } finally {
      await intercepted.destroy();
    }
    expect(aborted).toEqual([]);
    expect((await objectRow(context.db, id))?.state).toBe('uploaded');
  });
});

describe('review 1.3 abort-stale delete_after', () => {
  it('sets delete_after at least one URL lifetime ahead, even with zero grace', async () => {
    const { user, medias } = await newOwner(context);
    const id = randomId();
    await presign(
      context,
      user.sessionToken,
      presignBody({ id, mediaId: medias[0]!.mediaId, sizeBytes: SIZE_BYTES }),
    );
    const now = new Date();
    await context.db
      .updateTable('objects')
      .set({ createdAt: new Date(now.getTime() - YEAR_MS) })
      .where('id', '=', id)
      .execute();
    await run(abortStaleUploadsJob(context.settings, context.store, quiet), now);
    const row = await objectRow(context.db, id);
    const minimum = now.getTime() + context.settings.storage.uploadUrlTtlSeconds * SECOND_MS;
    expect(row?.state).toBe('deleting');
    expect(row?.deleteAfter?.getTime()).toBeGreaterThanOrEqual(minimum);
  });
});

describe('review 1.6 reconcile-usage locks the user row', () => {
  it('waits for a concurrent reservation and then records the committed sum', async () => {
    const { user, medias } = await newOwner(context);
    await context.db
      .updateTable('users')
      .set({ usedBytes: CORRUPT_USED_BYTES })
      .where('id', '=', user.userId)
      .execute();
    let locked!: () => void;
    const lockTaken = new Promise<void>((resolve) => {
      locked = resolve;
    });
    let commit!: () => void;
    const commitSignal = new Promise<void>((resolve) => {
      commit = resolve;
    });
    const reservation = context.db.transaction().execute(async (trx) => {
      await trx.selectFrom('users').select('id').where('id', '=', user.userId).forUpdate().execute();
      const id = randomId();
      await trx
        .insertInto('objects')
        .values({
          id,
          ownerId: user.userId,
          mediaId: medias[0]!.mediaId,
          variant: 'original',
          objectKey: objectKey(user.userId, id),
          sizeBytes: SIZE_BYTES,
          state: 'pending',
          multipartUploadId: null,
          createdAt: new Date(),
          uploadedAt: null,
          deleteAfter: null,
        })
        .execute();
      locked();
      await commitSignal;
    });
    await lockTaken;
    let finished = false;
    const reconcile = run(reconcileUsageJob(context.settings, quiet)).then(() => {
      finished = true;
    });
    await new Promise((resolve) => setTimeout(resolve, LOCK_WAIT_MS));
    expect(finished).toBe(false);
    commit();
    await reservation;
    await reconcile;
    expect(await usedBytesOf(context.db, user.userId)).toBe(SIZE_BYTES);
  });
});

async function insertDeleting(
  userId: string,
  mediaId: string | null,
  overrides: { multipartUploadId?: string; uploadedAt?: Date | null; sizeBytes?: number } = {},
): Promise<{ id: string; key: string }> {
  const id = randomId();
  const key = objectKey(userId, id);
  const now = new Date();
  await context.db
    .insertInto('objects')
    .values({
      id,
      ownerId: userId,
      mediaId,
      variant: 'original',
      objectKey: key,
      sizeBytes: overrides.sizeBytes ?? SIZE_BYTES,
      state: 'deleting',
      multipartUploadId: overrides.multipartUploadId ?? null,
      createdAt: now,
      uploadedAt: overrides.uploadedAt ?? null,
      deleteAfter: new Date(now.getTime() - HOUR_MS),
    })
    .execute();
  return { id, key };
}

describe('review 1.7 collect-objects', () => {
  it('aborts the multipart upload of a never-completed deleting row before deleting the key', async () => {
    const { user, medias } = await newOwner(context);
    const id = randomId();
    await presign(
      context,
      user.sessionToken,
      presignBody({ id, mediaId: medias[0]!.mediaId, sizeBytes: PART_BYTES + 1 }),
    );
    const row = await objectRow(context.db, id);
    const now = new Date();
    await context.db
      .updateTable('objects')
      .set({ state: 'deleting', deleteAfter: new Date(now.getTime() - HOUR_MS) })
      .where('id', '=', id)
      .execute();
    const calls: string[] = [];
    const spyStore: ObjectStore = {
      ...context.store,
      abortMultipartUpload: async (key, uploadId) => {
        calls.push('abort');
        await context.store.abortMultipartUpload(key, uploadId);
      },
      deleteObject: async (key) => {
        calls.push('delete');
        await context.store.deleteObject(key);
      },
    };
    await run(collectObjectsJob(context.settings, spyStore, quiet), now);
    expect(calls).toEqual(['abort', 'delete']);
    await expect(
      context.store.listParts(row!.objectKey, row!.multipartUploadId!),
    ).rejects.toBeDefined();
    expect(await objectRow(context.db, id)).toBeUndefined();
    expect(await usedBytesOf(context.db, user.userId)).toBe(0);
  });

  it('does not abort a multipart upload of a row that was uploaded', async () => {
    const { user } = await newOwner(context);
    const { id } = await insertDeleting(user.userId, null, {
      multipartUploadId: COMPLETED_UPLOAD_ID,
      uploadedAt: new Date(),
    });
    await context.db.updateTable('users').set({ usedBytes: SIZE_BYTES }).where('id', '=', user.userId).execute();
    const aborted: string[] = [];
    const spyStore: ObjectStore = {
      ...context.store,
      abortMultipartUpload: async (_key, uploadId) => {
        aborted.push(uploadId);
      },
    };
    await run(collectObjectsJob(context.settings, spyStore, quiet));
    expect(aborted).toEqual([]);
    expect(await objectRow(context.db, id)).toBeUndefined();
  });

  it('subtracts the size once when two runs overlap', async () => {
    const { user, medias } = await newOwner(context);
    const { id } = await insertDeleting(user.userId, medias[0]!.mediaId);
    await uploadSingle(context, user.sessionToken, medias[0]!.mediaId, bytesOfSize(OTHER_BYTES), 'thumbnail');
    await context.db
      .updateTable('users')
      .set({ usedBytes: SIZE_BYTES + OTHER_BYTES })
      .where('id', '=', user.userId)
      .execute();
    let started = 0;
    let release!: () => void;
    const gate = new Promise<void>((resolve) => {
      release = resolve;
    });
    const barrierStore: ObjectStore = {
      ...context.store,
      deleteObject: async (key) => {
        started += 1;
        if (started >= 2) release();
        await gate;
        await context.store.deleteObject(key);
      },
    };
    const job = collectObjectsJob(context.settings, barrierStore, quiet);
    const results = await Promise.all([run(job), run(job)]);
    expect(results).toHaveLength(2);
    expect(await objectRow(context.db, id)).toBeUndefined();
    expect(await usedBytesOf(context.db, user.userId)).toBe(OTHER_BYTES);
  });
});
