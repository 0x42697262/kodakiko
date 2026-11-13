import type { OutboxKind } from '@kodakiko/contract';
import {
  MAX_ATTEMPTS,
  PUSH_CONCURRENCY,
  SYNC_DEBOUNCE_MS,
  SYNC_INTERVAL_MS,
  classifyPushFailure,
  nextAttemptAt,
  planPushBatch,
  type OutboxItemView,
  type PushFailure,
} from '@kodakiko/core';
import type { Clock } from '../../adapters/clock';
import type { FileStore } from '../../adapters/filestore/filestore';
import { ApiError, NetworkError, type HttpClient } from '../../adapters/http/http';
import type { Lifecycle } from '../../adapters/lifecycle/lifecycle';
import type { Logger } from '../../adapters/logger';
import type { OutboxRow, RowStore } from '../../adapters/rowstore/rowstore';
import type { Uploader } from '../../adapters/uploader/uploader';
import type { AccountStateStore } from '../../composition/account-state';
import type { AppEventBus } from '../../composition/events';
import { PASS_FAILURE_CODE_NETWORK } from '../../constants/sync-constants';
import { SYNC_LOG_EVENTS, UNKNOWN_ERROR_CODE } from '../../keys/sync-keys';
import { AccountLockedError } from '../keys/entry-keys';
import type { ConflictResolver } from './conflict';
import { EXECUTORS } from './executors';
import type { Executor, ExecutorDeps, ExecutorResult } from './executors/executor-types';
import { deleteFiles, removeEntryRows } from './local-delete';
import { entryIdOfPayload, isUploadItem } from './outbox-rows';
import type { Pull } from './pull';
import type { UploadCoordinator } from './uploads';

export type SyncReport = {
  readonly pushed: number;
  readonly pulledEntries: number;
  readonly failed: number;
  readonly durationMs: number;
};

export type SyncStatus = {
  readonly running: boolean;
  readonly queued: number;
  readonly dead: number;
  readonly waitingForWifi: number;
  readonly lastSuccessAt: number | null;
  readonly offline: boolean;
};

export type SyncService = {
  readonly start: () => void;
  readonly stop: () => void;
  readonly syncNow: () => Promise<SyncReport>;
  readonly onPassCompleted: (listener: (report: SyncReport) => void) => () => void;
  readonly notifyLocalWrite: () => void;
  readonly status: () => SyncStatus;
  readonly subscribe: (listener: (status: SyncStatus) => void) => () => void;
  readonly retryDead: () => Promise<void>;
  readonly isEntryInFlight: (entryId: string) => boolean;
};

export type SyncServiceDeps = {
  readonly http: HttpClient;
  readonly rowStore: RowStore;
  readonly fileStore: FileStore;
  readonly uploader: Uploader;
  readonly uploads: UploadCoordinator;
  readonly pull: Pull;
  readonly conflict: ConflictResolver;
  readonly executors?: Readonly<Record<OutboxKind, Executor>>;
  readonly events: AppEventBus;
  readonly clock: Clock;
  readonly random: () => number;
  readonly lifecycle: Lifecycle;
  readonly accountState: AccountStateStore;
  readonly onSessionInvalid: () => void;
  readonly log: Logger;
  readonly localOnly?: () => boolean;
};

type PassTotals = {
  pushed: number;
  failed: number;
  reached: boolean;
  offline: boolean | null;
  halted: boolean;
};

const DROP_REMOVES_ENTRY: ReadonlySet<string> = new Set([
  'updateEntryMeta',
  'trashEntry',
  'restoreEntry',
  'deleteEntry',
]);

function viewOf(row: OutboxRow): OutboxItemView {
  return {
    id: row.id,
    kind: row.kind as OutboxKind,
    entryId: entryIdOfPayload(row.payload),
    state: row.state,
    nextAttemptAt: row.nextAttemptAt,
  };
}

function errorCodeOf(failure: PushFailure): string {
  return failure.kind === 'http' ? failure.code : PASS_FAILURE_CODE_NETWORK;
}

