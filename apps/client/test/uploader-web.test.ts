import { describe, expect, it } from 'vitest';
import { UPLOAD_PART_CONCURRENCY } from '@kodakiko/core';
import { createWebUploader } from '../src/adapters/uploader/uploader.web';
import type { UploadEvent, UploadJob } from '../src/adapters/uploader/uploader';
import { UPLOAD_NETWORK_RETRY_DELAYS_MS, UPLOAD_CONTENT_TYPE } from '../src/constants/sync-constants';
import { createMemoryFileStore } from './support';

const OBJECT_A = 'AAAAAAAAAAAAAAAAAAAAAA';
const OBJECT_B = 'BBBBBBBBBBBBBBBBBBBBBB';
const URL_BASE = 'https://bucket.test/upload';

type PutCall = { readonly url: string; readonly body: Uint8Array; readonly headers: Headers };

async function setup(options: {
  readonly respond?: (call: PutCall, attempt: number) => Response | Error | Promise<Response | Error>;
  readonly timeoutMs?: number;
} = {}) {
  const fileStore = createMemoryFileStore();
  await fileStore.open('acct');
  const calls: PutCall[] = [];
  const sleeps: number[] = [];
  const attempts = new Map<string, number>();
  let active = 0;
  let maxActive = 0;
  const fetcher = (async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    const body = new Uint8Array(await (init?.body as Blob).arrayBuffer());
    const call: PutCall = { url, body, headers: new Headers(init?.headers) };
    calls.push(call);
    active += 1;
    maxActive = Math.max(maxActive, active);
    const attempt = (attempts.get(url) ?? 0) + 1;
    attempts.set(url, attempt);
    try {
      await Promise.resolve();
      const aborted = new Promise<never>((_, reject) => {
        init?.signal?.addEventListener('abort', () => reject(new DOMException('aborted', 'AbortError')));
      });
      const answer = Promise.resolve(options.respond?.(call, attempt) ?? new Response(null, { status: 200 }));
      const result = await Promise.race([answer, aborted]);
      if (result instanceof Error) throw result;
      return result;
    } finally {
      active -= 1;
    }
  }) as typeof fetch;
  const uploader = createWebUploader({
    fileStore,
    fetcher,
    sleep: async (ms) => {
      sleeps.push(ms);
    },
    ...(options.timeoutMs === undefined ? {} : { timeoutMs: options.timeoutMs }),
  });
  const events: UploadEvent[] = [];
  uploader.subscribe((event) => events.push(event));
  async function store(objectId: string, bytes: Uint8Array) {
    await fileStore.writeObject(
      objectId,
      (async function* () {
        yield bytes;
      })(),
    );
  }
  async function settled(count: number) {
    for (let step = 0; step < 200 && events.filter((event) => event.type !== 'progress').length < count; step += 1) {
      await new Promise((resolve) => setTimeout(resolve, 2));
    }
  }
  return { uploader, calls, events, sleeps, store, settled, maxActive: () => maxActive };
}

function singleJob(objectId: string, size: number): UploadJob {
  return { objectId, parts: [{ partNumber: 0, url: `${URL_BASE}/${objectId}`, offset: 0, length: size }] };
}

