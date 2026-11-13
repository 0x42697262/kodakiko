import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { OutboxKind } from '@kodakiko/contract';
import {
  MAX_ATTEMPTS,
  PUSH_CONCURRENCY,
  SYNC_DEBOUNCE_MS,
  SYNC_INTERVAL_MS,
  nextAttemptAt,
  type Sodium,
} from '@kodakiko/core';
import { NetworkError } from '../src/adapters/http/http';
import { AccountLockedError } from '../src/services/keys/entry-keys';
import type { Lifecycle } from '../src/adapters/lifecycle/lifecycle';
import type { NewOutboxRow, OutboxRow } from '../src/adapters/rowstore/rowstore';
import { createAccountState } from '../src/composition/account-state';
import { createEventBus, type AppEvents } from '../src/composition/events';
import type { Executor, ExecutorResult } from '../src/services/sync/executors/executor-types';
import { createSyncService, type SyncService } from '../src/services/sync/sync-service';
import type { SyncReport } from '../src/services/sync/sync-service';
import type { UploadCoordinator } from '../src/services/sync/uploads';
import {
  createAccountFixture,
  createLocalEntry,
  createMemoryFileStore,
  createMemoryRowStore,
  createNodeSodium,
  type AccountFixture,
} from './support';
import { createFakeHttp } from './support/fake-http';
import { createFakeUploader, type FakeUploader } from './support/fake-uploader';

let sodium: Sodium;
let fixture: AccountFixture;
let now: number;
let service: SyncService;
let uploader: FakeUploader;
let handler: (item: OutboxRow) => Promise<ExecutorResult> | ExecutorResult;
let calls: number[];
let pullResults: Array<{ pulledEntries: number } | Error>;
let pullCalls: number;
let localOnly: boolean;
let invalid: number;
let accountState: ReturnType<typeof createAccountState>;
let lifecycle: Lifecycle & { fireActive: (active: boolean) => void; fireOnline: () => void; activeUnsubscribed: () => boolean };
let uploadedListeners: Array<(entryId: string) => void>;
let handledEvents: unknown[];
let heldBack: number;

const KEYS = { masterKey: new Uint8Array(32), privateKey: new Uint8Array(32), publicKey: new Uint8Array(32) };

function row(kind: OutboxKind, entryId: string, overrides: Partial<NewOutboxRow> = {}): NewOutboxRow {
  return { kind, payload: { entryId }, state: 'queued', createdAt: 0, attempts: 0, nextAttemptAt: 0, lastErrorCode: null, ...overrides };
}

async function enqueue(...rows: NewOutboxRow[]): Promise<number[]> {
  return fixture.rowStore.transaction(async (tx) => {
    const ids: number[] = [];
    for (const item of rows) ids.push(await tx.outbox.enqueue(item));
    return ids;
  });
}

async function outbox() {
  return fixture.rowStore.transaction((tx) => tx.outbox.all());
}

function executorMap(): Record<OutboxKind, Executor> {
  const run: Executor = async (item) => {
    calls.push(item.id);
    return handler(item);
  };
  return { createEntry: run, updateEntryMeta: run, addMedia: run, removeMedia: run, trashEntry: run, restoreEntry: run, deleteEntry: run };
}

beforeEach(async () => {
  sodium = await createNodeSodium();
  fixture = await createAccountFixture(sodium, createMemoryRowStore(), createMemoryFileStore());
  now = 1_000_000;
  calls = [];
  handler = () => 'done';
  pullResults = [];
  pullCalls = 0;
  localOnly = false;
  invalid = 0;
  uploadedListeners = [];
  handledEvents = [];
  heldBack = 0;
  uploader = createFakeUploader();
  accountState = createAccountState({ kind: 'unlocked', keys: KEYS, userId: 'u', username: 'n' });
  let activeListener: ((active: boolean) => void) | null = null;
  let onlineListener: (() => void) | null = null;
  let activeUnsub = false;
  lifecycle = {
    afterInteractions: async () => undefined,
    subscribeActive: (listener) => {
      activeListener = listener;
      return () => {
        activeUnsub = true;
      };
    },
    subscribeOnline: (listener) => {
      onlineListener = listener;
      return () => {
        onlineListener = null;
      };
    },
    fireActive: (active) => activeListener?.(active),
    fireOnline: () => onlineListener?.(),
    activeUnsubscribed: () => activeUnsub,
  };
  const uploads: UploadCoordinator = {
    enqueueObjectUploads: async () => undefined,
    reconcileUploads: async () => undefined,
    handleEvent: async (event) => {
      handledEvents.push(event);
    },
    heldBackCount: () => heldBack,
    onObjectUploaded: (listener) => {
      uploadedListeners.push(listener);
      return () => undefined;
    },
  };
  service = createSyncService({
    http: createFakeHttp().http,
    rowStore: fixture.rowStore,
    fileStore: fixture.fileStore,
    uploader,
    uploads,
    pull: {
      run: async () => {
        pullCalls += 1;
        const next = pullResults.shift() ?? { pulledEntries: 0 };
        if (next instanceof Error) throw next;
        return next;
      },
    },
    conflict: { resolve: async () => 'adopted' },
    executors: executorMap(),
    events: createEventBus<AppEvents>(),
    clock: { now: () => now, nowIso: () => new Date(now).toISOString() },
    random: () => 0.5,
    lifecycle,
    accountState,
    onSessionInvalid: () => {
      invalid += 1;
    },
    log: { warn: () => undefined },
    localOnly: () => localOnly,
  });
});

