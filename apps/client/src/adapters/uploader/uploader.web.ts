import { UPLOAD_PART_CONCURRENCY } from '@kodakiko/core';
import { HTTP_STATUS } from '../../constants/http-constants';
import {
  UPLOAD_CONTENT_TYPE,
  UPLOAD_NETWORK_RETRY_DELAYS_MS,
  UPLOAD_PART_TIMEOUT_MS,
} from '../../constants/sync-constants';
import { HTTP_HEADERS } from '../../keys/http-keys';
import type { FileStore } from '../filestore/filestore';
import type {
  UploadEvent,
  UploadFailureReason,
  UploadJob,
  UploadPart,
  UploadStatus,
  Uploader,
  UploaderDeps,
} from './uploader';

export type WebUploaderDeps = {
  readonly fileStore: FileStore;
  readonly fetcher?: typeof fetch;
  readonly sleep?: (ms: number) => Promise<void>;
  readonly timeoutMs?: number;
};

type ObjectRun = {
  readonly job: UploadJob;
  readonly pendingParts: UploadPart[];
  blob: Blob | null;
  started: boolean;
  ready: boolean;
  settled: boolean;
  remaining: number;
  sentBytes: number;
};

type PartOutcome = { readonly ok: true } | { readonly ok: false; readonly reason: UploadFailureReason };

function defaultSleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

export function createWebUploader(deps: WebUploaderDeps): Uploader {
  const fetcher = deps.fetcher ?? ((input, init) => fetch(input, init));
  const sleep = deps.sleep ?? defaultSleep;
  const timeoutMs = deps.timeoutMs ?? UPLOAD_PART_TIMEOUT_MS;
  const listeners = new Set<(event: UploadEvent) => void>();
  const runs = new Map<string, ObjectRun>();
  const statuses = new Map<string, UploadStatus>();
  const controllers = new Set<AbortController>();
  let inFlight = 0;
  let cancelGeneration = 0;

  function emit(event: UploadEvent): void {
    for (const listener of [...listeners]) listener(event);
  }

  function settle(run: ObjectRun, event: UploadEvent, status: UploadStatus): void {
    if (run.settled) return;
    run.settled = true;
    run.pendingParts.length = 0;
    statuses.set(run.job.objectId, status);
    emit(event);
  }

  function fail(run: ObjectRun, reason: UploadFailureReason): void {
    settle(run, { type: 'failed', objectId: run.job.objectId, reason }, 'failed');
  }

  async function putOnce(blob: Blob, part: UploadPart): Promise<Response | null> {
    const controller = new AbortController();
    controllers.add(controller);
    const timer = setTimeout(() => controller.abort(), timeoutMs);
    try {
      return await fetcher(part.url, {
        method: 'PUT',
        body: blob.slice(part.offset, part.offset + part.length),
        headers: { [HTTP_HEADERS.contentType]: UPLOAD_CONTENT_TYPE },
        signal: controller.signal,
      });
    } catch {
      return null;
    } finally {
      clearTimeout(timer);
      controllers.delete(controller);
    }
  }

  async function putPart(blob: Blob, part: UploadPart, generation: number): Promise<PartOutcome> {
    for (let attempt = 0; ; attempt += 1) {
      const response = await putOnce(blob, part);
      if (response !== null) {
        if (response.ok) return { ok: true };
        return { ok: false, reason: response.status === HTTP_STATUS.forbidden ? 'expired' : 'http' };
      }
      const delay = UPLOAD_NETWORK_RETRY_DELAYS_MS[attempt];
      if (delay === undefined || generation !== cancelGeneration) return { ok: false, reason: 'network' };
      await sleep(delay);
    }
  }

  async function startRun(run: ObjectRun): Promise<void> {
    run.started = true;
    statuses.set(run.job.objectId, 'uploading');
    run.blob = await deps.fileStore.readBlob(run.job.objectId).catch(() => null);
    if (run.blob === null) fail(run, 'missingFile');
    run.ready = true;
  }

  async function runPart(run: ObjectRun, part: UploadPart, blob: Blob): Promise<void> {
    const generation = cancelGeneration;
    const outcome = await putPart(blob, part, generation);
    inFlight -= 1;
    if (generation === cancelGeneration && !run.settled && runs.get(run.job.objectId) === run) {
      if (outcome.ok) {
        run.sentBytes += part.length;
        run.remaining -= 1;
        emit({ type: 'progress', objectId: run.job.objectId, sentBytes: run.sentBytes });
        if (run.remaining === 0) settle(run, { type: 'completed', objectId: run.job.objectId }, 'completed');
      } else {
        fail(run, outcome.reason);
      }
    }
    pump();
  }

  function nextRun(): ObjectRun | undefined {
    return [...runs.values()].find(
      (run) => !run.settled && (!run.started || (run.ready && run.pendingParts.length > 0)),
    );
  }

  function pump(): void {
    while (inFlight < UPLOAD_PART_CONCURRENCY) {
      const run = nextRun();
      if (run === undefined) return;
      if (!run.started) {
        startRun(run).then(pump, () => {
          fail(run, 'missingFile');
          run.ready = true;
          pump();
        });
        continue;
      }
      const part = run.pendingParts.shift();
      if (part === undefined || run.blob === null) return;
      inFlight += 1;
      void runPart(run, part, run.blob);
    }
  }

  return {
    enqueue: async (jobs) => {
      for (const job of jobs) {
        runs.set(job.objectId, {
          job,
          pendingParts: [...job.parts],
          blob: null,
          started: false,
          ready: false,
          settled: false,
          remaining: job.parts.length,
          sentBytes: 0,
        });
        statuses.set(job.objectId, 'queued');
      }
      pump();
    },
    statuses: async () => new Map(statuses),
    subscribe: (listener) => {
      listeners.add(listener);
      return () => {
        listeners.delete(listener);
      };
    },
    cancelAll: async () => {
      cancelGeneration += 1;
      for (const controller of controllers) controller.abort();
      runs.clear();
      statuses.clear();
      emit({ type: 'stopped', reason: 'cancelled' });
    },
  };
}

export function createUploader(deps: UploaderDeps): Uploader {
  return createWebUploader(deps);
}
