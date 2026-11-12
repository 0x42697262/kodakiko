import { sql, type Kysely } from 'kysely';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { downloadResponse, errorResponseSchema } from '@kodakiko/contract';
import { entriesRepository } from '../../src/adapters/db/entries-repository.ts';
import { sequenceRepository } from '../../src/adapters/db/sequence-repository.ts';
import { createUsersRepository } from '../../src/adapters/db/users-repository.ts';
import type { Database } from '../../src/adapters/db/schema.ts';
import { JOB_BATCH_SIZE } from '../../src/config/constants.ts';
import { AppError } from '../../src/domain/errors.ts';
import { eraseAccountsJob } from '../../src/jobs/erase-accounts.ts';
import { randomId } from '../../src/platform/ids.ts';
import { createSequenceService } from '../../src/services/sequence-service.ts';
import {
  DAY_MS,
  addMedia,
  createEntriesContext,
  createEntryFor,
  deleteEntry,
  entryDraft,
  entryRow,
  insertSharedCollectionFixture,
  membershipSeq,
  newUser,
  patchEntry,
  postEntry,
  randomBlob,
  removeMedia,
  restoreEntry,
  send,
  trashEntry,
  wrapOf,
  type EntriesContext,
} from './entries-helpers.ts';
import { createTestDatabase, type TestDatabase } from './helpers.ts';
import {
  bytesOfSize,
  confirm,
  download,
  presign,
  presignBody,
  presignOf,
  putBytes,
  uploadSingle,
} from './objects-helpers.ts';

const OBJECT_BYTES = 100;
const LOOP_ITERATIONS = 15;
const POLL_INTERVAL_MS = 25;
const POLL_DEADLINE_MS = 5000;
const STATUS_OK = 200;
const STATUS_NO_CONTENT = 204;
const STATUS_NOT_FOUND = 404;
const STATUS_UNPROCESSABLE = 422;
const SERVER_ERROR_MIN = 500;
const DELETION_GRACE_DAYS = 7;
const OVER_BATCH = JOB_BATCH_SIZE + 1;

let testDatabase: TestDatabase;
let context: EntriesContext;

beforeAll(async () => {
  testDatabase = await createTestDatabase();
  context = await createEntriesContext(testDatabase, { env: { FLAG_SHARING: 'true' } });
});

afterAll(async () => {
  await context.close();
  await testDatabase.drop();
});

function sleep(milliseconds: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, milliseconds));
}

async function waitForLockWait(db: Kysely<Database>): Promise<void> {
  const deadline = Date.now() + POLL_DEADLINE_MS;
  while (Date.now() < deadline) {
    const result = await sql<{ waiting: number }>`
      select count(*)::int as waiting
      from pg_stat_activity
      where datname = current_database() and wait_event_type = 'Lock'`.execute(db);
    if ((result.rows[0]?.waiting ?? 0) > 0) return;
    await sleep(POLL_INTERVAL_MS);
  }
  throw new Error('no statement ever waited for a lock');
}

async function insertUploadedObject(
  db: Kysely<Database>,
  ownerId: string,
  mediaId: string | null,
): Promise<string> {
  const id = randomId();
  await db
    .insertInto('objects')
    .values({
      id,
      ownerId,
      mediaId,
      variant: 'original',
      objectKey: `v1/${ownerId}/${id}`,
      sizeBytes: OBJECT_BYTES,
      state: 'uploaded',
      multipartUploadId: null,
      partSizeBytes: null,
      createdAt: new Date(),
      uploadedAt: new Date(),
      deleteAfter: null,
    })
    .execute();
  return id;
}

async function insertManyUploadedObjects(
  db: Kysely<Database>,
  ownerId: string,
  count: number,
): Promise<void> {
  const rows = Array.from({ length: count }, () => {
    const id = randomId();
    return {
      id,
      ownerId,
      mediaId: null,
      variant: 'original' as const,
      objectKey: `v1/${ownerId}/${id}`,
      sizeBytes: OBJECT_BYTES,
      state: 'uploaded' as const,
      multipartUploadId: null,
      partSizeBytes: null,
      createdAt: new Date(),
      uploadedAt: new Date(),
      deleteAfter: null,
    };
  });
  await db.insertInto('objects').values(rows).execute();
}

