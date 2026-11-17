import { describe, expect, it } from 'vitest';
import { z } from 'zod';
import { ROUTES } from '@kodakiko/contract';
import { ApiError, NetworkError, createHttpClient, type ApiCall, type HttpClient } from '../src/http.ts';
import { HTTP_RETRY_AFTER_MAX_MS, HTTP_RETRY_ATTEMPTS, HTTP_TIMEOUT_MS } from '../src/constants.ts';

const BASE_URL = 'https://journal.example.test';
const LINK_ID = 'L'.repeat(22);
const OK_SCHEMA = z.object({ ok: z.boolean() });

type FetchStep = (input: string, init: RequestInit) => Promise<Response>;

function jsonResponse(status: number, body: unknown, headers: Record<string, string> = {}): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json', ...headers } });
}

function errorBody(code: string, requestId = 'req-1', details: unknown = null): unknown {
  return { error: { code, message: 'message', requestId, details } };
}

function sequenceFetch(...steps: readonly FetchStep[]) {
  const calls: { readonly url: string; readonly init: RequestInit }[] = [];
  const fetcher = (async (input: string | URL | Request, init?: RequestInit) => {
    const step = steps[Math.min(calls.length, steps.length - 1)];
    calls.push({ url: String(input), init: init ?? {} });
    if (step === undefined) throw new Error('no step');
    return step(String(input), init ?? {});
  }) as typeof fetch;
  return { fetcher, calls };
}

function buildClient(fetcher: typeof fetch, random: () => number = () => 0.5) {
  const sleeps: number[] = [];
  const client: HttpClient = createHttpClient({
    baseUrl: BASE_URL,
    fetcher,
    sleep: async (milliseconds) => {
      sleeps.push(milliseconds);
    },
    random,
  });
  return { client, sleeps };
}

const entriesCall: ApiCall<{ ok: boolean }> = {
  method: 'GET',
  path: ROUTES.publicLinkEntries(LINK_ID),
  responseSchema: OK_SCHEMA,
  auth: { linkToken: 'link-token', viewToken: 'view-token' },
  idempotent: true,
};

const openCall: ApiCall<{ ok: boolean }> = {
  method: 'POST',
  path: ROUTES.publicLinkOpen(LINK_ID),
  responseSchema: OK_SCHEMA,
  auth: { linkToken: 'link-token' },
  idempotent: false,
};

function header(init: RequestInit | undefined, name: string): string | null {
  return new Headers(init?.headers).get(name);
}