describe('web uploader', () => {
  it('PUTs a single part with the right body and headers, then reports completion', async () => {
    const harness = await setup();
    const bytes = Uint8Array.from({ length: 50 }, (_, index) => index);
    await harness.store(OBJECT_A, bytes);
    await harness.uploader.enqueue([singleJob(OBJECT_A, 50)]);
    await harness.settled(1);
    expect(harness.calls).toHaveLength(1);
    expect([...(harness.calls[0]?.body ?? [])]).toEqual([...bytes]);
    expect(harness.calls[0]?.headers.get('Content-Type')).toBe(UPLOAD_CONTENT_TYPE);
    expect(harness.events.at(-1)).toEqual({ type: 'completed', objectId: OBJECT_A });
    expect((await harness.uploader.statuses()).get(OBJECT_A)).toBe('completed');
  });

  it('slices multipart parts by offset and length and completes after the last part', async () => {
    const harness = await setup();
    const bytes = Uint8Array.from({ length: 25 }, (_, index) => index);
    await harness.store(OBJECT_A, bytes);
    await harness.uploader.enqueue([
      {
        objectId: OBJECT_A,
        parts: [
          { partNumber: 1, url: `${URL_BASE}/1`, offset: 0, length: 10 },
          { partNumber: 2, url: `${URL_BASE}/2`, offset: 10, length: 10 },
          { partNumber: 3, url: `${URL_BASE}/3`, offset: 20, length: 5 },
        ],
      },
    ]);
    await harness.settled(1);
    const byUrl = new Map(harness.calls.map((call) => [call.url, [...call.body]]));
    expect(byUrl.get(`${URL_BASE}/1`)).toEqual([...bytes.slice(0, 10)]);
    expect(byUrl.get(`${URL_BASE}/2`)).toEqual([...bytes.slice(10, 20)]);
    expect(byUrl.get(`${URL_BASE}/3`)).toEqual([...bytes.slice(20)]);
    expect(harness.events.filter((event) => event.type === 'completed')).toHaveLength(1);
    const progress = harness.events.filter((event) => event.type === 'progress');
    expect(progress.at(-1)).toEqual({ type: 'progress', objectId: OBJECT_A, sentBytes: 25 });
  });

  it('never runs more than UPLOAD_PART_CONCURRENCY parts at once', async () => {
    const harness = await setup({
      respond: async () => {
        await new Promise((resolve) => setTimeout(resolve, 5));
        return new Response(null, { status: 200 });
      },
    });
    await harness.store(OBJECT_A, new Uint8Array(40));
    const parts = Array.from({ length: 8 }, (_, index) => ({
      partNumber: index + 1,
      url: `${URL_BASE}/${index + 1}`,
      offset: index * 5,
      length: 5,
    }));
    await harness.uploader.enqueue([{ objectId: OBJECT_A, parts }]);
    await harness.settled(1);
    expect(harness.calls).toHaveLength(8);
    expect(harness.maxActive()).toBeLessThanOrEqual(UPLOAD_PART_CONCURRENCY);
    expect(harness.maxActive()).toBeGreaterThan(1);
  });

  it('reports failed expired on 403 and does not retry', async () => {
    const harness = await setup({ respond: () => new Response(null, { status: 403 }) });
    await harness.store(OBJECT_A, new Uint8Array(10));
    await harness.uploader.enqueue([singleJob(OBJECT_A, 10)]);
    await harness.settled(1);
    expect(harness.events.at(-1)).toEqual({ type: 'failed', objectId: OBJECT_A, reason: 'expired' });
    expect(harness.calls).toHaveLength(1);
    expect((await harness.uploader.statuses()).get(OBJECT_A)).toBe('failed');
  });

  it('reports failed http on other non-2xx statuses', async () => {
    const harness = await setup({ respond: () => new Response(null, { status: 500 }) });
    await harness.store(OBJECT_A, new Uint8Array(10));
    await harness.uploader.enqueue([singleJob(OBJECT_A, 10)]);
    await harness.settled(1);
    expect(harness.events.at(-1)).toEqual({ type: 'failed', objectId: OBJECT_A, reason: 'http' });
  });

  it('retries network errors three times with backoff, then reports failed network', async () => {
    const harness = await setup({ respond: () => new TypeError('offline') });
    await harness.store(OBJECT_A, new Uint8Array(10));
    await harness.uploader.enqueue([singleJob(OBJECT_A, 10)]);
    await harness.settled(1);
    expect(harness.calls).toHaveLength(UPLOAD_NETWORK_RETRY_DELAYS_MS.length + 1);
    expect(harness.sleeps).toEqual([...UPLOAD_NETWORK_RETRY_DELAYS_MS]);
    expect(harness.events.at(-1)).toEqual({ type: 'failed', objectId: OBJECT_A, reason: 'network' });
  });

  it('succeeds when a retry gets through', async () => {
    const harness = await setup({ respond: (_call, attempt) => (attempt < 3 ? new TypeError('offline') : new Response(null, { status: 200 })) });
    await harness.store(OBJECT_A, new Uint8Array(10));
    await harness.uploader.enqueue([singleJob(OBJECT_A, 10)]);
    await harness.settled(1);
    expect(harness.events.at(-1)).toEqual({ type: 'completed', objectId: OBJECT_A });
    expect(harness.calls).toHaveLength(3);
  });

  it('aborts a part that exceeds the timeout and treats it as a network error', async () => {
    const harness = await setup({
      timeoutMs: 5,
      respond: () => new Promise<Response>(() => undefined),
    });
    await harness.store(OBJECT_A, new Uint8Array(10));
    await harness.uploader.enqueue([singleJob(OBJECT_A, 10)]);
    await harness.settled(1);
    expect(harness.events.at(-1)).toEqual({ type: 'failed', objectId: OBJECT_A, reason: 'network' });
  });

  it('reports failed missingFile when the object is not in the FileStore', async () => {
    const harness = await setup();
    await harness.uploader.enqueue([singleJob(OBJECT_A, 10)]);
    await harness.settled(1);
    expect(harness.events.at(-1)).toEqual({ type: 'failed', objectId: OBJECT_A, reason: 'missingFile' });
    expect(harness.calls).toHaveLength(0);
  });

  it('uploads several objects and reports each', async () => {
    const harness = await setup();
    await harness.store(OBJECT_A, new Uint8Array(10));
    await harness.store(OBJECT_B, new Uint8Array(12));
    await harness.uploader.enqueue([singleJob(OBJECT_A, 10), singleJob(OBJECT_B, 12)]);
    await harness.settled(2);
    const completed = harness.events.filter((event) => event.type === 'completed');
    expect(completed.map((event) => (event as { objectId: string }).objectId).sort()).toEqual([OBJECT_A, OBJECT_B]);
  });

  it('stops unsubscribed listeners from receiving events', async () => {
    const harness = await setup();
    const extra: UploadEvent[] = [];
    const unsubscribe = harness.uploader.subscribe((event) => extra.push(event));
    unsubscribe();
    await harness.store(OBJECT_A, new Uint8Array(10));
    await harness.uploader.enqueue([singleJob(OBJECT_A, 10)]);
    await harness.settled(1);
    expect(extra).toEqual([]);
  });

  it('cancelAll aborts pending work, clears statuses and emits stopped cancelled', async () => {
    const harness = await setup({ respond: () => new Promise<Response>(() => undefined) });
    await harness.store(OBJECT_A, new Uint8Array(10));
    await harness.uploader.enqueue([singleJob(OBJECT_A, 10)]);
    await harness.uploader.cancelAll();
    expect(harness.events.at(-1)).toEqual({ type: 'stopped', reason: 'cancelled' });
    expect((await harness.uploader.statuses()).size).toBe(0);
  });

  it('lets a re-enqueued object replace its earlier failed run', async () => {
    let fail = true;
    const harness = await setup({ respond: () => (fail ? new Response(null, { status: 403 }) : new Response(null, { status: 200 })) });
    await harness.store(OBJECT_A, new Uint8Array(10));
    await harness.uploader.enqueue([singleJob(OBJECT_A, 10)]);
    await harness.settled(1);
    fail = false;
    await harness.uploader.enqueue([singleJob(OBJECT_A, 10)]);
    await harness.settled(2);
    expect(harness.events.at(-1)).toEqual({ type: 'completed', objectId: OBJECT_A });
    expect((await harness.uploader.statuses()).get(OBJECT_A)).toBe('completed');
  });
});