describe('review 1: lock order entry, media, objects, collections', () => {
  it('lets a transaction holding an object lock bump the entry while a delete is in flight', async () => {
    const owner = await newUser(context);
    const draft = await createEntryFor(context, owner);
    const objectId = await insertUploadedObject(context.db, owner.userId, draft.mediaIds[0] as string);
    const sequence = createSequenceService();
    let deletion: ReturnType<typeof deleteEntry> | undefined;
    await context.db.transaction().execute(async (trx) => {
      await trx.selectFrom('objects').select('id').where('id', '=', objectId).forUpdate().execute();
      deletion = deleteEntry(context, owner.token, draft.id);
      await waitForLockWait(context.db);
      await sequence.bumpEntry(trx, draft.id, new Date());
    });
    const result = await (deletion as ReturnType<typeof deleteEntry>);
    expect(result.status).toBe(STATUS_NO_CONTENT);
    expect(await entryRow(context.db, draft.id)).toBeUndefined();
    const object = await context.db
      .selectFrom('objects')
      .select('state')
      .where('id', '=', objectId)
      .executeTakeFirstOrThrow();
    expect(object.state).toBe('deleting');
  });

  it('survives concurrent DELETE and confirm loops on the same entry without a server error', async () => {
    const owner = await newUser(context);
    const statuses: number[] = [];
    for (let iteration = 0; iteration < LOOP_ITERATIONS; iteration += 1) {
      const draft = await createEntryFor(context, owner);
      const objectId = randomId();
      const issued = presignOf(
        await presign(
          context,
          owner.token,
          presignBody({ id: objectId, mediaId: draft.mediaIds[0] as string, sizeBytes: OBJECT_BYTES }),
        ),
      );
      if (issued.mode !== 'single') throw new Error('expected a single upload');
      const put = await putBytes(issued.url, bytesOfSize(OBJECT_BYTES));
      expect(put.ok).toBe(true);
      const [removed, confirmed] = await Promise.all([
        deleteEntry(context, owner.token, draft.id),
        confirm(context, owner.token, objectId),
      ]);
      expect(removed.status).toBe(STATUS_NO_CONTENT);
      expect([STATUS_OK, STATUS_NOT_FOUND]).toContain(confirmed.status);
      statuses.push(removed.status, confirmed.status);
    }
    for (const status of statuses) expect(status).toBeLessThan(SERVER_ERROR_MIN);
  });

  it('marks a pending object whose insert was in flight while the delete waited for the media lock', async () => {
    const owner = await newUser(context);
    const draft = await createEntryFor(context, owner);
    const objectId = randomId();
    let deletion: ReturnType<typeof deleteEntry> | undefined;
    await context.db.transaction().execute(async (trx) => {
      await trx
        .insertInto('objects')
        .values({
          id: objectId,
          ownerId: owner.userId,
          mediaId: draft.mediaIds[0] as string,
          variant: 'original',
          objectKey: `v1/${owner.userId}/${objectId}`,
          sizeBytes: OBJECT_BYTES,
          state: 'pending',
          multipartUploadId: null,
          partSizeBytes: null,
          createdAt: new Date(),
          uploadedAt: null,
          deleteAfter: null,
        })
        .execute();
      deletion = deleteEntry(context, owner.token, draft.id);
      await waitForLockWait(context.db);
    });
    const result = await (deletion as ReturnType<typeof deleteEntry>);
    expect(result.status).toBe(STATUS_NO_CONTENT);
    const object = await context.db
      .selectFrom('objects')
      .select(['state', 'deleteAfter'])
      .where('id', '=', objectId)
      .executeTakeFirstOrThrow();
    expect(object.state).toBe('deleting');
    expect(object.deleteAfter).not.toBeNull();
  });
});

describe('review 4: downloads of trashed entries', () => {
  it('refuses a recipient the object of a trashed entry while the owner still downloads it', async () => {
    const owner = await newUser(context);
    const recipient = await newUser(context);
    const shared = await insertSharedCollectionFixture(context.db, {
      ownerId: owner.userId,
      recipientId: recipient.userId,
    });
    const draft = await createEntryFor(context, owner, {
      collectionIds: [owner.homeId, shared.collectionId],
    });
    const objectId = await uploadSingle(
      context,
      owner.token,
      draft.mediaIds[0] as string,
      bytesOfSize(OBJECT_BYTES),
    );
    const visible = await download(context, recipient.token, objectId);
    expect(visible.status).toBe(STATUS_OK);
    expect(downloadResponse.safeParse(visible.json).success).toBe(true);
    await trashEntry(context, owner.token, draft.id);
    const refused = await download(context, recipient.token, objectId);
    expect(refused.status).toBe(STATUS_NOT_FOUND);
    const ownerView = await download(context, owner.token, objectId);
    expect(ownerView.status).toBe(STATUS_OK);
    await restoreEntry(context, owner.token, draft.id);
    expect((await download(context, recipient.token, objectId)).status).toBe(STATUS_OK);
  });
});

