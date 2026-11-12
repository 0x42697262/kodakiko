import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import Fastify from 'fastify';
import { createDatabase } from '../../src/adapters/db/database.ts';
import { JOB_TICK_MS } from '../../src/config/constants.ts';
import {
  createScheduler,
  type JobContext,
  type JobDefinition,
  type JobResult,
} from '../../src/jobs/scheduler.ts';
import { systemClock } from '../../src/platform/clock.ts';
import { LOG_EVENTS } from '../../src/text/events.ts';
import { ignorePoolError, testSettings } from '../integration/helpers.ts';

const INTERVAL_MS = 60_000;
const TIMEOUT_MS = 120_000;
const LOCK_ID = 1;
const DONE: JobResult = { processed: 1, remaining: false };

type LogLine = Record<string, unknown>;

function collectingLogger() {
  const lines: LogLine[] = [];
  const app = Fastify({
    logger: {
      level: 'info',
      stream: { write: (line: string) => lines.push(JSON.parse(line) as LogLine) },
    },
  });
  return { logger: app.log, lines };
}

function eventsOf(lines: LogLine[], event: string): LogLine[] {
  return lines.filter((line) => line['event'] === event);
}

function makeJob(run: (context: JobContext) => Promise<JobResult>): JobDefinition {
  return { name: 'test-job', lockId: LOCK_ID, intervalMs: INTERVAL_MS, timeoutMs: TIMEOUT_MS, run };
}

function setup(
  job: JobDefinition,
  tryLock?: (lockId: number) => Promise<(() => Promise<void>) | null>,
) {
  const { logger, lines } = collectingLogger();
  const db = createDatabase(testSettings().database, ignorePoolError);
  const release = vi.fn(async () => undefined);
  const lock = tryLock ?? vi.fn(async () => release);
  const scheduler = createScheduler({
    db,
    clock: systemClock,
    logger,
    jobs: [job],
    enabled: true,
    tryLock: lock,
  });
  return { scheduler, lines, release, lock };
}

beforeEach(() => {
  vi.useFakeTimers();
});

afterEach(() => {
  vi.useRealTimers();
});

