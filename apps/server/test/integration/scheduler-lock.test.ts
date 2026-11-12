import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import Fastify from 'fastify';
import { sql } from 'kysely';
import { createDatabase } from '../../src/adapters/db/database.ts';
import { createScheduler, type JobDefinition } from '../../src/jobs/scheduler.ts';
import { systemClock } from '../../src/platform/clock.ts';
import { createTestDatabase, ignorePoolError, testSettings, type TestDatabase } from './helpers.ts';

const LOCK_ID = 424242;
const JOB_DURATION_MS = 500;
const SETTLE_MS = 1500;
const INTERVAL_MS = 3_600_000;
const TIMEOUT_MS = 60_000;
const BUSY_MS = 1000;
const BUSY_SETTLE_MS = 200;

let testDatabase: TestDatabase;

beforeAll(async () => {
  testDatabase = await createTestDatabase();
});

afterAll(async () => {
  await testDatabase.drop();
});

function sleep(milliseconds: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, milliseconds));
}

function quietLogger() {
  return Fastify({ logger: { level: 'fatal' } }).log;
}

describe('scheduler advisory lock', () => {
  it('never runs the job body on two instances at the same time', async () => {
    const settings = testSettings({ DATABASE_URL: testDatabase.url });
    const secondDb = createDatabase(settings.database, ignorePoolError);
    const spans: { start: number; end: number }[] = [];
    const job: JobDefinition = {
      name: 'locked-job',
      lockId: LOCK_ID,
      intervalMs: INTERVAL_MS,
      timeoutMs: TIMEOUT_MS,
      async run() {
        const span = { start: Date.now(), end: 0 };
        spans.push(span);
        await sleep(JOB_DURATION_MS);
        span.end = Date.now();
        return { processed: 1, remaining: false };
      },
    };
    const options = { clock: systemClock, logger: quietLogger(), jobs: [job], enabled: true };
    const first = createScheduler({ ...options, db: testDatabase.db });
    const second = createScheduler({ ...options, db: secondDb });
    first.start();
    second.start();
    await sleep(SETTLE_MS);
    await first.stop();
    await second.stop();

    expect(spans.length).toBeGreaterThanOrEqual(1);
    const ordered = [...spans].sort((left, right) => left.start - right.start);
    ordered.slice(1).forEach((span, position) => {
      expect(span.start).toBeGreaterThanOrEqual(ordered[position]?.end ?? 0);
    });

    const free = await sql<{
      locked: boolean;
    }>`select pg_try_advisory_lock(${LOCK_ID}) as locked`.execute(secondDb);
    expect(free.rows[0]?.locked).toBe(true);
    await secondDb.destroy();
  });

  it('never starts the job when stop is called while the lock is pending', async () => {
    const settings = testSettings({ DATABASE_URL: testDatabase.url, DATABASE_POOL_MAX: '1' });
    const singleConnectionDb = createDatabase(settings.database, ignorePoolError);
    let runs = 0;
    const job: JobDefinition = {
      name: 'pending-lock-job',
      lockId: LOCK_ID + 1,
      intervalMs: INTERVAL_MS,
      timeoutMs: TIMEOUT_MS,
      async run() {
        runs += 1;
        return { processed: 0, remaining: false };
      },
    };
    const busy = sql`select pg_sleep(${BUSY_MS / 1000})`.execute(singleConnectionDb);
    await sleep(BUSY_SETTLE_MS);
    const scheduler = createScheduler({
      db: singleConnectionDb,
      clock: systemClock,
      logger: quietLogger(),
      jobs: [job],
      enabled: true,
    });
    scheduler.start();
    await sleep(BUSY_SETTLE_MS);
    await scheduler.stop();
    await busy;
    expect(runs).toBe(0);

    const verifier = createDatabase(
      testSettings({ DATABASE_URL: testDatabase.url }).database,
      ignorePoolError,
    );
    const free = await sql<{
      locked: boolean;
    }>`select pg_try_advisory_lock(${LOCK_ID + 1}) as locked`.execute(verifier);
    expect(free.rows[0]?.locked).toBe(true);
    await verifier.destroy();
    await singleConnectionDb.destroy();
  });
});