describe('review 6: a share recipient cannot write', () => {
  it('answers 404 for every write and leaves rows and seqs unchanged', async () => {
    const owner = await newUser(context);
    const recipient = await newUser(context);
    const shared = await insertSharedCollectionFixture(context.db, {
      ownerId: owner.userId,
      recipientId: recipient.userId,
    });
    const draft = await createEntryFor(context, owner, {
      collectionIds: [owner.homeId, shared.collectionId],
    });
    const mediaId = draft.mediaIds[0] as string;
    const before = await entryRow(context.db, draft.id);
    const homeSeq = await membershipSeq(context.db, owner.homeId, draft.id);
    const sharedSeq = await membershipSeq(context.db, shared.collectionId, draft.id);
    const results = [
      await patchEntry(context, recipient.token, draft.id, randomBlob(), 1),
      await trashEntry(context, recipient.token, draft.id),
      await restoreEntry(context, recipient.token, draft.id),
      await deleteEntry(context, recipient.token, draft.id),
      await addMedia(context, recipient.token, draft.id, {
        mediaId: randomId(),
        mediaKeyByEntry: wrapOf(),
      }),
      await removeMedia(context, recipient.token, mediaId),
    ];
    for (const result of results) {
      expect(result.status).toBe(STATUS_NOT_FOUND);
      expect(errorResponseSchema.parse(result.json).error.code).toBe('NOT_FOUND');
    }
    expect(await entryRow(context.db, draft.id)).toEqual(before);
    expect(await membershipSeq(context.db, owner.homeId, draft.id)).toBe(homeSeq);
    expect(await membershipSeq(context.db, shared.collectionId, draft.id)).toBe(sharedSeq);
    const media = await context.db
      .selectFrom('media')
      .select('id')
      .where('entryId', '=', draft.id)
      .execute();
    expect(media.map((row) => row.id)).toEqual([mediaId]);
  });
});

describe('review 8: duplicate media ids', () => {
  it('answers 422 naming the media and creates nothing', async () => {
    const owner = await newUser(context);
    const draft = entryDraft({ homeId: owner.homeId, mediaCount: 2 });
    const media = draft.body['media'] as { mediaId: string; mediaKeyByEntry: string }[];
    const duplicated = { ...draft.body, media: [media[0], { ...media[1], mediaId: media[0]?.mediaId }] };
    const result = await postEntry(context, owner.token, duplicated);
    expect(result.status).toBe(STATUS_UNPROCESSABLE);
    const parsed = errorResponseSchema.parse(result.json);
    expect(parsed.error.code).toBe('VALIDATION_FAILED');
    expect(JSON.stringify(parsed.error.details)).toContain('media');
    expect(await entryRow(context.db, draft.id)).toBeUndefined();
    const rows = await context.db
      .selectFrom('media')
      .select('id')
      .where('id', '=', media[0]?.mediaId as string)
      .execute();
    expect(rows).toEqual([]);
  });
});

describe('review 9: purge selection comparison', () => {
  it('selects an entry trashed exactly at the cutoff', async () => {
    const owner = await newUser(context);
    const draft = await createEntryFor(context, owner);
    const cutoff = new Date(context.clock.now().getTime() - DAY_MS);
    await trashEntry(context, owner.token, draft.id);
    await context.db.updateTable('entries').set({ trashedAt: cutoff }).where('id', '=', draft.id).execute();
    const ids = await entriesRepository.selectTrashedBefore(context.db, cutoff, JOB_BATCH_SIZE);
    expect(ids).toContain(draft.id);
    const earlier = await entriesRepository.selectTrashedBefore(
      context.db,
      new Date(cutoff.getTime() - 1),
      JOB_BATCH_SIZE,
    );
    expect(earlier).not.toContain(draft.id);
  });
});

