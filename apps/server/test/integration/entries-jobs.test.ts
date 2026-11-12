import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { collectObjectsJob } from '../../src/jobs/collect-objects.ts';
import { eraseAccountsJob } from '../../src/jobs/erase-accounts.ts';
import { JOB_LOCK_IDS } from '../../src/jobs/keys.ts';
import { pruneTombstonesJob } from '../../src/jobs/prune-tombstones.ts';
import { purgeTrashJob } from '../../src/jobs/purge-trash.ts';
import type { JobDefinition } from '../../src/jobs/scheduler.ts';
import { JOB_TIMEOUT_MS } from '../../src/config/constants.ts';
import { trashCutoff } from '../../src/domain/entries.ts';
import { registerAccount } from './auth-helpers.ts';
import {
  DAY_MS,
  SECOND_MS,
  createEntriesContext,
  createEntryFor,
  entryRow,
  newUser,
  restoreEntry,
  send,
  tombstonesOf,
  trashEntry,
  type EntriesContext,
} from './entries-helpers.ts';
import { createTestDatabase, type TestDatabase } from './helpers.ts';
import { bytesOfSize, objectRow, uploadSingle } from './objects-helpers.ts';

const OBJECT_BYTES = 100;
const DELETION_GRACE_DAYS = 7;
const TRASHED_LONG_AGO_DAYS = 31;
const TRASHED_RECENTLY_DAYS = 29;
const STATUS_ACCEPTED = 202;

let testDatabase: TestDatabase;
let context: EntriesContext;

beforeAll(async () => {
  testDatabase = await createTestDatabase();
  context = await createEntriesContext(testDatabase);
});

afterAll(async () => {
  await context.close();
  await testDatabase.drop();
});

function runJob(job: JobDefinition, now: Date) {
  return job.run({ db: context.db, now, signal: new AbortController().signal });
}

function purgeJob() {
  return purgeTrashJob(context.settings, context.entriesService, context.app.log);
}

function eraseJob() {
  return eraseAccountsJob(context.settings, context.app.log);
}

function setTrashedAt(entryId: string, trashedAt: Date) {
  return context.db.updateTable('entries').set({ trashedAt }).where('id', '=', entryId).execute();
}

describe('job definitions', () => {
  it('uses the planned names, lock ids, intervals and timeout', () => {
    const purge = purgeJob();
    const erase = eraseJob();
    const prune = pruneTombstonesJob(context.settings);
    expect([purge.name, erase.name, prune.name]).toEqual([
      'purge-trash',
      'erase-accounts',
      'prune-tombstones',
    ]);
    expect([purge.lockId, erase.lockId, prune.lockId]).toEqual([1201, 1202, 1203]);
    expect(JOB_LOCK_IDS['purgeTrash']).toBe(1201);
    expect(JOB_LOCK_IDS['eraseAccounts']).toBe(1202);
    expect(JOB_LOCK_IDS['pruneTombstones']).toBe(1203);
    expect(purge.intervalMs).toBe(context.settings.jobs.purgeTrashIntervalMs);
    expect(erase.intervalMs).toBe(context.settings.jobs.eraseAccountsIntervalMs);
    expect(prune.intervalMs).toBe(context.settings.jobs.pruneTombstonesIntervalMs);
    for (const job of [purge, erase, prune]) expect(job.timeoutMs).toBe(JOB_TIMEOUT_MS);
  });
});

