import type { FastifyBaseLogger } from 'fastify';
import { sql, type Kysely } from 'kysely';
import type { Database } from '../adapters/db/schema.ts';
import { JOB_TICK_MS } from '../config/constants.ts';
import type { Clock } from '../platform/clock.ts';
import { LOG_EVENTS } from '../text/events.ts';
import { SYSTEM_TEXT } from '../text/system.ts';

export type JobContext = {
  readonly db: Kysely<Database>;
  readonly now: Date;
  readonly signal: AbortSignal;
};
export type JobResult = { readonly processed: number; readonly remaining: boolean };
export type JobDefinition = {
  readonly name: string;
  readonly lockId: number;
  readonly intervalMs: number;
  readonly timeoutMs: number;
  readonly run: (context: JobContext) => Promise<JobResult>;
};
export type ReleaseLock = () => Promise<void>;
export type TryLock = (lockId: number) => Promise<ReleaseLock | null>;
export type SchedulerOptions = {
  readonly db: Kysely<Database>;
  readonly clock: Clock;
  readonly logger: FastifyBaseLogger;
  readonly jobs: readonly JobDefinition[];
  readonly enabled: boolean;
  readonly tryLock?: TryLock;
};

type JobState = { lastRunAt: number; drain: boolean };
type Deferred<Value> = {
  readonly promise: Promise<Value>;
  readonly resolve: (value: Value) => void;
  readonly reject: (reason: unknown) => void;
};

function createDeferred<Value>(): Deferred<Value> {
  let resolve!: (value: Value) => void;
  let reject!: (reason: unknown) => void;
  const promise = new Promise<Value>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise;
    reject = rejectPromise;
  });
  return { promise, resolve, reject };
}

function advisoryLock(db: Kysely<Database>): TryLock {
  return async (lockId) => {
    const acquired = createDeferred<ReleaseLock | null>();
    const unlockRequested = createDeferred<void>();
    const connectionDone: Promise<void> = db
      .connection()
      .execute(async (connection) => {
        const result = await sql<{
          locked: boolean;
        }>`select pg_try_advisory_lock(${lockId}) as locked`.execute(connection);
        if (result.rows[0]?.locked !== true) {
          acquired.resolve(null);
          return;
        }
        acquired.resolve(async () => {
          unlockRequested.resolve();
          await connectionDone;
        });
        try {
          await unlockRequested.promise;
        } finally {
          await sql`select pg_advisory_unlock(${lockId})`.execute(connection);
        }
      })
      .catch((error: unknown) => {
        acquired.reject(error);
        throw error;
      });
    connectionDone.catch(() => undefined);
    return acquired.promise;
  };
}

export function createScheduler(options: SchedulerOptions): {
  start(): void;
  stop(): Promise<void>;
} {
  const { db, clock, logger, jobs, enabled } = options;
  const tryLock = options.tryLock ?? advisoryLock(db);
  const states = new Map<string, JobState>();
  const running = new Set<AbortController>();
  let activeTick: Promise<void> | undefined;
  let ticking = false;
  let stopped = true;
  let firstTick: NodeJS.Timeout | undefined;
  let interval: NodeJS.Timeout | undefined;

  function isDue(job: JobDefinition): boolean {
    const state = states.get(job.name);
    if (state === undefined || state.drain) return true;
    return state.lastRunAt + job.intervalMs <= clock.now().getTime();
  }

  async function runLocked(job: JobDefinition, release: ReleaseLock): Promise<void> {
    const controller = new AbortController();
    running.add(controller);
    const timer = setTimeout(
      () => controller.abort(new Error(SYSTEM_TEXT.jobTimedOut)),
      job.timeoutMs,
    );
    const startedAt = clock.now();
    states.set(job.name, { lastRunAt: startedAt.getTime(), drain: false });
    logger.info({ event: LOG_EVENTS.jobStarted, job: job.name });
    try {
      const result = await job.run({ db, now: startedAt, signal: controller.signal });
      states.set(job.name, { lastRunAt: startedAt.getTime(), drain: result.remaining });
      logger.info({
        event: LOG_EVENTS.jobFinished,
        job: job.name,
        processed: result.processed,
        remaining: result.remaining,
        durationMs: clock.now().getTime() - startedAt.getTime(),
      });
    } catch (error) {
      logger.error({ event: LOG_EVENTS.jobFailed, job: job.name, err: error });
    } finally {
      clearTimeout(timer);
      running.delete(controller);
      await release().catch((error: unknown) => {
        logger.error({ event: LOG_EVENTS.jobFailed, job: job.name, err: error });
      });
    }
  }

  async function runJob(job: JobDefinition): Promise<void> {
    try {
      const release = await tryLock(job.lockId);
      if (release === null) {
        states.set(job.name, { lastRunAt: clock.now().getTime(), drain: false });
        return;
      }
      if (stopped) {
        await release();
        return;
      }
      await runLocked(job, release);
    } catch (error) {
      states.set(job.name, { lastRunAt: clock.now().getTime(), drain: false });
      logger.error({ event: LOG_EVENTS.jobFailed, job: job.name, err: error });
    }
  }

  async function tick(): Promise<void> {
    if (ticking || stopped) return;
    ticking = true;
    try {
      for (const job of jobs) {
        if (stopped) break;
        if (isDue(job)) await runJob(job);
      }
    } finally {
      ticking = false;
    }
  }

  function startTick(): void {
    if (ticking || stopped) return;
    activeTick = tick();
  }

  return {
    start() {
      if (!enabled || !stopped) return;
      stopped = false;
      firstTick = setTimeout(startTick, 0);
      interval = setInterval(startTick, JOB_TICK_MS);
    },
    async stop() {
      stopped = true;
      clearTimeout(firstTick);
      clearInterval(interval);
      for (const controller of running) controller.abort();
      await activeTick;
    },
  };
}