describe('review 10: adapters do not throw AppError', () => {
  it('returns undefined from allocate for a missing collection', async () => {
    const result = await context.db
      .transaction()
      .execute((trx) => sequenceRepository.allocate(trx, [randomId()], new Date()));
    expect(result).toBeUndefined();
  });

  it('rolls the allocation back and maps a missing collection to NOT_FOUND in the service', async () => {
    const owner = await newUser(context);
    const sequence = createSequenceService();
    const outcome = await context.db
      .transaction()
      .execute((trx) => sequence.allocate(trx, [owner.homeId, randomId()], new Date()))
      .catch((error: unknown) => error);
    expect(outcome).toBeInstanceOf(AppError);
    expect((outcome as AppError).code).toBe('NOT_FOUND');
    const home = await context.db
      .selectFrom('collections')
      .select('lastSeq')
      .where('id', '=', owner.homeId)
      .executeTakeFirstOrThrow();
    expect(home.lastSeq).toBe(0);
  });

  it('keeps allocating in order for existing collections', async () => {
    const owner = await newUser(context);
    const seqs = await context.db
      .transaction()
      .execute((trx) => sequenceRepository.allocate(trx, [owner.homeId], new Date()));
    expect(seqs?.get(owner.homeId)).toBe(1);
  });
});

describe('review 11: erase-accounts is bounded and skips users waiting for collection', () => {
  async function requestDeletion(token: string): Promise<void> {
    const result = await send(context.app, 'POST', '/api/v1/users/me/delete', { token });
    expect(result.status).toBe(202);
  }

  function eraseAt(now: Date) {
    return eraseAccountsJob(context.settings, context.app.log).run({
      db: context.db,
      now,
      signal: new AbortController().signal,
    });
  }

  it('selects only actionable due users, in due order', async () => {
    const stuck = await newUser(context);
    const empty = await newUser(context);
    const working = await newUser(context);
    const later = await newUser(context);
    for (const user of [stuck, empty, working, later]) await requestDeletion(user.token);
    const base = context.clock.now().getTime();
    const dueAt = new Map([
      [stuck.userId, new Date(base + 1 * DAY_MS)],
      [empty.userId, new Date(base + 2 * DAY_MS)],
      [working.userId, new Date(base + 3 * DAY_MS)],
      [later.userId, new Date(base + 30 * DAY_MS)],
    ]);
    for (const [userId, due] of dueAt) {
      await context.db.updateTable('users').set({ deletionDueAt: due }).where('id', '=', userId).execute();
    }
    const stuckObject = await insertUploadedObject(context.db, stuck.userId, null);
    await context.db
      .updateTable('objects')
      .set({ state: 'deleting', deleteAfter: new Date() })
      .where('id', '=', stuckObject)
      .execute();
    await insertUploadedObject(context.db, working.userId, null);
    const now = new Date(base + 10 * DAY_MS);
    const selected = await createUsersRepository(context.db).selectDueForErasure(now, JOB_BATCH_SIZE);
    const ours = selected
      .map((user) => user.id)
      .filter((id) => dueAt.has(id));
    expect(ours).toEqual([empty.userId, working.userId]);
  });

  it('marks at most one batch of objects per run and keeps the user until all are collected', async () => {
    const user = await newUser(context);
    await requestDeletion(user.token);
    await insertManyUploadedObjects(context.db, user.userId, OVER_BATCH);
    const now = new Date(context.clock.now().getTime() + (DELETION_GRACE_DAYS + 1) * DAY_MS);
    const countState = async (state: 'uploaded' | 'deleting') =>
      Number(
        (
          await context.db
            .selectFrom('objects')
            .select((builder) => builder.fn.countAll<number>().as('total'))
            .where('ownerId', '=', user.userId)
            .where('state', '=', state)
            .executeTakeFirstOrThrow()
        ).total,
      );
    await eraseAt(now);
    expect(await countState('deleting')).toBe(JOB_BATCH_SIZE);
    expect(await countState('uploaded')).toBe(1);
    await eraseAt(now);
    expect(await countState('deleting')).toBe(OVER_BATCH);
    expect(await countState('uploaded')).toBe(0);
    const stillThere = await context.db
      .selectFrom('users')
      .select('id')
      .where('id', '=', user.userId)
      .execute();
    expect(stillThere).toHaveLength(1);
  });
});
