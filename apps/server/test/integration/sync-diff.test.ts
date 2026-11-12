import { randomBytes } from 'node:crypto';
import { CamelCasePlugin, Kysely, PostgresDialect, sql } from 'kysely';
import pg from 'pg';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { membershipsSinceQuery } from '../../src/adapters/db/diff-repository.ts';
import type { Database } from '../../src/adapters/db/schema.ts';
import { sequenceRepository } from '../../src/adapters/db/sequence-repository.ts';
import { pruneTombstonesJob } from '../../src/jobs/prune-tombstones.ts';
import { errorCodeOf } from './auth-helpers.ts';
import {
  DAY_MS,
  SECOND_MS,
  collectionLastSeq,
  createEntriesContext,
  createEntryFor,
  deleteEntry,
  diff,
  diffOf,
  getEntry,
  insertSharedCollectionFixture,
  membershipSeq,
  newUser,
  patchEntry,
  randomBlob,
  renameCollection,
  restoreEntry,
  send,
  syncState,
  syncStateOf,
  trashEntry,
  type EntriesContext,
} from './entries-helpers.ts';
import { createTestDatabase, type TestDatabase } from './helpers.ts';
import { bytesOfSize, presign, presignBody, uploadSingle } from './objects-helpers.ts';

const OBJECT_BYTES = 100;
const MAX_DIFF_PAGE = 10;
const OVER_MAX_ENTRIES = 12;
const PAGE_SIZE = 3;
const PAGED_ENTRIES = 7;
const BIG_LIMIT = 500;
const PRUNE_AFTER_DAYS = 91;
const QUERY_COUNT_ENTRIES = 50;
const QUERY_COUNT_MAX = 8;
const SMALL_PAGE_ENTRIES = 5;
const MEDIA_PER_COUNTED_ENTRY = 2;
const UPLOADED_OBJECT_STEP = 10;
const STATUS_OK = 200;
const STATUS_NOT_FOUND = 404;
const STATUS_UNAUTHORIZED = 401;
const QUERY_LOG_LEVEL = 'query';
const TRANSACTION_CONTROL = /^(start transaction|begin|commit|rollback)/;
const POOL_MAX = 5;

let testDatabase: TestDatabase;
let context: EntriesContext;

beforeAll(async () => {
  testDatabase = await createTestDatabase();
  context = await createEntriesContext(testDatabase, {
    env: { FLAG_SHARING: 'true', MAX_DIFF_PAGE_SIZE: String(MAX_DIFF_PAGE) },
  });
});

afterAll(async () => {
  await context.close();
  await testDatabase.drop();
});

function runPrune(now: Date) {
  return pruneTombstonesJob(context.settings).run({
    db: context.db,
    now,
    signal: new AbortController().signal,
  });
}

describe('10. object hook', () => {
  it('bumps the membership seq on confirm and lists only uploaded objects in the diff', async () => {
    const user = await newUser(context);
    const draft = await createEntryFor(context, user);
    const mediaId = draft.mediaIds[0] as string;
    const created = await membershipSeq(context.db, user.homeId, draft.id);
    const objectId = await uploadSingle(context, user.token, mediaId, bytesOfSize(OBJECT_BYTES));
    const confirmed = await membershipSeq(context.db, user.homeId, draft.id);
    expect(confirmed).toBe(created + 1);
    const pendingId = 'QQQQQQQQQQQQQQQQQQQQQQ';
    const issued = await presign(
      context,
      user.token,
      presignBody({ id: pendingId, mediaId, variant: 'thumbnail', sizeBytes: OBJECT_BYTES }),
    );
    expect(issued.status).toBe(STATUS_OK);
    expect(await membershipSeq(context.db, user.homeId, draft.id)).toBe(confirmed);
    const page = diffOf(await diff(context, user.token, user.homeId, 0));
    expect(page.objects.map((object) => [object.id, object.state])).toEqual([
      [objectId, 'uploaded'],
    ]);
    expect(page.media.map((media) => media.id)).toEqual([mediaId]);
    expect(page.entries.map((entry) => entry.id)).toEqual([draft.id]);
  });
});

