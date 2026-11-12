import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import {
  collectionLastSeq,
  createEntriesContext,
  createEntryFor,
  entryDraft,
  entryRow,
  insertCollectionFixture,
  newUser,
  patchEntry,
  postEntry,
  randomBlob,
  restoreEntry,
  trashEntry,
  type EntriesContext,
  type EntriesUser,
} from './entries-helpers.ts';
import { createTestDatabase, type TestDatabase } from './helpers.ts';

const PARALLEL_CREATES = 20;
const ROUNDS = 25;
const PARALLEL_TOGGLES = 50;
const STATUS_OK = 200;
const STATUS_CREATED = 201;
const STATUS_CONFLICT = 409;
const SERVER_ERROR_MIN = 500;

let testDatabase: TestDatabase;
let context: EntriesContext;
let owner: EntriesUser;
let sharedId: string;
let first: string;
let second: string;

beforeAll(async () => {
  testDatabase = await createTestDatabase();
  context = await createEntriesContext(testDatabase, { env: { FLAG_SHARING: 'true' } });
  owner = await newUser(context);
  sharedId = await insertCollectionFixture(context.db, { ownerId: owner.userId, kind: 'shared' });
  const draftOne = await createEntryFor(context, owner, {
    collectionIds: [sharedId, owner.homeId],
  });
  first = draftOne.id;
  const draftTwo = await createEntryFor(context, owner, {
    collectionIds: [owner.homeId, sharedId],
  });
  second = draftTwo.id;
});

afterAll(async () => {
  await context.close();
  await testDatabase.drop();
});

describe('3. concurrency', () => {
  it('allocates unique consecutive seqs for parallel creates into one home collection', async () => {
    const user = await newUser(context);
    const results = await Promise.all(
      Array.from({ length: PARALLEL_CREATES }, () =>
        postEntry(context, user.token, entryDraft({ homeId: user.homeId }).body),
      ),
    );
    expect(results.map((result) => result.status)).toEqual(
      Array.from({ length: PARALLEL_CREATES }, () => STATUS_CREATED),
    );
    expect(await collectionLastSeq(context.db, user.homeId)).toBe(PARALLEL_CREATES);
    const seqs = (
      await context.db
        .selectFrom('entryMemberships')
        .select('seq')
        .where('collectionId', '=', user.homeId)
        .execute()
    )
      .map((row) => row.seq)
      .sort((left, right) => left - right);
    expect(seqs).toEqual(Array.from({ length: PARALLEL_CREATES }, (_, position) => position + 1));
  });
});

describe('4. deadlock avoidance', () => {
  it('lets 50 patches across two entries in the same two collections all succeed', async () => {
    const results = [];
    for (let round = 1; round <= ROUNDS; round += 1) {
      results.push(
        ...(await Promise.all([
          patchEntry(context, owner.token, first, randomBlob(), round),
          patchEntry(context, owner.token, second, randomBlob(), round),
        ])),
      );
    }
    expect(results).toHaveLength(ROUNDS * 2);
    expect(results.map((result) => result.status)).toEqual(
      Array.from({ length: ROUNDS * 2 }, () => STATUS_OK),
    );
    expect((await entryRow(context.db, first))?.revision).toBe(ROUNDS + 1);
    expect((await entryRow(context.db, second))?.revision).toBe(ROUNDS + 1);
  });

  it('survives 50 fully parallel patches with unknown revisions without a server error', async () => {
    const targets = [first, second];
    const results = await Promise.all(
      Array.from({ length: PARALLEL_TOGGLES }, (_, position) =>
        patchEntry(context, owner.token, targets[position % targets.length] as string, randomBlob(), 1),
      ),
    );
    for (const result of results) {
      expect([STATUS_OK, STATUS_CONFLICT]).toContain(result.status);
      expect(result.status).toBeLessThan(SERVER_ERROR_MIN);
    }
  });

  it('survives 50 parallel unconditional bumps across both entries and keeps seqs unique', async () => {
    const targets = [first, second];
    const results = await Promise.all(
      Array.from({ length: PARALLEL_TOGGLES }, (_, position) => {
        const target = targets[position % targets.length] as string;
        return position % 2 === 0
          ? trashEntry(context, owner.token, target)
          : restoreEntry(context, owner.token, target);
      }),
    );
    expect(results.map((result) => result.status)).toEqual(
      Array.from({ length: PARALLEL_TOGGLES }, () => STATUS_OK),
    );
    for (const collectionId of [owner.homeId, sharedId]) {
      const rows = await context.db
        .selectFrom('entryMemberships')
        .select('seq')
        .where('collectionId', '=', collectionId)
        .execute();
      const seqs = rows.map((row) => row.seq);
      expect(new Set(seqs).size).toBe(seqs.length);
      expect(Math.max(...seqs)).toBeLessThanOrEqual(await collectionLastSeq(context.db, collectionId));
    }
  });
});
