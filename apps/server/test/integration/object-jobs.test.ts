import { afterAll, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { JOB_BATCH_SIZE, JOB_TIMEOUT_MS } from '../../src/config/constants.ts';
import { abortStaleUploadsJob } from '../../src/jobs/abort-stale-uploads.ts';
import { collectObjectsJob } from '../../src/jobs/collect-objects.ts';
import { JOB_LOCK_IDS } from '../../src/jobs/keys.ts';
import { reconcileUsageJob } from '../../src/jobs/reconcile-usage.ts';
import type { ObjectStore } from '../../src/adapters/storage/object-store.ts';
import { StorageUnavailableError } from '../../src/adapters/storage/errors.ts';
import { objectKey } from '../../src/domain/objects.ts';
import { randomId } from '../../src/platform/ids.ts';
import { createTestDatabase, type TestDatabase } from './helpers.ts';
import {
  PART_BYTES,
  bytesOfSize,
  createObjectsContext,
  newOwner,
  objectRow,
  presign,
  presignBody,
  presignOf,
  uploadSingle,
  usedBytesOf,
  type ObjectsContext,
} from './objects-helpers.ts';

const HOUR_MS = 3_600_000;
const DAY_MS = 86_400_000;
const STALE_HOURS_PAST_CUTOFF = 2;
const CORRUPT_USED_BYTES = 999_999;
const SMALL_BYTES = 100;
const PENDING_BYTES = 1000;

let testDatabase: TestDatabase;
let context: ObjectsContext;

beforeAll(async () => {
  testDatabase = await createTestDatabase();
  context = await createObjectsContext(testDatabase);
});

beforeEach(async () => {
  await context.db.deleteFrom('objects').execute();
});

afterAll(async () => {
  await context.close();
  await testDatabase.drop();
});

function runJob(
  job: { run: (input: { db: ObjectsContext['db']; now: Date; signal: AbortSignal }) => Promise<unknown> },
  now: Date = new Date(),
) {
  return job.run({ db: context.db, now, signal: new AbortController().signal });
}

describe('job definitions', () => {
  it('uses the planned lock ids, intervals and timeout', () => {
    const logging = { info: () => undefined, warn: () => undefined };
    const abort = abortStaleUploadsJob(context.settings, context.store, logging);
    const collect = collectObjectsJob(context.settings, context.store, logging);
    const reconcile = reconcileUsageJob(context.settings, logging);
    expect([abort.name, collect.name, reconcile.name]).toEqual([
      'abort-stale-uploads',
      'collect-objects',
      'reconcile-usage',
    ]);
    expect([abort.lockId, collect.lockId, reconcile.lockId]).toEqual([1101, 1102, 1103]);
    expect(JOB_LOCK_IDS['abortStaleUploads']).toBe(1101);
    expect(JOB_LOCK_IDS['collectObjects']).toBe(1102);
    expect(JOB_LOCK_IDS['reconcileUsage']).toBe(1103);
    expect(abort.intervalMs).toBe(context.settings.jobs.abortStaleUploadsIntervalMs);
    expect(collect.intervalMs).toBe(context.settings.jobs.collectObjectsIntervalMs);
    expect(reconcile.intervalMs).toBe(context.settings.jobs.reconcileUsageIntervalMs);
    for (const job of [abort, collect, reconcile]) expect(job.timeoutMs).toBe(JOB_TIMEOUT_MS);
  });
});

describe('9. abort-stale-uploads', () => {
  it('aborts an old multipart upload and leaves a younger one alone', async () => {
    const { user, medias } = await newOwner(context, 2);
    const size = PART_BYTES + 1;
    const oldId = randomId();
    const youngId = randomId();
    await presign(
      context,
      user.sessionToken,
      presignBody({ id: oldId, mediaId: medias[0]!.mediaId, sizeBytes: size }),
    );
    await presign(
      context,
      user.sessionToken,
      presignBody({ id: youngId, mediaId: medias[1]!.mediaId, sizeBytes: size }),
    );
    const oldRow = await objectRow(context.db, oldId);
    const youngRow = await objectRow(context.db, youngId);
    const now = new Date();
    const cutoffAge = (context.settings.storage.pendingUploadHours + STALE_HOURS_PAST_CUTOFF) * HOUR_MS;
    await context.db
      .updateTable('objects')
      .set({ createdAt: new Date(now.getTime() - cutoffAge) })
      .where('id', '=', oldId)
      .execute();

    const job = abortStaleUploadsJob(context.settings, context.store, {
      info: () => undefined,
      warn: () => undefined,
    });
    const result = await runJob(job, now);
    expect(result).toEqual({ processed: 1, remaining: false });

    const aborted = await objectRow(context.db, oldId);
    expect(aborted?.state).toBe('deleting');
    expect(aborted?.deleteAfter?.getTime()).toBeGreaterThanOrEqual(
      now.getTime() + context.settings.storage.uploadUrlTtlSeconds * 1000,
    );
    await expect(
      context.store.listParts(oldRow!.objectKey, oldRow!.multipartUploadId!),
    ).rejects.toBeDefined();
    const untouched = await objectRow(context.db, youngId);
    expect(untouched?.state).toBe('pending');
    expect(
      await context.store.listParts(youngRow!.objectKey, youngRow!.multipartUploadId!),
    ).toEqual([]);
  });

  it('moves an old single-PUT row to deleting and ignores uploaded rows', async () => {
    const { user, medias } = await newOwner(context, 2);
    const pendingId = randomId();
    await presign(
      context,
      user.sessionToken,
      presignBody({ id: pendingId, mediaId: medias[0]!.mediaId, sizeBytes: PENDING_BYTES }),
    );
    const uploadedId = await uploadSingle(
      context,
      user.sessionToken,
      medias[1]!.mediaId,
      bytesOfSize(SMALL_BYTES),
    );
    const now = new Date();
    await context.db
      .updateTable('objects')
      .set({ createdAt: new Date(now.getTime() - 365 * DAY_MS) })
      .where('id', 'in', [pendingId, uploadedId])
      .execute();
    const job = abortStaleUploadsJob(context.settings, context.store, {
      info: () => undefined,
      warn: () => undefined,
    });
    await runJob(job, now);
    expect((await objectRow(context.db, pendingId))?.state).toBe('deleting');
    expect((await objectRow(context.db, uploadedId))?.state).toBe('uploaded');
  });

  it('claims the row first, so a failed abort call leaves it deleting for collect-objects', async () => {
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
      .set({ createdAt: new Date(now.getTime() - 365 * DAY_MS) })
      .where('id', '=', id)
      .execute();
    const failing: ObjectStore = {
      ...context.store,
      abortMultipartUpload: async () => {
        throw new StorageUnavailableError(new Error('down'));
      },
    };
    const job = abortStaleUploadsJob(context.settings, failing, {
      info: () => undefined,
      warn: () => undefined,
    });
    expect(await runJob(job, now)).toEqual({ processed: 1, remaining: false });
    const row = await objectRow(context.db, id);
    expect(row?.state).toBe('deleting');
    expect(row?.multipartUploadId).not.toBeNull();
  });
});

describe('collect-objects', () => {
  it('only collects rows whose grace has passed and keeps others', async () => {
    const { user, medias } = await newOwner(context, 2);
    const early = await uploadSingle(context, user.sessionToken, medias[0]!.mediaId, bytesOfSize(SMALL_BYTES));
    const late = await uploadSingle(context, user.sessionToken, medias[1]!.mediaId, bytesOfSize(SMALL_BYTES));
    const now = new Date();
    await context.db
      .updateTable('objects')
      .set({ state: 'deleting', deleteAfter: new Date(now.getTime() - HOUR_MS) })
      .where('id', '=', early)
      .execute();
    await context.db
      .updateTable('objects')
      .set({ state: 'deleting', deleteAfter: new Date(now.getTime() + HOUR_MS) })
      .where('id', '=', late)
      .execute();
    const earlyRow = await objectRow(context.db, early);
    const job = collectObjectsJob(context.settings, context.store, {
      info: () => undefined,
      warn: () => undefined,
    });
    expect(await runJob(job, now)).toEqual({ processed: 1, remaining: false });
    expect(await objectRow(context.db, early)).toBeUndefined();
    expect(await context.store.headObject(earlyRow!.objectKey)).toBeNull();
    expect((await objectRow(context.db, late))?.state).toBe('deleting');
    expect(await usedBytesOf(context.db, user.userId)).toBe(SMALL_BYTES);
  });

  it('treats an already missing bucket key as deleted', async () => {
    const { user, medias } = await newOwner(context);
    const id = randomId();
    await presign(
      context,
      user.sessionToken,
      presignBody({ id, mediaId: medias[0]!.mediaId, sizeBytes: PENDING_BYTES }),
    );
    const now = new Date();
    await context.db
      .updateTable('objects')
      .set({ state: 'deleting', deleteAfter: new Date(now.getTime() - HOUR_MS) })
      .where('id', '=', id)
      .execute();
    const job = collectObjectsJob(context.settings, context.store, {
      info: () => undefined,
      warn: () => undefined,
    });
    expect(await runJob(job, now)).toEqual({ processed: 1, remaining: false });
    expect(await objectRow(context.db, id)).toBeUndefined();
    expect(await usedBytesOf(context.db, user.userId)).toBe(0);
  });

  it('leaves the row and logs object.delete_failed when the bucket delete fails', async () => {
    const { user, medias } = await newOwner(context);
    const id = await uploadSingle(context, user.sessionToken, medias[0]!.mediaId, bytesOfSize(SMALL_BYTES));
    const now = new Date();
    await context.db
      .updateTable('objects')
      .set({ state: 'deleting', deleteAfter: new Date(now.getTime() - HOUR_MS) })
      .where('id', '=', id)
      .execute();
    const events: unknown[] = [];
    const failing: ObjectStore = {
      ...context.store,
      deleteObject: async () => {
        throw new StorageUnavailableError(new Error('down'));
      },
    };
    const job = collectObjectsJob(context.settings, failing, {
      info: (entry: unknown) => void events.push(entry),
      warn: (entry: unknown) => void events.push(entry),
    });
    expect(await runJob(job, now)).toEqual({ processed: 0, remaining: false });
    expect((await objectRow(context.db, id))?.state).toBe('deleting');
    expect(await usedBytesOf(context.db, user.userId)).toBe(SMALL_BYTES);
    expect(JSON.stringify(events)).toContain('object.delete_failed');
    expect(JSON.stringify(events)).toContain(id);
  });

  it('reports remaining work when a full batch was collected', async () => {
    const { user, medias } = await newOwner(context);
    const now = new Date();
    const rows = Array.from({ length: JOB_BATCH_SIZE }, () => randomId()).map((id) => ({
      id,
      ownerId: user.userId,
      mediaId: null,
      variant: 'original' as const,
      objectKey: objectKey(user.userId, id),
      sizeBytes: 40,
      state: 'deleting' as const,
      multipartUploadId: null,
      createdAt: now,
      uploadedAt: null,
      deleteAfter: new Date(now.getTime() - HOUR_MS),
    }));
    expect(medias).toHaveLength(1);
    await context.db.insertInto('objects').values(rows).execute();
    await context.db
      .updateTable('users')
      .set({ usedBytes: 40 * JOB_BATCH_SIZE })
      .where('id', '=', user.userId)
      .execute();
    const job = collectObjectsJob(context.settings, context.store, {
      info: () => undefined,
      warn: () => undefined,
    });
    expect(await runJob(job, now)).toEqual({ processed: JOB_BATCH_SIZE, remaining: true });
    expect(await usedBytesOf(context.db, user.userId)).toBe(0);
  });
});

describe('10. reconcile-usage', () => {
  it('restores used_bytes from the object sizes and logs the correction', async () => {
    const { user, medias } = await newOwner(context, 2);
    await uploadSingle(context, user.sessionToken, medias[0]!.mediaId, bytesOfSize(SMALL_BYTES));
    const issued = presignOf(
      await presign(
        context,
        user.sessionToken,
        presignBody({ mediaId: medias[1]!.mediaId, sizeBytes: PENDING_BYTES }),
      ),
    );
    expect(issued.mode).toBe('single');
    const expected = SMALL_BYTES + PENDING_BYTES;
    await context.db
      .updateTable('users')
      .set({ usedBytes: CORRUPT_USED_BYTES })
      .where('id', '=', user.userId)
      .execute();
    const events: Record<string, unknown>[] = [];
    const job = reconcileUsageJob(context.settings, {
      info: (entry: Record<string, unknown>) => void events.push(entry),
      warn: (entry: Record<string, unknown>) => void events.push(entry),
    });
    const result = (await runJob(job)) as { remaining: boolean };
    expect(result.remaining).toBe(false);
    expect(await usedBytesOf(context.db, user.userId)).toBe(expected);
    const logged = events.find((entry) => entry['event'] === 'usage.reconciled' && entry['userId'] === user.userId);
    expect(logged).toMatchObject({ oldBytes: CORRUPT_USED_BYTES, newBytes: expected });
  });

  it('sets a user without objects to zero and leaves correct users unlogged', async () => {
    const empty = await newOwner(context);
    const correct = await newOwner(context);
    await uploadSingle(context, correct.user.sessionToken, correct.medias[0]!.mediaId, bytesOfSize(SMALL_BYTES));
    await context.db
      .updateTable('users')
      .set({ usedBytes: CORRUPT_USED_BYTES })
      .where('id', '=', empty.user.userId)
      .execute();
    const events: Record<string, unknown>[] = [];
    const job = reconcileUsageJob(context.settings, {
      info: (entry: Record<string, unknown>) => void events.push(entry),
      warn: (entry: Record<string, unknown>) => void events.push(entry),
    });
    await runJob(job);
    expect(await usedBytesOf(context.db, empty.user.userId)).toBe(0);
    expect(await usedBytesOf(context.db, correct.user.userId)).toBe(SMALL_BYTES);
    const reconciledUsers = events
      .filter((entry) => entry['event'] === 'usage.reconciled')
      .map((entry) => entry['userId']);
    expect(reconciledUsers).toContain(empty.user.userId);
    expect(reconciledUsers).not.toContain(correct.user.userId);
  });

  it('counts objects in every state', async () => {
    const { user, medias } = await newOwner(context);
    const id = await uploadSingle(context, user.sessionToken, medias[0]!.mediaId, bytesOfSize(SMALL_BYTES));
    await context.db
      .updateTable('objects')
      .set({ state: 'deleting', deleteAfter: new Date() })
      .where('id', '=', id)
      .execute();
    await context.db.updateTable('users').set({ usedBytes: 0 }).where('id', '=', user.userId).execute();
    await runJob(reconcileUsageJob(context.settings, { info: () => undefined, warn: () => undefined }));
    expect(await usedBytesOf(context.db, user.userId)).toBe(SMALL_BYTES);
  });
});