describe('11. pagination', () => {
  it('pages 3, 3, 1 with hasMore true, true, false and equals one big page', async () => {
    const user = await newUser(context);
    for (let count = 0; count < PAGED_ENTRIES; count += 1) {
      await createEntryFor(context, user);
    }
    const pages = [];
    let since = 0;
    for (;;) {
      const page = diffOf(await diff(context, user.token, user.homeId, since, PAGE_SIZE));
      pages.push(page);
      since = page.nextCursor;
      if (!page.hasMore) break;
    }
    expect(pages.map((page) => page.memberships.length)).toEqual([3, 3, 1]);
    expect(pages.map((page) => page.hasMore)).toEqual([true, true, false]);
    const whole = diffOf(await diff(context, user.token, user.homeId, 0, BIG_LIMIT));
    const union = pages.flatMap((page) => page.memberships);
    expect(union).toEqual(whole.memberships);
    expect(pages.flatMap((page) => page.entries)).toEqual(whole.entries);
    expect(pages.flatMap((page) => page.media)).toEqual(whole.media);
    expect(whole.hasMore).toBe(false);
    expect(whole.nextCursor).toBe(PAGED_ENTRIES);
  });

  it('clamps a limit above the maximum and uses it as the default', async () => {
    const user = await newUser(context);
    for (let count = 0; count < OVER_MAX_ENTRIES; count += 1) {
      await createEntryFor(context, user);
    }
    const defaulted = diffOf(await diff(context, user.token, user.homeId, 0));
    expect(defaulted.memberships).toHaveLength(MAX_DIFF_PAGE);
    expect(defaulted.hasMore).toBe(true);
    const clamped = diffOf(await diff(context, user.token, user.homeId, 0, BIG_LIMIT));
    expect(clamped.memberships).toHaveLength(MAX_DIFF_PAGE);
    expect(clamped.hasMore).toBe(true);
  });

  it('answers 422 for a limit of 0 and a negative since', async () => {
    const user = await newUser(context);
    expect((await diff(context, user.token, user.homeId, 0, 0)).status).toBe(422);
    expect((await diff(context, user.token, user.homeId, -1)).status).toBe(422);
  });
});

describe('12. snapshot cursor', () => {
  it('returns last_seq as the cursor when the final page is empty', async () => {
    const user = await newUser(context);
    await createEntryFor(context, user);
    await createEntryFor(context, user);
    await createEntryFor(context, user);
    await context.db
      .transaction()
      .execute((trx) => sequenceRepository.allocate(trx, [user.homeId], context.clock.now()));
    const lastSeq = await collectionLastSeq(context.db, user.homeId);
    expect(lastSeq).toBe(4);
    const page = diffOf(await diff(context, user.token, user.homeId, lastSeq - 1));
    expect(page.memberships).toEqual([]);
    expect(page.hasMore).toBe(false);
    expect(page.nextCursor).toBe(lastSeq);
  });
});

describe('13. resync', () => {
  it('raises the pruned seq and answers resyncRequired only for a cursor below it', async () => {
    const user = await newUser(context);
    const doomed = await createEntryFor(context, user);
    const kept = await createEntryFor(context, user);
    await deleteEntry(context, user.token, doomed.id);
    const tombstoneSeq = await collectionLastSeq(context.db, user.homeId);
    expect(tombstoneSeq).toBe(3);
    const now = new Date(context.clock.now().getTime() + PRUNE_AFTER_DAYS * DAY_MS);
    const result = await runPrune(now);
    expect(result).toEqual({ processed: 1, remaining: false });
    const row = await context.db
      .selectFrom('collections')
      .select('tombstonesPrunedThroughSeq')
      .where('id', '=', user.homeId)
      .executeTakeFirstOrThrow();
    expect(row.tombstonesPrunedThroughSeq).toBe(tombstoneSeq);
    const stale = diffOf(await diff(context, user.token, user.homeId, 1));
    expect(stale).toEqual({
      collectionId: user.homeId,
      memberships: [],
      entries: [],
      media: [],
      objects: [],
      nextCursor: 0,
      hasMore: false,
      resyncRequired: true,
    });
    const fresh = diffOf(await diff(context, user.token, user.homeId, 0));
    expect(fresh.resyncRequired).toBe(false);
    expect(fresh.memberships.map((membership) => membership.entryId)).toEqual([kept.id]);
    const atPruned = diffOf(await diff(context, user.token, user.homeId, tombstoneSeq));
    expect(atPruned.resyncRequired).toBe(false);
  });

  it('keeps tombstones younger than the cutoff and never lowers the pruned seq', async () => {
    const user = await newUser(context);
    const first = await createEntryFor(context, user);
    const second = await createEntryFor(context, user);
    await deleteEntry(context, user.token, first.id);
    await deleteEntry(context, user.token, second.id);
    const secondTombstone = await collectionLastSeq(context.db, user.homeId);
    await context.db
      .updateTable('membershipTombstones')
      .set({ removedAt: new Date(context.clock.now().getTime() + 10 * DAY_MS) })
      .where('collectionId', '=', user.homeId)
      .where('seq', '=', secondTombstone)
      .execute();
    const now = new Date(context.clock.now().getTime() + PRUNE_AFTER_DAYS * DAY_MS);
    await runPrune(now);
    const remaining = await context.db
      .selectFrom('membershipTombstones')
      .select('seq')
      .where('collectionId', '=', user.homeId)
      .execute();
    expect(remaining.map((tombstone) => tombstone.seq)).toEqual([secondTombstone]);
    const pruned = async () =>
      (
        await context.db
          .selectFrom('collections')
          .select('tombstonesPrunedThroughSeq')
          .where('id', '=', user.homeId)
          .executeTakeFirstOrThrow()
      ).tombstonesPrunedThroughSeq;
    expect(await pruned()).toBe(secondTombstone - 1);
    await runPrune(now);
    expect(await pruned()).toBe(secondTombstone - 1);
    expect(await runPrune(now)).toEqual({ processed: 0, remaining: false });
  });
});