afterEach(() => {
  service.stop();
  vi.useRealTimers();
});

describe('local-only mode (spec 20a)', () => {
  it('a pass pushes and pulls nothing and keeps the outbox', async () => {
    localOnly = true;
    await enqueue(row('trashEntry', fixture.randomId()));
    const report = await service.syncNow();
    expect(report.pushed).toBe(0);
    expect(calls).toEqual([]);
    expect(pullCalls).toBe(0);
    expect(await outbox()).toHaveLength(1);
  });
});

describe('push drain', () => {
  it('runs due items in id order and deletes finished ones', async () => {
    const entries = [fixture.randomId(), fixture.randomId(), fixture.randomId()];
    const ids = await enqueue(...entries.map((entryId) => row('trashEntry', entryId)));
    const report = await service.syncNow();
    expect(calls).toEqual(ids);
    expect(await outbox()).toEqual([]);
    expect(report.pushed).toBe(3);
    expect(report.failed).toBe(0);
  });

  it('runs same-entry items strictly one after another', async () => {
    const entryId = fixture.randomId();
    const [first, second] = await enqueue(row('createEntry', entryId), row('updateEntryMeta', entryId));
    let secondStarted = false;
    handler = async (item) => {
      if (item.id === first) await Promise.resolve();
      if (item.id === second) secondStarted = true;
      else expect(secondStarted).toBe(false);
      return 'done' as const;
    };
    await service.syncNow();
    expect(calls).toEqual([first, second]);
  });

  it('reports an entry as in flight only while one of its items runs', async () => {
    const entryId = fixture.randomId();
    await enqueue(row('trashEntry', entryId));
    const seen: boolean[] = [];
    handler = () => {
      seen.push(service.isEntryInFlight(entryId), service.isEntryInFlight(fixture.randomId()));
      return 'done' as const;
    };
    expect(service.isEntryInFlight(entryId)).toBe(false);
    await service.syncNow();
    expect(seen).toEqual([true, false]);
    expect(service.isEntryInFlight(entryId)).toBe(false);
  });

  it('keeps later same-entry items blocked while the earlier one fails and waits', async () => {
    const entryId = fixture.randomId();
    await enqueue(row('createEntry', entryId), row('updateEntryMeta', entryId));
    handler = () => ({ failure: { kind: 'network' } });
    await service.syncNow();
    expect(calls).toHaveLength(1);
  });

  it('never runs more than PUSH_CONCURRENCY items at once', async () => {
    await enqueue(...Array.from({ length: 9 }, () => row('trashEntry', fixture.randomId())));
    let active = 0;
    let peak = 0;
    handler = async () => {
      active += 1;
      peak = Math.max(peak, active);
      await new Promise((resolve) => setTimeout(resolve, 3));
      active -= 1;
      return 'done' as const;
    };
    await service.syncNow();
    expect(peak).toBe(PUSH_CONCURRENCY);
    expect(calls).toHaveLength(9);
  });

  it('keeps a waiting item queued without counting an attempt, due after SYNC_INTERVAL_MS', async () => {
    const entryId = fixture.randomId();
    const [id] = await enqueue(row('createEntry', entryId));
    handler = () => 'waiting';
    await service.syncNow();
    const [item] = await outbox();
    expect(item).toMatchObject({ id, attempts: 0, state: 'queued', nextAttemptAt: now + SYNC_INTERVAL_MS });
    expect(calls).toHaveLength(1);
  });

  it('makes a waiting item due at once when one of its uploads was confirmed meanwhile', async () => {
    const entryId = fixture.randomId();
    await enqueue(row('createEntry', entryId));
    let kicked = false;
    handler = async () => {
      if (!kicked) for (const listener of uploadedListeners) listener(entryId);
      kicked = true;
      return 'waiting' as const;
    };
    await service.syncNow();
    expect(calls.length).toBeGreaterThanOrEqual(2);
    const [item] = await outbox();
    expect(item?.nextAttemptAt).toBe(now + SYNC_INTERVAL_MS);
  });

  it('retries a network failure with backoff, counts the attempt and flags offline', async () => {
    const [id] = await enqueue(row('createEntry', fixture.randomId()));
    handler = () => ({ failure: { kind: 'network' } });
    pullResults = [new NetworkError()];
    const report = await service.syncNow();
    const [item] = await outbox();
    expect(item).toMatchObject({ id, attempts: 1, state: 'queued', lastErrorCode: 'NETWORK' });
    expect(item?.nextAttemptAt).toBe(nextAttemptAt(1, now, () => 0.5));
    expect(service.status().offline).toBe(true);
    expect(report.failed).toBe(2);
  });

  it('clears offline after a success', async () => {
    await enqueue(row('createEntry', fixture.randomId()));
    handler = () => ({ failure: { kind: 'network' } });
    await service.syncNow();
    handler = () => 'done';
    now += 10_000_000;
    await service.syncNow();
    expect(service.status().offline).toBe(false);
  });

  it('moves an item to dead once it fails MAX_ATTEMPTS times', async () => {
    const [id] = await enqueue(row('createEntry', fixture.randomId(), { attempts: MAX_ATTEMPTS - 1 }));
    handler = () => ({ failure: { kind: 'network' } });
    await service.syncNow();
    const [item] = await outbox();
    expect(item).toMatchObject({ id, state: 'dead' });
    expect(service.status().dead).toBe(1);
  });

  it('dead-letters a quota error at once, keeps its code and keeps draining others', async () => {
    const [bad] = await enqueue(row('createEntry', fixture.randomId()), row('trashEntry', fixture.randomId()));
    handler = (item) =>
      item.id === bad
        ? { failure: { kind: 'http', status: 403, code: 'QUOTA_EXCEEDED', retryAfterSeconds: null } }
        : 'done';
    await service.syncNow();
    const items = await outbox();
    expect(items).toHaveLength(1);
    expect(items[0]).toMatchObject({ id: bad, state: 'dead', lastErrorCode: 'QUOTA_EXCEEDED' });
    expect(service.status()).toMatchObject({ dead: 1, queued: 0 });
  });

  it('honors Retry-After on a 429', async () => {
    await enqueue(row('createEntry', fixture.randomId()));
    handler = () => ({ failure: { kind: 'http', status: 429, code: 'RATE_LIMITED', retryAfterSeconds: 30 } });
    await service.syncNow();
    expect((await outbox())[0]?.nextAttemptAt).toBe(now + 30_000);
  });

  it('drops a 404 on deleteEntry and removes the entry locally', async () => {
    const local = await createLocalEntry(fixture, { mediaBytes: [Uint8Array.of(1)], note: '', tags: [], enqueue: false });
    await enqueue(row('deleteEntry', local.entryId));
    handler = () => ({ failure: { kind: 'http', status: 404, code: 'NOT_FOUND', retryAfterSeconds: null } });
    await service.syncNow();
    expect(await outbox()).toEqual([]);
    expect(await fixture.rowStore.transaction((tx) => tx.entries.get(local.entryId))).toBeNull();
    expect(await fixture.fileStore.size(local.originalObjectIds[0] ?? '')).toBeNull();
  });

  it('retries with backoff when the executor could not resolve a revision conflict', async () => {
    await enqueue(row('updateEntryMeta', fixture.randomId()));
    handler = () => ({ failure: { kind: 'http', status: 409, code: 'REVISION_CONFLICT', retryAfterSeconds: null } });
    await service.syncNow();
    const [item] = await outbox();
    expect(item).toMatchObject({ attempts: 1, state: 'queued' });
    expect(item?.nextAttemptAt).toBeGreaterThan(now - 1);
  });

  it('drops a 404 on an update and removes the entry locally', async () => {
    const local = await createLocalEntry(fixture, { mediaBytes: [Uint8Array.of(1)], note: '', tags: [], enqueue: false });
    await enqueue(row('updateEntryMeta', local.entryId));
    handler = () => ({ failure: { kind: 'http', status: 404, code: 'NOT_FOUND', retryAfterSeconds: null } });
    await service.syncNow();
    expect(await outbox()).toEqual([]);
    expect(await fixture.rowStore.transaction((tx) => tx.entries.get(local.entryId))).toBeNull();
    expect(await fixture.fileStore.size(local.originalObjectIds[0] ?? '')).toBeNull();
  });

  it('dead-letters a 404 on createEntry', async () => {
    await enqueue(row('createEntry', fixture.randomId()));
    handler = () => ({ failure: { kind: 'http', status: 404, code: 'NOT_FOUND', retryAfterSeconds: null } });
    await service.syncNow();
    expect((await outbox())[0]?.state).toBe('dead');
  });

  it('stops and signals an invalid session on 401', async () => {
    await enqueue(row('createEntry', fixture.randomId()), row('trashEntry', fixture.randomId()));
    handler = () => ({ failure: { kind: 'http', status: 401, code: 'UNAUTHENTICATED', retryAfterSeconds: null } });
    await service.syncNow();
    expect(invalid).toBe(1);
    expect(pullCalls).toBe(0);
  });

  it('treats an unexpected executor error as a failed attempt instead of crashing the pass', async () => {
    const [id] = await enqueue(row('createEntry', fixture.randomId()));
    handler = () => {
      throw new TypeError('bug');
    };
    const report = await service.syncNow();
    expect(report.failed).toBe(1);
    expect((await outbox())[0]).toMatchObject({ id, attempts: 1, lastErrorCode: 'UNKNOWN' });
  });

  it('dead-letters an item whose executor keeps throwing unexpected errors', async () => {
    const [id] = await enqueue(row('createEntry', fixture.randomId(), { attempts: MAX_ATTEMPTS - 1 }));
    handler = () => {
      throw new TypeError('bug');
    };
    await service.syncNow();
    expect((await outbox())[0]).toMatchObject({ id, state: 'dead', lastErrorCode: 'UNKNOWN' });
    expect(service.status().dead).toBe(1);
  });

  it('does not count an attempt when the account is locked mid-push', async () => {
    const [id] = await enqueue(row('createEntry', fixture.randomId(), { attempts: MAX_ATTEMPTS - 1 }));
    handler = () => {
      throw new AccountLockedError();
    };
    await service.syncNow();
    expect(calls).toEqual([id]);
    expect((await outbox())[0]).toMatchObject({
      id,
      state: 'queued',
      attempts: MAX_ATTEMPTS - 1,
      nextAttemptAt: now + SYNC_INTERVAL_MS,
    });
  });

  it('does not run items that are not due yet', async () => {
    await enqueue(row('createEntry', fixture.randomId(), { nextAttemptAt: now + 1 }));
    await service.syncNow();
    expect(calls).toEqual([]);
  });
});