describe('scheduler', () => {
  it('runs a job on the first tick and again when its interval has passed', async () => {
    const run = vi.fn(async () => DONE);
    const { scheduler, lines } = setup(makeJob(run));
    scheduler.start();
    await vi.advanceTimersByTimeAsync(0);
    expect(run).toHaveBeenCalledTimes(1);
    await vi.advanceTimersByTimeAsync(INTERVAL_MS - JOB_TICK_MS);
    expect(run).toHaveBeenCalledTimes(1);
    await vi.advanceTimersByTimeAsync(JOB_TICK_MS);
    expect(run).toHaveBeenCalledTimes(2);
    expect(eventsOf(lines, LOG_EVENTS.jobStarted)).toHaveLength(2);
    expect(eventsOf(lines, LOG_EVENTS.jobFinished)).toHaveLength(2);
    await scheduler.stop();
  });

  it('logs processed and remaining when a job finishes', async () => {
    const run = vi.fn(async () => ({ processed: 7, remaining: false }));
    const { scheduler, lines } = setup(makeJob(run));
    scheduler.start();
    await vi.advanceTimersByTimeAsync(0);
    const finished = eventsOf(lines, LOG_EVENTS.jobFinished)[0];
    expect(finished?.['processed']).toBe(7);
    expect(finished?.['remaining']).toBe(false);
    expect(finished?.['job']).toBe('test-job');
    await scheduler.stop();
  });

  it('does not run a job twice at the same time', async () => {
    let finish: (result: JobResult) => void = () => undefined;
    const run = vi.fn(
      () =>
        new Promise<JobResult>((resolve) => {
          finish = resolve;
        }),
    );
    const { scheduler } = setup({ ...makeJob(run), intervalMs: 1000 });
    scheduler.start();
    await vi.advanceTimersByTimeAsync(JOB_TICK_MS * 3);
    expect(run).toHaveBeenCalledTimes(1);
    finish(DONE);
    await vi.advanceTimersByTimeAsync(0);
    await scheduler.stop();
  });

  it('runs a job with remaining work again on the next tick', async () => {
    const run = vi
      .fn<(context: JobContext) => Promise<JobResult>>()
      .mockResolvedValueOnce({ processed: 5, remaining: true })
      .mockResolvedValue(DONE);
    const { scheduler } = setup(makeJob(run));
    scheduler.start();
    await vi.advanceTimersByTimeAsync(0);
    expect(run).toHaveBeenCalledTimes(1);
    await vi.advanceTimersByTimeAsync(JOB_TICK_MS);
    expect(run).toHaveBeenCalledTimes(2);
    await vi.advanceTimersByTimeAsync(JOB_TICK_MS);
    expect(run).toHaveBeenCalledTimes(2);
    await scheduler.stop();
  });

  it('logs a failure, keeps running and retries at the next interval', async () => {
    const run = vi
      .fn<(context: JobContext) => Promise<JobResult>>()
      .mockRejectedValueOnce(new Error('boom'))
      .mockResolvedValue(DONE);
    const { scheduler, lines } = setup(makeJob(run));
    scheduler.start();
    await vi.advanceTimersByTimeAsync(0);
    const failed = eventsOf(lines, LOG_EVENTS.jobFailed);
    expect(failed).toHaveLength(1);
    expect(JSON.stringify(failed[0])).toContain('boom');
    await vi.advanceTimersByTimeAsync(INTERVAL_MS - JOB_TICK_MS);
    expect(run).toHaveBeenCalledTimes(1);
    await vi.advanceTimersByTimeAsync(JOB_TICK_MS);
    expect(run).toHaveBeenCalledTimes(2);
    expect(eventsOf(lines, LOG_EVENTS.jobFinished)).toHaveLength(1);
    await scheduler.stop();
  });

  it('releases the lock after every run, also after a failure', async () => {
    const run = vi.fn().mockRejectedValue(new Error('boom'));
    const { scheduler, release } = setup(makeJob(run));
    scheduler.start();
    await vi.advanceTimersByTimeAsync(0);
    expect(release).toHaveBeenCalledTimes(1);
    await scheduler.stop();
  });

  it('skips a job whose lock another instance holds until the next interval', async () => {
    const run = vi.fn(async () => DONE);
    const lock = vi.fn(async () => null);
    const { scheduler } = setup(makeJob(run), lock);
    scheduler.start();
    await vi.advanceTimersByTimeAsync(0);
    expect(lock).toHaveBeenCalledTimes(1);
    await vi.advanceTimersByTimeAsync(INTERVAL_MS - JOB_TICK_MS);
    expect(lock).toHaveBeenCalledTimes(1);
    await vi.advanceTimersByTimeAsync(JOB_TICK_MS);
    expect(lock).toHaveBeenCalledTimes(2);
    expect(run).not.toHaveBeenCalled();
    await scheduler.stop();
  });

  it('releases a lock that resolves after stop and never starts the job', async () => {
    const run = vi.fn(async () => DONE);
    const release = vi.fn(async () => undefined);
    let grant: (value: (() => Promise<void>) | null) => void = () => undefined;
    const lock = vi.fn(
      () =>
        new Promise<(() => Promise<void>) | null>((resolve) => {
          grant = resolve;
        }),
    );
    const { scheduler } = setup(makeJob(run), lock);
    scheduler.start();
    await vi.advanceTimersByTimeAsync(0);
    expect(lock).toHaveBeenCalledTimes(1);
    const stopping = scheduler.stop();
    grant(release);
    await stopping;
    expect(release).toHaveBeenCalledTimes(1);
    expect(run).not.toHaveBeenCalled();
  });

  it('aborts running jobs on stop and waits for them', async () => {
    let seenSignal: AbortSignal | undefined;
    const run = vi.fn(
      (context: JobContext) =>
        new Promise<JobResult>((resolve) => {
          seenSignal = context.signal;
          context.signal.addEventListener('abort', () => resolve(DONE));
        }),
    );
    const { scheduler, release } = setup(makeJob(run));
    scheduler.start();
    await vi.advanceTimersByTimeAsync(0);
    expect(seenSignal?.aborted).toBe(false);
    await scheduler.stop();
    expect(seenSignal?.aborted).toBe(true);
    expect(release).toHaveBeenCalledTimes(1);
    await vi.advanceTimersByTimeAsync(INTERVAL_MS * 2);
    expect(run).toHaveBeenCalledTimes(1);
  });

  it('aborts a job that exceeds its timeout', async () => {
    let seenSignal: AbortSignal | undefined;
    const run = vi.fn(
      (context: JobContext) =>
        new Promise<JobResult>((resolve) => {
          seenSignal = context.signal;
          context.signal.addEventListener('abort', () => resolve(DONE));
        }),
    );
    const { scheduler } = setup({ ...makeJob(run), timeoutMs: 1000 });
    scheduler.start();
    await vi.advanceTimersByTimeAsync(0);
    expect(seenSignal?.aborted).toBe(false);
    await vi.advanceTimersByTimeAsync(1000);
    expect(seenSignal?.aborted).toBe(true);
    await scheduler.stop();
  });

  it('does nothing when disabled', async () => {
    const run = vi.fn(async () => DONE);
    const { logger } = collectingLogger();
    const scheduler = createScheduler({
      db: createDatabase(testSettings().database, ignorePoolError),
      clock: systemClock,
      logger,
      jobs: [makeJob(run)],
      enabled: false,
    });
    scheduler.start();
    await vi.advanceTimersByTimeAsync(JOB_TICK_MS * 2);
    expect(run).not.toHaveBeenCalled();
    await scheduler.stop();
  });
});