describe('14. viewer view', () => {
  it('shows a trashed entry to a recipient as removed and hides it from get', async () => {
    const owner = await newUser(context);
    const recipient = await newUser(context);
    const shared = await insertSharedCollectionFixture(context.db, {
      ownerId: owner.userId,
      recipientId: recipient.userId,
    });
    const draft = await createEntryFor(context, owner, {
      collectionIds: [owner.homeId, shared.collectionId],
    });
    const visible = diffOf(await diff(context, recipient.token, shared.collectionId, 0));
    expect(visible.memberships).toHaveLength(1);
    expect(visible.memberships[0]).toMatchObject({ entryId: draft.id, removed: false });
    expect(visible.memberships[0]?.entryKeyByCollection).not.toBeNull();
    expect(visible.entries.map((entry) => entry.id)).toEqual([draft.id]);
    expect((await getEntry(context, recipient.token, draft.id)).status).toBe(STATUS_OK);
    await trashEntry(context, owner.token, draft.id);
    const hidden = diffOf(await diff(context, recipient.token, shared.collectionId, 0));
    expect(hidden.memberships).toHaveLength(1);
    expect(hidden.memberships[0]).toMatchObject({
      entryId: draft.id,
      removed: true,
      entryKeyByCollection: null,
    });
    expect(hidden.entries).toEqual([]);
    expect(hidden.media).toEqual([]);
    const refused = await getEntry(context, recipient.token, draft.id);
    expect(refused.status).toBe(STATUS_NOT_FOUND);
    const ownerView = await getEntry(context, owner.token, draft.id);
    expect(ownerView.status).toBe(STATUS_OK);
    const ownerDiff = diffOf(await diff(context, owner.token, owner.homeId, 0));
    expect(ownerDiff.entries[0]?.trashedAt).not.toBeNull();
    await restoreEntry(context, owner.token, draft.id);
    const restored = diffOf(await diff(context, recipient.token, shared.collectionId, 0));
    expect(restored.memberships[0]).toMatchObject({ removed: false });
    expect(restored.entries.map((entry) => entry.id)).toEqual([draft.id]);
  });

  it('answers 404 for the diff of a collection the user cannot read', async () => {
    const owner = await newUser(context);
    const stranger = await newUser(context);
    expect((await diff(context, stranger.token, owner.homeId, 0)).status).toBe(STATUS_NOT_FOUND);
    const unknown = await diff(context, stranger.token, 'ZZZZZZZZZZZZZZZZZZZZZZ', 0);
    expect(errorCodeOf(unknown)).toBe('NOT_FOUND');
  });

  it('answers 401 for the diff and sync state without a session', async () => {
    const owner = await newUser(context);
    const anonymous = await send(context.app, 'GET', `/api/v1/collections/${owner.homeId}/diff?since=0`);
    expect(anonymous.status).toBe(STATUS_UNAUTHORIZED);
    expect((await send(context.app, 'GET', '/api/v1/sync/state')).status).toBe(
      STATUS_UNAUTHORIZED,
    );
  });
});