describe('pass', () => {
  it('pulls after pushing and reports pulled entries', async () => {
    pullResults = [{ pulledEntries: 4 }];
    const report = await service.syncNow();
    expect(pullCalls).toBe(1);
    expect(report.pulledEntries).toBe(4);
    expect(report.durationMs).toBeGreaterThanOrEqual(0);
  });

  it('records lastSuccessAt after a pass that reached the server', async () => {
    await service.syncNow();
    expect(service.status().lastSuccessAt).toBe(now);
  });

  it('flags offline and counts a failure when the pull cannot reach the server', async () => {
    pullResults = [new NetworkError()];
    const report = await service.syncNow();
    expect(service.status().offline).toBe(true);
    expect(report.failed).toBeGreaterThan(0);
    expect(service.status().lastSuccessAt).toBeNull();
  });

  it('coalesces a trigger during a pass into one more pass', async () => {
    let release: () => void = () => undefined;
    const gate = new Promise<void>((resolve) => {
      release = resolve;
    });
    handler = async () => {
      await gate;
      return 'done' as const;
    };
    await enqueue(row('trashEntry', fixture.randomId()));
    const first = service.syncNow();
    const second = service.syncNow();
    release();
    await Promise.all([first, second]);
    expect(pullCalls).toBe(2);
  });

  it('notifies pass listeners only for passes that reached the server', async () => {
    const reports: SyncReport[] = [];
    service.onPassCompleted((report) => reports.push(report));
    pullResults = [new NetworkError()];
    await service.syncNow();
    expect(reports).toHaveLength(0);
    await service.syncNow();
    expect(reports).toHaveLength(1);
  });

  it('does nothing while the account is not unlocked', async () => {
    accountState.set({ kind: 'locked' });
    await enqueue(row('trashEntry', fixture.randomId()));
    const report = await service.syncNow();
    expect(calls).toEqual([]);
    expect(pullCalls).toBe(0);
    expect(report).toMatchObject({ pushed: 0, pulledEntries: 0, failed: 0 });
  });

  it('exposes running and notifies status subscribers', async () => {
    const seen: boolean[] = [];
    service.subscribe((status) => seen.push(status.running));
    await service.syncNow();
    expect(seen).toContain(true);
    expect(seen.at(-1)).toBe(false);
  });

  it('reports held-back uploads as waitingForWifi', async () => {
    heldBack = 2;
    await service.syncNow();
    expect(service.status().waitingForWifi).toBe(2);
  });
});

