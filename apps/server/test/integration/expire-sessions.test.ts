import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { JOB_BATCH_SIZE, JOB_TIMEOUT_MS } from '../../src/config/constants.ts';
import { JOB_LOCK_IDS } from '../../src/jobs/keys.ts';
import { expireSessionsJob } from '../../src/jobs/expire-sessions.ts';
import { createAuthContext, registerAccount, type AuthContext } from './auth-helpers.ts';
import { createTestDatabase, type TestDatabase } from './helpers.ts';
import { randomBytes } from 'node:crypto';
import { randomId } from '../../src/platform/ids.ts';

const HOUR_MS = 3_600_000;
const TOKEN_HASH_BYTES = 32;
const EXTRA_EXPIRED = 5;
const DEVICE = 'Test';

let testDatabase: TestDatabase;
let context: AuthContext;

beforeAll(async () => {
  testDatabase = await createTestDatabase();
  context = await createAuthContext(testDatabase);
});

afterAll(async () => {
  await context.close();
  await testDatabase.drop();
});

async function insertSessions(userId: string, count: number, expiresAt: Date): Promise<void> {
  const created = context.clock.now();
  await context.db
    .insertInto('sessions')
    .values(
      Array.from({ length: count }, () => ({
        id: randomId(),
        userId,
        tokenHash: randomBytes(TOKEN_HASH_BYTES),
        deviceLabel: DEVICE,
        createdAt: created,
        lastUsedAt: created,
        expiresAt,
      })),
    )
    .execute();
}

describe('12. expire-sessions job', () => {
  it('is defined with the planned lock id, interval and timeout', () => {
    const job = expireSessionsJob(context.settings);
    expect(job.name).toBe('expire-sessions');
    expect(job.lockId).toBe(1001);
    expect(JOB_LOCK_IDS['expireSessions']).toBe(1001);
    expect(job.intervalMs).toBe(context.settings.jobs.expireSessionsIntervalMs);
    expect(job.timeoutMs).toBe(JOB_TIMEOUT_MS);
  });

  it('deletes only expired rows', async () => {
    const account = await registerAccount(context, 'job-user');
    const now = context.clock.now();
    await insertSessions(account.material.request.userId, 3, new Date(now.getTime() - HOUR_MS));
    await insertSessions(account.material.request.userId, 2, new Date(now.getTime() + HOUR_MS));
    const job = expireSessionsJob(context.settings);
    const result = await job.run({ db: context.db, now, signal: new AbortController().signal });
    expect(result).toEqual({ processed: 3, remaining: false });
    const rows = await context.db
      .selectFrom('sessions')
      .select('expiresAt')
      .where('userId', '=', account.material.request.userId)
      .execute();
    expect(rows).toHaveLength(3);
    expect(rows.every((row) => row.expiresAt.getTime() > now.getTime())).toBe(true);
  });

  it('reports remaining work when a full batch was deleted', async () => {
    const account = await registerAccount(context, 'job-batch');
    const now = context.clock.now();
    await insertSessions(
      account.material.request.userId,
      JOB_BATCH_SIZE + EXTRA_EXPIRED,
      new Date(now.getTime() - HOUR_MS),
    );
    const job = expireSessionsJob(context.settings);
    const signal = new AbortController().signal;
    const first = await job.run({ db: context.db, now, signal });
    expect(first).toEqual({ processed: JOB_BATCH_SIZE, remaining: true });
    const second = await job.run({ db: context.db, now, signal });
    expect(second).toEqual({ processed: EXTRA_EXPIRED, remaining: false });
  });
});