describe('15. sync state', () => {
  it('lists the home collection and changes metaUpdatedAt only on rename', async () => {
    const user = await newUser(context);
    const recipient = await newUser(context);
    const shared = await insertSharedCollectionFixture(context.db, {
      ownerId: recipient.userId,
      recipientId: user.userId,
    });
    const draft = await createEntryFor(context, user);
    const before = syncStateOf(await syncState(context, user.token));
    const home = before.collections.find((collection) => collection.id === user.homeId);
    expect(home).toMatchObject({ kind: 'home', role: 'owner', lastSeq: 1 });
    expect(home?.tombstonesPrunedThroughSeq).toBe(0);
    const viewer = before.collections.find((collection) => collection.id === shared.collectionId);
    expect(viewer).toMatchObject({ kind: 'shared', role: 'viewer' });
    context.clock.advance(SECOND_MS);
    const patched = await patchEntry(context, user.token, draft.id, randomBlob(), 1);
    expect(patched.status).toBe(STATUS_OK);
    const afterPatch = syncStateOf(await syncState(context, user.token));
    const patchedHome = afterPatch.collections.find((collection) => collection.id === user.homeId);
    expect(patchedHome?.lastSeq).toBe(2);
    expect(patchedHome?.metaUpdatedAt).toBe(home?.metaUpdatedAt);
    context.clock.advance(SECOND_MS);
    const renamed = await renameCollection(context, user.token, user.homeId, randomBlob());
    expect(renamed.status).toBe(STATUS_OK);
    const afterRename = syncStateOf(await syncState(context, user.token));
    const renamedHome = afterRename.collections.find((collection) => collection.id === user.homeId);
    expect(renamedHome?.metaUpdatedAt).not.toBe(home?.metaUpdatedAt);
    expect(renamedHome?.lastSeq).toBe(2);
  });
});

describe('query shape', () => {
  it('uses entry_memberships_feed_idx for the membership query', async () => {
    const user = await newUser(context);
    await createEntryFor(context, user);
    const plan = await context.db.transaction().execute(async (trx) => {
      await sql`set local enable_seqscan = off`.execute(trx);
      return membershipsSinceQuery(trx, user.homeId, 0, QUERY_COUNT_ENTRIES).explain('text');
    });
    expect(JSON.stringify(plan)).toContain('entry_memberships_feed_idx');
  });

  it('issues a constant, small number of queries per diff page', async () => {
    const queries: string[] = [];
    const pool = new pg.Pool({ connectionString: testDatabase.url, max: POOL_MAX });
    const countingDb = new Kysely<Database>({
      dialect: new PostgresDialect({ pool }),
      plugins: [new CamelCasePlugin()],
      log: (event) => {
        if (event.level === QUERY_LOG_LEVEL && !TRANSACTION_CONTROL.test(event.query.sql)) {
          queries.push(event.query.sql);
        }
      },
    });
    const counted = await createEntriesContext(testDatabase, {
      db: countingDb,
      env: { FLAG_SHARING: 'true', MAX_DIFF_PAGE_SIZE: '100' },
    });
    try {
      const small = await newUser(counted);
      const large = await newUser(counted);
      const mediaIds: string[] = [];
      for (let count = 0; count < SMALL_PAGE_ENTRIES; count += 1) {
        await createEntryFor(counted, small, { mediaCount: MEDIA_PER_COUNTED_ENTRY });
      }
      for (let count = 0; count < QUERY_COUNT_ENTRIES; count += 1) {
        const draft = await createEntryFor(counted, large, { mediaCount: MEDIA_PER_COUNTED_ENTRY });
        if (count % UPLOADED_OBJECT_STEP === 0) mediaIds.push(draft.mediaIds[0] as string);
      }
      for (const mediaId of mediaIds) {
        await countingDb
          .insertInto('objects')
          .values({
            id: randomBytes(16).toString('base64url'),
            ownerId: large.userId,
            mediaId,
            variant: 'original',
            objectKey: `v1/${large.userId}/${mediaId}`,
            sizeBytes: OBJECT_BYTES,
            state: 'uploaded',
            multipartUploadId: null,
            partSizeBytes: null,
            createdAt: new Date(),
            uploadedAt: new Date(),
            deleteAfter: null,
          })
          .execute();
      }
      queries.length = 0;
      const smallPage = diffOf(await diff(counted, small.token, small.homeId, 0, QUERY_COUNT_ENTRIES));
      const smallCount = queries.length;
      queries.length = 0;
      const largePage = diffOf(await diff(counted, large.token, large.homeId, 0, QUERY_COUNT_ENTRIES));
      const largeCount = queries.length;
      expect(smallPage.entries).toHaveLength(SMALL_PAGE_ENTRIES);
      expect(largePage.entries).toHaveLength(QUERY_COUNT_ENTRIES);
      expect(largePage.objects).toHaveLength(mediaIds.length);
      expect(largeCount).toBe(smallCount);
      expect(largeCount).toBeLessThanOrEqual(QUERY_COUNT_MAX);
    } finally {
      await counted.close();
      await countingDb.destroy();
    }
  });
});