describe('retryDead', () => {
  it('resets dead items to queued with zero attempts and dead objects to local', async () => {
    const local = await createLocalEntry(fixture, { mediaBytes: [Uint8Array.of(1)], note: '', tags: [], enqueue: false });
    await enqueue(row('createEntry', local.entryId, { state: 'dead', attempts: 8, lastErrorCode: 'QUOTA_EXCEEDED' }));
    await fixture.rowStore.transaction(async (tx) => {
      for (const object of await tx.objects.all()) await tx.objects.put({ ...object, uploadState: 'dead' });
    });
    await service.retryDead();
    const [item] = await outbox();
    expect(item).toMatchObject({ state: 'queued', attempts: 0, lastErrorCode: null });
    const objects = await fixture.rowStore.transaction((tx) => tx.objects.all());
    expect(objects.every((object) => object.uploadState === 'local')).toBe(true);
    expect(service.status()).toMatchObject({ dead: 0, queued: 1 });
  });
});

describe('triggers', () => {
  it('start runs one pass at once and routes uploader events to the coordinator', async () => {
    service.start();
    await vi.waitFor(() => expect(pullCalls).toBe(1));
    uploader.emit({ type: 'completed', objectId: 'x' });
    await vi.waitFor(() => expect(handledEvents).toEqual([{ type: 'completed', objectId: 'x' }]));
  });

  it('runs a pass when the app becomes active or the network comes back', async () => {
    service.start();
    await vi.waitFor(() => expect(pullCalls).toBe(1));
    lifecycle.fireActive(true);
    await vi.waitFor(() => expect(pullCalls).toBe(2));
    lifecycle.fireOnline();
    await vi.waitFor(() => expect(pullCalls).toBe(3));
    lifecycle.fireActive(false);
    await new Promise((resolve) => setTimeout(resolve, 5));
    expect(pullCalls).toBe(3);
  });

  it('runs a pass every SYNC_INTERVAL_MS while active', async () => {
    vi.useFakeTimers();
    service.start();
    await vi.advanceTimersByTimeAsync(0);
    const before = pullCalls;
    await vi.advanceTimersByTimeAsync(SYNC_INTERVAL_MS);
    expect(pullCalls).toBe(before + 1);
    lifecycle.fireActive(false);
    await vi.advanceTimersByTimeAsync(SYNC_INTERVAL_MS * 2);
    expect(pullCalls).toBe(before + 1);
  });

  it('debounces local writes into one pass after SYNC_DEBOUNCE_MS', async () => {
    vi.useFakeTimers();
    service.notifyLocalWrite();
    service.notifyLocalWrite();
    await vi.advanceTimersByTimeAsync(SYNC_DEBOUNCE_MS - 1);
    expect(pullCalls).toBe(0);
    await vi.advanceTimersByTimeAsync(1);
    expect(pullCalls).toBe(1);
  });

  it('stop unsubscribes from lifecycle and cancels timers', async () => {
    vi.useFakeTimers();
    service.start();
    await vi.advanceTimersByTimeAsync(0);
    service.stop();
    const before = pullCalls;
    await vi.advanceTimersByTimeAsync(SYNC_INTERVAL_MS * 3);
    expect(pullCalls).toBe(before);
    expect(lifecycle.activeUnsubscribed()).toBe(true);
  });
});