describe('link viewer http client (twin of apps/client http client, link calls only)', () => {
  it('sends method and url with the link token and, when known, the view token; never an authorization header', async () => {
    const { fetcher, calls } = sequenceFetch(async () => jsonResponse(200, { ok: true }));
    const { client } = buildClient(fetcher);
    await expect(client.request(openCall)).resolves.toEqual({ ok: true });
    await client.request(entriesCall);
    await client.request({ ...entriesCall, auth: { linkToken: 'link-token', viewToken: null } });
    expect(calls[0]?.url).toBe(`${BASE_URL}${ROUTES.publicLinkOpen(LINK_ID)}`);
    expect(calls[0]?.init.method).toBe('POST');
    expect(header(calls[0]?.init, 'x-kodakiko-link-token')).toBe('link-token');
    expect(header(calls[0]?.init, 'x-kodakiko-view-token')).toBeNull();
    expect(calls[1]?.url).toBe(`${BASE_URL}${ROUTES.publicLinkEntries(LINK_ID)}`);
    expect(calls[1]?.init.method).toBe('GET');
    expect(header(calls[1]?.init, 'x-kodakiko-link-token')).toBe('link-token');
    expect(header(calls[1]?.init, 'x-kodakiko-view-token')).toBe('view-token');
    expect(header(calls[2]?.init, 'x-kodakiko-view-token')).toBeNull();
    for (const call of calls) {
      expect(header(call.init, 'authorization')).toBeNull();
      expect(call.init.credentials ?? 'omit').not.toBe('include');
    }
  });

  it('sends a JSON body with its content type when the call has one', async () => {
    const { fetcher, calls } = sequenceFetch(async () => jsonResponse(200, { ok: true }));
    const { client } = buildClient(fetcher);
    await client.request({ ...openCall, body: { a: 1 } });
    expect(calls[0]?.init.body).toBe(JSON.stringify({ a: 1 }));
    expect(header(calls[0]?.init, 'content-type')).toBe('application/json');
  });

  it('gives every request an abort signal and a default timeout', async () => {
    const { fetcher, calls } = sequenceFetch(async () => jsonResponse(200, { ok: true }));
    const { client } = buildClient(fetcher);
    await client.request(openCall);
    expect(calls[0]?.init.signal).toBeInstanceOf(AbortSignal);
    expect(HTTP_TIMEOUT_MS).toBe(20_000);
  });

  it('aborts a request that exceeds its timeout and reports a network error', async () => {
    let seenSignal: AbortSignal | null = null;
    const { fetcher, calls } = sequenceFetch(
      (_url, init) =>
        new Promise<Response>((_resolve, reject) => {
          seenSignal = init.signal ?? null;
          init.signal?.addEventListener('abort', () => reject(new DOMException('aborted', 'AbortError')));
        }),
    );
    const { client } = buildClient(fetcher);
    await expect(client.request({ ...openCall, timeoutMs: 10 })).rejects.toBeInstanceOf(NetworkError);
    expect(calls).toHaveLength(1);
    expect((seenSignal as AbortSignal | null)?.aborted).toBe(true);
  });

  it('retries an idempotent GET on 503 and then succeeds', async () => {
    const { fetcher, calls } = sequenceFetch(
      async () => jsonResponse(503, errorBody('STORAGE_UNAVAILABLE')),
      async () => jsonResponse(200, { ok: true }),
    );
    const { client, sleeps } = buildClient(fetcher);
    await expect(client.request(entriesCall)).resolves.toEqual({ ok: true });
    expect(calls).toHaveLength(2);
    expect(sleeps).toEqual([250]);
  });

  it('retries a failing idempotent GET exactly HTTP_RETRY_ATTEMPTS requests with full jitter backoff and reports the last error', async () => {
    const { fetcher, calls } = sequenceFetch(async () => jsonResponse(503, errorBody('STORAGE_UNAVAILABLE')));
    const { client, sleeps } = buildClient(fetcher);
    await expect(client.request(entriesCall)).rejects.toMatchObject({ status: 503, code: 'STORAGE_UNAVAILABLE' });
    expect(HTTP_RETRY_ATTEMPTS).toBe(3);
    expect(calls).toHaveLength(HTTP_RETRY_ATTEMPTS);
    expect(sleeps).toEqual([250, 500]);
  });

  it('retries an idempotent GET after network failures and then reports NetworkError', async () => {
    const failing = sequenceFetch(async () => {
      throw new TypeError('network down');
    });
    const { client, sleeps } = buildClient(failing.fetcher);
    await expect(client.request(entriesCall)).rejects.toBeInstanceOf(NetworkError);
    expect(failing.calls).toHaveLength(HTTP_RETRY_ATTEMPTS);
    expect(sleeps).toEqual([250, 500]);
  });

  it('recovers when a network failure is followed by a success', async () => {
    const recovering = sequenceFetch(
      async () => {
        throw new TypeError('network down');
      },
      async () => jsonResponse(200, { ok: true }),
    );
    const { client } = buildClient(recovering.fetcher);
    await expect(client.request(entriesCall)).resolves.toEqual({ ok: true });
  });

  it('never retries the open call: one view of a one-view link must not be spent twice', async () => {
    const down = sequenceFetch(async () => {
      throw new TypeError('network down');
    });
    const first = buildClient(down.fetcher);
    await expect(first.client.request(openCall)).rejects.toBeInstanceOf(NetworkError);
    expect(down.calls).toHaveLength(1);
    expect(first.sleeps).toEqual([]);
    const unavailable = sequenceFetch(async () => jsonResponse(503, errorBody('STORAGE_UNAVAILABLE')));
    const second = buildClient(unavailable.fetcher);
    await expect(second.client.request(openCall)).rejects.toMatchObject({ status: 503 });
    expect(unavailable.calls).toHaveLength(1);
  });

  it('does not retry the link denials', async () => {
    for (const code of ['LINK_TOKEN_INVALID', 'LINK_EXPIRED', 'LINK_VIEW_EXPIRED']) {
      const { fetcher, calls } = sequenceFetch(async () => jsonResponse(403, errorBody(code)));
      const { client } = buildClient(fetcher);
      await expect(client.request(entriesCall)).rejects.toMatchObject({ status: 403, code });
      expect(calls).toHaveLength(1);
    }
  });

  it('waits Retry-After seconds on 429 and caps a huge Retry-After', async () => {
    const short = sequenceFetch(
      async () => jsonResponse(429, errorBody('RATE_LIMITED'), { 'retry-after': '2' }),
      async () => jsonResponse(200, { ok: true }),
    );
    const first = buildClient(short.fetcher);
    await expect(first.client.request(entriesCall)).resolves.toEqual({ ok: true });
    expect(first.sleeps).toEqual([2000]);
    const huge = sequenceFetch(
      async () => jsonResponse(429, errorBody('RATE_LIMITED'), { 'retry-after': '3600' }),
      async () => jsonResponse(200, { ok: true }),
    );
    const second = buildClient(huge.fetcher);
    await expect(second.client.request(entriesCall)).resolves.toEqual({ ok: true });
    expect(second.sleeps).toEqual([HTTP_RETRY_AFTER_MAX_MS]);
    expect(HTTP_RETRY_AFTER_MAX_MS).toBe(60_000);
  });

  it('turns an error body into an ApiError with status, code, details and request id', async () => {
    const { fetcher } = sequenceFetch(async () => jsonResponse(403, errorBody('LINK_EXPIRED', 'req-9', { why: 1 })));
    const { client } = buildClient(fetcher);
    const failure = await client.request(openCall).catch((error: unknown) => error);
    expect(failure).toBeInstanceOf(ApiError);
    expect(failure).toMatchObject({ status: 403, code: 'LINK_EXPIRED', details: { why: 1 }, requestId: 'req-9' });
  });

  it('maps a non-2xx response without a known error body to ApiError INTERNAL', async () => {
    const { fetcher } = sequenceFetch(async () => new Response('gateway oops', { status: 400 }));
    const { client } = buildClient(fetcher);
    await expect(client.request(openCall)).rejects.toMatchObject({ status: 400, code: 'INTERNAL' });
  });

  it('maps a response body that does not match the schema to ApiError INTERNAL', async () => {
    const { fetcher } = sequenceFetch(async () => jsonResponse(200, { ok: 'nope' }));
    const { client } = buildClient(fetcher);
    await expect(client.request(openCall)).rejects.toMatchObject({ status: 200, code: 'INTERNAL' });
  });
});