describe('16. erase accounts', () => {
  it('moves objects to deleting first and deletes the user after object collection', async () => {
    const username = `erase${Date.now().toString(36)}`;
    const account = await registerAccount(context, username);
    const homeId = account.material.request.homeCollection.id;
    const userId = account.material.request.userId;
    const draft = await createEntryFor(context, {
      token: account.token,
      userId,
      homeId,
      username,
    });
    const objectId = await uploadSingle(
      context,
      account.token,
      draft.mediaIds[0] as string,
      bytesOfSize(OBJECT_BYTES),
    );
    const requested = await send(context.app, 'POST', '/api/v1/users/me/delete', {
      token: account.token,
    });
    expect(requested.status).toBe(STATUS_ACCEPTED);

    const early = await runJob(eraseJob(), context.clock.now());
    expect(early).toEqual({ processed: 0, remaining: false });
    expect(await context.db.selectFrom('users').select('id').where('id', '=', userId).execute()).toHaveLength(1);
    expect((await objectRow(context.db, objectId))?.state).toBe('uploaded');

    context.clock.advance((DELETION_GRACE_DAYS + 1) * DAY_MS);
    const firstRun = await runJob(eraseJob(), context.clock.now());
    expect(firstRun).toEqual({ processed: 0, remaining: false });
    expect((await objectRow(context.db, objectId))?.state).toBe('deleting');
    expect(await context.db.selectFrom('users').select('id').where('id', '=', userId).execute()).toHaveLength(1);

    const collected = await runJob(
      collectObjectsJob(context.settings, context.store, context.app.log),
      new Date(context.clock.now().getTime() + SECOND_MS),
    );
    expect(collected.processed).toBe(1);
    expect(await objectRow(context.db, objectId)).toBeUndefined();

    const secondRun = await runJob(eraseJob(), context.clock.now());
    expect(secondRun).toEqual({ processed: 1, remaining: false });
    expect(await context.db.selectFrom('users').select('id').where('id', '=', userId).execute()).toEqual([]);
    expect(await context.db.selectFrom('collections').select('id').where('ownerId', '=', userId).execute()).toEqual([]);
    expect(await entryRow(context.db, draft.id)).toBeUndefined();
    expect(context.logs.text()).toContain('account.erased');

    const reused = await registerAccount(context, username);
    expect(reused.token).toMatch(/^kdk_s_/);
  });

  it('erases a due user with no objects in one run and ignores an active user', async () => {
    const pending = await newUser(context);
    const active = await newUser(context);
    await send(context.app, 'POST', '/api/v1/users/me/delete', { token: pending.token });
    const due = new Date(context.clock.now().getTime() + (DELETION_GRACE_DAYS + 1) * DAY_MS);
    const result = await runJob(eraseJob(), due);
    expect(result.processed).toBeGreaterThanOrEqual(1);
    const remaining = await context.db
      .selectFrom('users')
      .select('id')
      .where('id', 'in', [pending.userId, active.userId])
      .execute();
    expect(remaining.map((row) => row.id)).toEqual([active.userId]);
  });

  it('is idempotent when run twice', async () => {
    const user = await newUser(context);
    await send(context.app, 'POST', '/api/v1/users/me/delete', { token: user.token });
    const due = new Date(context.clock.now().getTime() + (DELETION_GRACE_DAYS + 1) * DAY_MS);
    await runJob(eraseJob(), due);
    const again = await runJob(eraseJob(), due);
    expect(again.processed).toBe(0);
  });
});

describe('17. purge trash', () => {
  it('deletes an entry trashed 31 days ago and keeps one trashed 29 days ago', async () => {
    const user = await newUser(context);
    const old = await createEntryFor(context, user);
    const recent = await createEntryFor(context, user);
    const objectId = await uploadSingle(
      context,
      user.token,
      old.mediaIds[0] as string,
      bytesOfSize(OBJECT_BYTES),
    );
    await trashEntry(context, user.token, old.id);
    await trashEntry(context, user.token, recent.id);
    const now = context.clock.now();
    await setTrashedAt(old.id, new Date(now.getTime() - TRASHED_LONG_AGO_DAYS * DAY_MS));
    await setTrashedAt(recent.id, new Date(now.getTime() - TRASHED_RECENTLY_DAYS * DAY_MS));
    const result = await runJob(purgeJob(), now);
    expect(result).toEqual({ processed: 1, remaining: false });
    expect(await entryRow(context.db, old.id)).toBeUndefined();
    expect(await entryRow(context.db, recent.id)).toBeDefined();
    const tombstones = await tombstonesOf(context.db, user.homeId);
    expect(tombstones.map((tombstone) => tombstone.entryId)).toEqual([old.id]);
    expect((await objectRow(context.db, objectId))?.state).toBe('deleting');
    expect(context.logs.text()).toContain('entry.purged');
    const again = await runJob(purgeJob(), now);
    expect(again.processed).toBe(0);
  });
});

describe('purge guard', () => {
  it('keeps an entry restored between the select and the remove', async () => {
    const user = await newUser(context);
    const draft = await createEntryFor(context, user);
    await trashEntry(context, user.token, draft.id);
    const now = context.clock.now();
    await setTrashedAt(draft.id, new Date(now.getTime() - TRASHED_LONG_AGO_DAYS * DAY_MS));
    const cutoff = trashCutoff(now, context.settings.entries.trashDays);
    await restoreEntry(context, user.token, draft.id);
    await context.entriesService.removeAsSystem(draft.id, { onlyIfTrashedBefore: cutoff });
    expect(await entryRow(context.db, draft.id)).toBeDefined();
    expect(await tombstonesOf(context.db, user.homeId)).toEqual([]);
  });

  it('keeps an entry trashed after the cutoff and ignores an absent entry', async () => {
    const user = await newUser(context);
    const draft = await createEntryFor(context, user);
    await trashEntry(context, user.token, draft.id);
    const cutoff = new Date(context.clock.now().getTime() - DAY_MS);
    await context.entriesService.removeAsSystem(draft.id, { onlyIfTrashedBefore: cutoff });
    expect(await entryRow(context.db, draft.id)).toBeDefined();
    await context.entriesService.removeAsSystem('ZZZZZZZZZZZZZZZZZZZZZZ', {
      onlyIfTrashedBefore: cutoff,
    });
  });

  it('removes an entry trashed exactly at the cutoff', async () => {
    const user = await newUser(context);
    const draft = await createEntryFor(context, user);
    await trashEntry(context, user.token, draft.id);
    const cutoff = new Date(context.clock.now().getTime() - DAY_MS);
    await setTrashedAt(draft.id, cutoff);
    await context.entriesService.removeAsSystem(draft.id, { onlyIfTrashedBefore: cutoff });
    expect(await entryRow(context.db, draft.id)).toBeUndefined();
  });
});
