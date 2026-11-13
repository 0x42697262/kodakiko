import { DownloadSizeMismatchError } from '../../adapters/filestore/download-errors';
import type { FileStore } from '../../adapters/filestore/filestore';
import { ApiError, type HttpClient } from '../../adapters/http/http';
import type { ObjectRow, RowStore } from '../../adapters/rowstore/rowstore';
import {
  DOWNLOAD_PRIORITIES,
  DOWNLOAD_RETRY_DELAYS_MS,
  DOWNLOAD_TIMEOUT_MS,
  HTTP_NOT_FOUND,
  HTTP_SERVER_ERROR_MIN,
  ORIGINAL_FETCH_CONCURRENCY,
  THUMBNAIL_FETCH_CONCURRENCY,
} from './constants';
import { OBJECT_CALLS } from './object-calls';

export type DownloadPriority = (typeof DOWNLOAD_PRIORITIES)[number];
type Lane = 'thumbnail' | 'original';
type ProgressListener = (receivedBytes: number, totalBytes: number) => void;

export interface Downloader {
  readonly fetchObject: (objectId: string, priority: DownloadPriority) => Promise<void>;
  readonly cancelBackground: () => void;
  readonly abortAll: () => void;
  readonly resume: () => void;
  readonly onCompleted: (listener: (objectId: string) => void) => () => void;
  readonly onProgress: (objectId: string, listener: ProgressListener) => () => void;
}

export type DownloaderDeps = {
  readonly http: HttpClient;
  readonly rowStore: RowStore;
  readonly fileStore: FileStore;
  readonly clock: { readonly now: () => number };
  readonly sleep: (ms: number) => Promise<void>;
  readonly isOffline: () => boolean;
};

type Job = {
  readonly row: ObjectRow;
  priority: DownloadPriority;
  readonly controller: AbortController;
  readonly resolve: () => void;
  readonly reject: (error: unknown) => void;
};

export class DownloadCancelledError extends Error {
  constructor() {
    super('Download cancelled.');
  }
}

export class ObjectUnavailableError extends Error {
  constructor() {
    super('Object is no longer available.');
  }
}

const LIMITS: Readonly<Record<Lane, number>> = Object.freeze({
  thumbnail: THUMBNAIL_FETCH_CONCURRENCY,
  original: ORIGINAL_FETCH_CONCURRENCY,
});

function laneOf(row: ObjectRow): Lane {
  return row.variant === 'thumbnail' ? 'thumbnail' : 'original';
}

function retryable(error: unknown): boolean {
  if (error instanceof DownloadSizeMismatchError) return false;
  if (error instanceof ApiError) return error.status >= HTTP_SERVER_ERROR_MIN;
  return true;
}

function rank(priority: DownloadPriority): number {
  return DOWNLOAD_PRIORITIES.indexOf(priority);
}

export function createDownloader(deps: DownloaderDeps): Downloader {
  const queued: Job[] = [];
  const running = new Map<string, Job>();
  const promises = new Map<string, Promise<void>>();
  const active: Record<Lane, number> = { thumbnail: 0, original: 0 };
  const completedListeners = new Set<(objectId: string) => void>();
  const progressListeners = new Map<string, Set<ProgressListener>>();

  async function setState(objectId: string, patch: Partial<ObjectRow>): Promise<void> {
    await deps.rowStore.transaction(async (tx) => {
      const row = await tx.objects.get(objectId);
      if (row !== null) await tx.objects.put({ ...row, ...patch });
    });
  }

  function emitProgress(objectId: string, received: number, total: number): void {
    for (const listener of progressListeners.get(objectId) ?? []) listener(received, total);
  }

  async function attempt(job: Job): Promise<void> {
    const { row, controller } = job;
    const timer = setTimeout(() => controller.abort(), DOWNLOAD_TIMEOUT_MS);
    try {
      await deps.fileStore.downloadObject(
        row.id,
        async () => (await deps.http.request(OBJECT_CALLS.download(row.id))).url,
        row.sizeBytes,
        (bytes) => emitProgress(row.id, bytes, row.sizeBytes),
        controller.signal,
      );
    } finally {
      clearTimeout(timer);
    }
  }

  async function download(job: Job): Promise<void> {
    for (let retry = 0; ; retry += 1) {
      try {
        await attempt(job);
        break;
      } catch (error) {
        if (error instanceof ApiError && error.status === HTTP_NOT_FOUND) {
          await setState(job.row.id, { localState: 'unavailable' });
          throw new ObjectUnavailableError();
        }
        if (job.controller.signal.aborted) throw new DownloadCancelledError();
        const delay = DOWNLOAD_RETRY_DELAYS_MS[retry];
        if (!retryable(error) || delay === undefined) throw error;
        await deps.sleep(delay);
      }
    }
    await setState(job.row.id, { localState: 'present', lastAccessAt: deps.clock.now() });
    for (const listener of completedListeners) listener(job.row.id);
  }

  function pump(): void {
    queued.sort((left, right) => rank(left.priority) - rank(right.priority));
    for (const job of [...queued]) {
      const lane = laneOf(job.row);
      if (active[lane] >= LIMITS[lane]) continue;
      if (job.priority === 'background' && deps.isOffline()) continue;
      queued.splice(queued.indexOf(job), 1);
      active[lane] += 1;
      running.set(job.row.id, job);
      download(job)
        .then(job.resolve, job.reject)
        .finally(() => {
          active[lane] -= 1;
          running.delete(job.row.id);
          promises.delete(job.row.id);
          pump();
        });
    }
  }

  async function fetchObject(objectId: string, priority: DownloadPriority): Promise<void> {
    const existing = promises.get(objectId);
    if (existing !== undefined) {
      const waiting = queued.find((job) => job.row.id === objectId);
      if (waiting !== undefined && rank(priority) < rank(waiting.priority)) {
        waiting.priority = priority;
        pump();
      }
      return existing;
    }
    const row = await deps.rowStore.transaction((tx) => tx.objects.get(objectId));
    if (row === null || row.localState === 'unavailable') throw new ObjectUnavailableError();
    if (row.localState === 'present') return undefined;
    const again = promises.get(objectId);
    if (again !== undefined) return again;
    const promise = new Promise<void>((resolve, reject) => {
      queued.push({ row, priority, controller: new AbortController(), resolve, reject });
    });
    promises.set(objectId, promise);
    pump();
    return promise;
  }

  function cancel(predicate: (job: Job) => boolean): void {
    for (const job of [...queued]) {
      if (!predicate(job)) continue;
      queued.splice(queued.indexOf(job), 1);
      promises.delete(job.row.id);
      job.reject(new DownloadCancelledError());
    }
    for (const job of running.values()) if (predicate(job)) job.controller.abort();
  }

  return {
    fetchObject,
    cancelBackground: () => cancel((job) => job.priority === 'background'),
    abortAll: () => cancel(() => true),
    resume: pump,
    onCompleted: (listener) => {
      completedListeners.add(listener);
      return () => completedListeners.delete(listener);
    },
    onProgress: (objectId, listener) => {
      const listeners = progressListeners.get(objectId) ?? new Set<ProgressListener>();
      listeners.add(listener);
      progressListeners.set(objectId, listeners);
      return () => {
        listeners.delete(listener);
        if (listeners.size === 0) progressListeners.delete(objectId);
      };
    },
  };
}
