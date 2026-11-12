import { describe, expect, it, vi } from 'vitest';
import { z } from 'zod';
import { ROUTES } from '@kodakiko/contract';
import {
  ApiError,
  NetworkError,
  createHttpClient,
  type ApiCall,
  type HttpClient,
} from '../src/adapters/http/http';
import { HTTP_RETRY_AFTER_MAX_MS, HTTP_RETRY_ATTEMPTS } from '../src/constants/http-constants';

const BASE_URL = 'https://journal.example.test';
const SESSION_TOKEN = 'kdk_s_test-token';
const OK_SCHEMA = z.object({ ok: z.boolean() });

type FetchStep = (input: string, init: RequestInit) => Promise<Response>;

function jsonResponse(
  status: number,
  body: unknown,
  headers: Record<string, string> = {},
): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'content-type': 'application/json', ...headers },
  });
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

function buildClient(
  fetcher: typeof fetch,
  overrides: { readonly onSessionInvalid?: () => void; readonly random?: () => number } = {},
) {
  const sleeps: number[] = [];
  const client: HttpClient = createHttpClient({
    baseUrl: async () => BASE_URL,
    sessionToken: async () => SESSION_TOKEN,
    onSessionInvalid: overrides.onSessionInvalid ?? (() => undefined),
    fetcher,
    sleep: async (milliseconds) => {
      sleeps.push(milliseconds);
    },
    random: overrides.random ?? (() => 0.5),
  });
  return { client, sleeps };
}

const getCall: ApiCall<{ ok: boolean }> = {
  method: 'GET',
  path: ROUTES.config(),
  responseSchema: OK_SCHEMA,
  auth: 'session',
  idempotent: true,
};

const postCall: ApiCall<{ ok: boolean }> = {
  method: 'POST',
  path: ROUTES.entries(),
  body: { a: 1 },
  responseSchema: OK_SCHEMA,
  auth: 'session',
  idempotent: false,
};