export function createSyncService(deps: SyncServiceDeps): SyncService {
  const { rowStore, uploads, clock } = deps;
  const executors = deps.executors ?? EXECUTORS;
  const executorDeps: ExecutorDeps = {
    http: deps.http,
    rowStore,
    fileStore: deps.fileStore,
    events: deps.events,
    log: deps.log,
    uploads,
    conflict: deps.conflict,
  };
  const statusListeners = new Set<(status: SyncStatus) => void>();
  const passListeners = new Set<(report: SyncReport) => void>();
  const kickedAt = new Map<string, number>();
  let current: SyncStatus = {
    running: false,
    queued: 0,
    dead: 0,
    waitingForWifi: 0,
    lastSuccessAt: null,
    offline: false,
  };
  let started = false;
  let active = true;
  let passInFlight: Promise<SyncReport> | null = null;
  let runAgain = false;
  let debounceTimer: ReturnType<typeof setTimeout> | null = null;
  let intervalTimer: ReturnType<typeof setInterval> | null = null;
  let unsubscribers: Array<() => void> = [];
  let uploaderUnsubscribe: (() => void) | null = null;

  function setStatus(patch: Partial<SyncStatus>): void {
    current = { ...current, ...patch };
    for (const listener of [...statusListeners]) listener(current);
  }

  async function refreshCounts(): Promise<void> {
    const counts = await rowStore.transaction((tx) => tx.outbox.countByState());
    setStatus({ queued: counts.queued, dead: counts.dead, waitingForWifi: uploads.heldBackCount() });
  }

  function reportFailure(error: unknown): void {
    deps.log.warn(SYNC_LOG_EVENTS.passFailed, {
      error: error instanceof Error ? error.constructor.name : typeof error,
    });
  }

  function triggerPass(): void {
    syncNow().catch(reportFailure);
  }

  function schedulePass(): void {
    if (!started) return;
    if (debounceTimer !== null) clearTimeout(debounceTimer);
    debounceTimer = setTimeout(() => {
      debounceTimer = null;
      triggerPass();
    }, SYNC_DEBOUNCE_MS);
  }

  async function makeEntryDue(entryId: string): Promise<void> {
    const now = clock.now();
    kickedAt.set(entryId, now);
    await rowStore.transaction(async (tx) => {
      for (const row of await tx.outbox.all()) {
        if (
          row.state === 'queued' &&
          isUploadItem(row) &&
          entryIdOfPayload(row.payload) === entryId &&
          row.nextAttemptAt > now
        ) {
          await tx.outbox.update({ ...row, nextAttemptAt: now });
        }
      }
    });
  }

  uploads.onObjectUploaded((entryId) => {
    makeEntryDue(entryId).then(schedulePass, reportFailure);
  });

  function ensureListening(): void {
    uploaderUnsubscribe ??= deps.uploader.subscribe((event) => {
      uploads.handleEvent(event).then(
        () => refreshCounts().catch(reportFailure),
        (error: unknown) => {
          deps.log.warn(SYNC_LOG_EVENTS.eventHandlerFailed, {
            error: error instanceof Error ? error.constructor.name : typeof error,
          });
        },
      );
    });
  }

  async function updateRow(id: number, change: (row: OutboxRow) => OutboxRow | null): Promise<void> {
    await rowStore.transaction(async (tx) => {
      const row = (await tx.outbox.all()).find((candidate) => candidate.id === id);
      if (row === undefined) return;
      const next = change(row);
      if (next !== null) await tx.outbox.update(next);
    });
  }

  async function applyRetry(row: OutboxRow, at: number, code: string): Promise<void> {
    await updateRow(row.id, (stored) => ({
      ...stored,
      attempts: row.attempts + 1,
      nextAttemptAt: at,
      lastErrorCode: code,
    }));
  }

  async function applyDead(row: OutboxRow, code: string): Promise<void> {
    await updateRow(row.id, (stored) => ({
      ...stored,
      state: 'dead',
      attempts: row.attempts + 1,
      lastErrorCode: code,
    }));
  }

  async function dropItem(row: OutboxRow): Promise<void> {
    const entryId = entryIdOfPayload(row.payload);
    let objectIds: readonly string[] = [];
    await rowStore.transaction(async (tx) => {
      if (entryId !== null && DROP_REMOVES_ENTRY.has(row.kind)) {
        objectIds = await removeEntryRows(tx, entryId);
      }
      await tx.outbox.delete(row.id);
    });
    await deleteFiles(deps.fileStore, objectIds);
    if (entryId !== null) deps.events.emit('entriesChanged', [entryId]);
  }

  async function applyFailure(
    row: OutboxRow,
    failure: PushFailure,
    totals: PassTotals,
  ): Promise<void> {
    const now = clock.now();
    const attempts = row.attempts + 1;
    const decision = classifyPushFailure(row.kind as OutboxKind, failure, attempts, now, deps.random);
    totals.offline = failure.kind === 'network';
    totals.reached = totals.reached || failure.kind === 'http';
    const code = errorCodeOf(failure);
    switch (decision.action) {
      case 'retry':
        totals.failed += 1;
        await applyRetry(row, decision.at, code);
        break;
      case 'conflict':
        totals.failed += 1;
        await applyRetry(row, nextAttemptAt(attempts, now, deps.random), code);
        break;
      case 'dead':
        totals.failed += 1;
        await applyDead(row, code);
        break;
      case 'drop':
        await dropItem(row);
        break;
      case 'signedOut':
        totals.failed += 1;
        if (!totals.halted) {
          totals.halted = true;
          stop();
          deps.onSessionInvalid();
        }
        break;
    }
  }

  async function applyResult(
    row: OutboxRow,
    result: ExecutorResult,
    startedAt: number,
    totals: PassTotals,
  ): Promise<void> {
    if (result === 'done') {
      totals.pushed += 1;
      totals.reached = true;
      totals.offline = false;
      await rowStore.transaction((tx) => tx.outbox.delete(row.id));
      return;
    }
    if (result === 'waiting') {
      const entryId = entryIdOfPayload(row.payload);
      const kicked = entryId !== null && (kickedAt.get(entryId) ?? 0) >= startedAt;
      if (entryId !== null && kicked) kickedAt.delete(entryId);
      const now = clock.now();
      await updateRow(row.id, (stored) => ({
        ...stored,
        nextAttemptAt: kicked ? now : now + SYNC_INTERVAL_MS,
      }));
      return;
    }
    await applyFailure(row, result.failure, totals);
  }

  async function runItem(row: OutboxRow, totals: PassTotals): Promise<void> {
    const startedAt = clock.now();
    const executor = executors[row.kind as OutboxKind];
    let result: ExecutorResult;
    try {
      result = await executor(row, executorDeps);
    } catch (error) {
      if (error instanceof AccountLockedError) {
        await updateRow(row.id, (stored) => ({
          ...stored,
          nextAttemptAt: clock.now() + SYNC_INTERVAL_MS,
        }));
        return;
      }
      reportFailure(error);
      totals.failed += 1;
      const attempts = row.attempts + 1;
      if (attempts >= MAX_ATTEMPTS) await applyDead(row, UNKNOWN_ERROR_CODE);
      else await applyRetry(row, nextAttemptAt(attempts, clock.now(), deps.random), UNKNOWN_ERROR_CODE);
      return;
    }
    await applyResult(row, result, startedAt, totals);
  }

  const inFlightEntries = new Set<string>();

  async function pushDrain(totals: PassTotals): Promise<void> {
    const running = new Map<number, Promise<void>>();
    for (;;) {
      const rows = await rowStore.transaction((tx) => tx.outbox.all());
      const ids = planPushBatch(
        rows.map(viewOf),
        inFlightEntries,
        clock.now(),
        PUSH_CONCURRENCY - running.size,
      ).filter((id) => !running.has(id));
      for (const row of rows.filter((candidate) => ids.includes(candidate.id))) {
        const entryId = entryIdOfPayload(row.payload);
        if (entryId !== null) inFlightEntries.add(entryId);
        const promise = runItem(row, totals).finally(() => {
          running.delete(row.id);
          if (entryId !== null) inFlightEntries.delete(entryId);
        });
        running.set(row.id, promise);
      }
      if (running.size === 0) return;
      await Promise.race([...running.values()]);
      if (totals.halted) {
        await Promise.all([...running.values()]);
        return;
      }
    }
  }

  async function pullAll(totals: PassTotals): Promise<number> {
    try {
      const result = await deps.pull.run();
      totals.reached = true;
      totals.offline = false;
      return result.pulledEntries;
    } catch (error) {
      totals.failed += 1;
      if (error instanceof NetworkError) totals.offline = true;
      else if (error instanceof ApiError) totals.reached = true;
      else throw error;
      return 0;
    }
  }

  async function onePass(): Promise<SyncReport> {
    const startedAt = clock.now();
    if (deps.accountState.get().kind !== 'unlocked' || deps.localOnly?.() === true) {
      return { pushed: 0, pulledEntries: 0, failed: 0, durationMs: 0 };
    }
    setStatus({ running: true });
    const totals: PassTotals = { pushed: 0, failed: 0, reached: false, offline: null, halted: false };
    let pulledEntries = 0;
    try {
      ensureListening();
      await uploads.reconcileUploads().catch(reportFailure);
      await pushDrain(totals);
      if (!totals.halted) pulledEntries = await pullAll(totals);
    } finally {
      const clean = totals.reached && totals.offline !== true;
      setStatus({
        running: false,
        ...(totals.offline === null ? {} : { offline: totals.offline }),
        ...(clean ? { lastSuccessAt: clock.now() } : {}),
      });
      await refreshCounts().catch(reportFailure);
    }
    const report: SyncReport = {
      pushed: totals.pushed,
      pulledEntries,
      failed: totals.failed,
      durationMs: clock.now() - startedAt,
    };
    if (totals.reached) for (const listener of [...passListeners]) listener(report);
    return report;
  }

  async function passLoop(): Promise<SyncReport> {
    let report: SyncReport;
    do {
      runAgain = false;
      report = await onePass();
    } while (runAgain);
    return report;
  }

  function syncNow(): Promise<SyncReport> {
    if (passInFlight !== null) {
      runAgain = true;
      return passInFlight;
    }
    const pass = passLoop().finally(() => {
      passInFlight = null;
    });
    passInFlight = pass;
    return pass;
  }

  function stopTimers(): void {
    if (debounceTimer !== null) clearTimeout(debounceTimer);
    debounceTimer = null;
    if (intervalTimer !== null) clearInterval(intervalTimer);
    intervalTimer = null;
  }

  function intervalTick(): void {
    if (active) triggerPass();
  }

  function startInterval(): void {
    if (intervalTimer === null) intervalTimer = setInterval(intervalTick, SYNC_INTERVAL_MS);
  }

  function start(): void {
    if (started) return;
    started = true;
    active = true;
    ensureListening();
    unsubscribers = [
      deps.lifecycle.subscribeActive((isActive) => {
        active = isActive;
        if (isActive) {
          startInterval();
          triggerPass();
        } else if (intervalTimer !== null) {
          clearInterval(intervalTimer);
          intervalTimer = null;
        }
      }),
      deps.lifecycle.subscribeOnline(triggerPass),
    ];
    startInterval();
    triggerPass();
  }

  function stop(): void {
    started = false;
    active = false;
    stopTimers();
    for (const unsubscribe of unsubscribers) unsubscribe();
    unsubscribers = [];
    uploaderUnsubscribe?.();
    uploaderUnsubscribe = null;
  }

  async function retryDead(): Promise<void> {
    await rowStore.transaction(async (tx) => {
      const now = clock.now();
      for (const row of await tx.outbox.all()) {
        if (row.state === 'dead') {
          await tx.outbox.update({ ...row, state: 'queued', attempts: 0, nextAttemptAt: now, lastErrorCode: null });
        }
      }
      for (const object of await tx.objects.all()) {
        if (object.uploadState === 'dead') await tx.objects.put({ ...object, uploadState: 'local' });
      }
    });
    await refreshCounts();
    schedulePass();
  }

  function notifyLocalWrite(): void {
    refreshCounts().catch(reportFailure);
    if (debounceTimer !== null) clearTimeout(debounceTimer);
    debounceTimer = setTimeout(() => {
      debounceTimer = null;
      triggerPass();
    }, SYNC_DEBOUNCE_MS);
  }

  return {
    start,
    stop,
    syncNow,
    onPassCompleted: (listener) => {
      passListeners.add(listener);
      return () => {
        passListeners.delete(listener);
      };
    },
    notifyLocalWrite,
    status: () => current,
    subscribe: (listener) => {
      statusListeners.add(listener);
      return () => {
        statusListeners.delete(listener);
      };
    },
    retryDead,
    isEntryInFlight: (entryId) => inFlightEntries.has(entryId),
  };
}