describe('http client', () => {
  it('sends method, url, bearer header and JSON body', async () => {
    const { fetcher, calls } = sequenceFetch(async () => jsonResponse(200, { ok: true }));
    const { client } = buildClient(fetcher);
    await expect(client.request(postCall)).resolves.toEqual({ ok: true });
    expect(calls).toHaveLength(1);
    expect(calls[0]?.url).toBe(`${BASE_URL}${ROUTES.entries()}`);
    expect(calls[0]?.init.method).toBe('POST');
    expect(new Headers(calls[0]?.init.headers).get('authorization')).toBe(
      `Bearer ${SESSION_TOKEN}`,
    );
    expect(calls[0]?.init.body).toBe(JSON.stringify({ a: 1 }));
  });

  it('sends no authorization header for auth none and the link token header for link auth', async () => {
    const { fetcher, calls } = sequenceFetch(async () => jsonResponse(200, { ok: true }));
    const { client } = buildClient(fetcher);
    await client.request({ ...getCall, auth: 'none' });
    await client.request({ ...getCall, auth: { linkToken: 'link-token' } });
    expect(new Headers(calls[0]?.init.headers).get('authorization')).toBeNull();
    expect(new Headers(calls[1]?.init.headers).get('authorization')).toBeNull();
    expect(new Headers(calls[1]?.init.headers).get('x-kodakiko-link-token')).toBe('link-token');
  });

  it('aborts a request that exceeds its timeout and reports a network error', async () => {
    let seenSignal: AbortSignal | null = null;
    const { fetcher, calls } = sequenceFetch(
      (_url, init) =>
        new Promise<Response>((_resolve, reject) => {
          seenSignal = init.signal ?? null;
          init.signal?.addEventListener('abort', () =>
            reject(new DOMException('aborted', 'AbortError')),
          );
        }),
    );
    const { client } = buildClient(fetcher);
    await expect(client.request({ ...postCall, timeoutMs: 10 })).rejects.toBeInstanceOf(
      NetworkError,
    );
    expect(calls).toHaveLength(1);
    expect((seenSignal as AbortSignal | null)?.aborted).toBe(true);
  });

  it('retries an idempotent GET on 503 and then succeeds', async () => {
    const { fetcher, calls } = sequenceFetch(
      async () => jsonResponse(503, errorBody('STORAGE_UNAVAILABLE')),
      async () => jsonResponse(200, { ok: true }),
    );
    const { client, sleeps } = buildClient(fetcher);
    await expect(client.request(getCall)).resolves.toEqual({ ok: true });
    expect(calls).toHaveLength(2);
    expect(sleeps).toEqual([250]);
  });

  it('retries a failing idempotent GET exactly HTTP_RETRY_ATTEMPTS requests, backing off with full jitter, and reports the last error', async () => {
    const { fetcher, calls } = sequenceFetch(async () =>
      jsonResponse(503, errorBody('STORAGE_UNAVAILABLE')),
    );
    const { client, sleeps } = buildClient(fetcher);
    await expect(client.request(getCall)).rejects.toMatchObject({
      status: 503,
      code: 'STORAGE_UNAVAILABLE',
    });
    expect(calls).toHaveLength(HTTP_RETRY_ATTEMPTS);
    expect(sleeps).toEqual([250, 500]);
  });

  it('retries an idempotent GET after network failures exactly HTTP_RETRY_ATTEMPTS requests and then reports NetworkError', async () => {
    const failing = sequenceFetch(async () => {
      throw new TypeError('network down');
    });
    const { client, sleeps } = buildClient(failing.fetcher);
    await expect(client.request(getCall)).rejects.toBeInstanceOf(NetworkError);
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
    await expect(client.request(getCall)).resolves.toEqual({ ok: true });
  });

  it('does not retry a POST on 503', async () => {
    const { fetcher, calls } = sequenceFetch(async () =>
      jsonResponse(503, errorBody('STORAGE_UNAVAILABLE')),
    );
    const { client, sleeps } = buildClient(fetcher);
    await expect(client.request(postCall)).rejects.toMatchObject({ status: 503 });
    expect(calls).toHaveLength(1);
    expect(sleeps).toEqual([]);
  });

  it('does not retry idempotent calls on other statuses', async () => {
    const { fetcher, calls } = sequenceFetch(async () => jsonResponse(404, errorBody('NOT_FOUND')));
    const { client } = buildClient(fetcher);
    await expect(client.request(getCall)).rejects.toMatchObject({ status: 404, code: 'NOT_FOUND' });
    expect(calls).toHaveLength(1);
  });

  it('waits Retry-After seconds on 429', async () => {
    const { fetcher, calls } = sequenceFetch(
      async () => jsonResponse(429, errorBody('RATE_LIMITED'), { 'retry-after': '2' }),
      async () => jsonResponse(200, { ok: true }),
    );
    const { client, sleeps } = buildClient(fetcher);
    await expect(client.request(getCall)).resolves.toEqual({ ok: true });
    expect(calls).toHaveLength(2);
    expect(sleeps).toEqual([2000]);
  });

  it('caps a huge Retry-After at HTTP_RETRY_AFTER_MAX_MS', async () => {
    const { fetcher } = sequenceFetch(
      async () => jsonResponse(429, errorBody('RATE_LIMITED'), { 'retry-after': '3600' }),
      async () => jsonResponse(200, { ok: true }),
    );
    const { client, sleeps } = buildClient(fetcher);
    await expect(client.request(getCall)).resolves.toEqual({ ok: true });
    expect(sleeps).toEqual([HTTP_RETRY_AFTER_MAX_MS]);
    expect(HTTP_RETRY_AFTER_MAX_MS).toBe(60_000);
  });

  it('turns an error body into an ApiError with status, code, details and request id', async () => {
    const { fetcher } = sequenceFetch(async () =>
      jsonResponse(409, errorBody('REVISION_CONFLICT', 'req-9', { revision: 3 })),
    );
    const { client } = buildClient(fetcher);
    const failure = await client.request(postCall).catch((error: unknown) => error);
    expect(failure).toBeInstanceOf(ApiError);
    expect(failure).toMatchObject({
      status: 409,
      code: 'REVISION_CONFLICT',
      details: { revision: 3 },
      requestId: 'req-9',
    });
  });

  it('maps a non-2xx response without a known error body to ApiError INTERNAL', async () => {
    const { fetcher } = sequenceFetch(async () => new Response('gateway oops', { status: 400 }));
    const { client } = buildClient(fetcher);
    await expect(client.request(postCall)).rejects.toMatchObject({ status: 400, code: 'INTERNAL' });
  });

  it('calls onSessionInvalid on 401 SESSION_EXPIRED and UNAUTHENTICATED but not INVALID_CREDENTIALS', async () => {
    const onSessionInvalid = vi.fn();
    const expired = sequenceFetch(async () => jsonResponse(401, errorBody('SESSION_EXPIRED')));
    await expect(
      buildClient(expired.fetcher, { onSessionInvalid }).client.request(getCall),
    ).rejects.toMatchObject({
      status: 401,
      code: 'SESSION_EXPIRED',
    });
    expect(onSessionInvalid).toHaveBeenCalledTimes(1);

    const unauthenticated = sequenceFetch(async () =>
      jsonResponse(401, errorBody('UNAUTHENTICATED')),
    );
    await expect(
      buildClient(unauthenticated.fetcher, { onSessionInvalid }).client.request(getCall),
    ).rejects.toBeInstanceOf(ApiError);
    expect(onSessionInvalid).toHaveBeenCalledTimes(2);

    const invalid = sequenceFetch(async () => jsonResponse(401, errorBody('INVALID_CREDENTIALS')));
    await expect(
      buildClient(invalid.fetcher, { onSessionInvalid }).client.request(postCall),
    ).rejects.toBeInstanceOf(ApiError);
    expect(onSessionInvalid).toHaveBeenCalledTimes(2);
  });

  it('maps a response body that does not match the schema to ApiError INTERNAL', async () => {
    const { fetcher } = sequenceFetch(async () => jsonResponse(200, { ok: 'nope' }));
    const { client } = buildClient(fetcher);
    await expect(client.request(postCall)).rejects.toMatchObject({ status: 200, code: 'INTERNAL' });
  });

  it('accepts an empty 204 body for a void schema', async () => {
    const { fetcher } = sequenceFetch(async () => new Response(null, { status: 204 }));
    const { client } = buildClient(fetcher);
    await expect(
      client.request({ ...postCall, responseSchema: z.undefined() }),
    ).resolves.toBeUndefined();
  });
});
