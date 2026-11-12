import { describe, expect, it } from 'vitest';
import { z } from 'zod';
import { ApiError, createHttpClient, type ApiCall } from '../src/adapters/http/http';

const OK_SCHEMA = z.object({ ok: z.boolean() });

function errorResponse(status: number, headers: Record<string, string>): Response {
  return new Response(
    JSON.stringify({ error: { code: 'RATE_LIMITED', message: 'slow', requestId: 'r', details: null } }),
    { status, headers: { 'content-type': 'application/json', ...headers } },
  );
}

async function failureOf(response: Response): Promise<ApiError> {
  const client = createHttpClient({
    baseUrl: async () => 'https://x.test',
    sessionToken: async () => null,
    onSessionInvalid: () => undefined,
    fetcher: (async () => response) as typeof fetch,
  });
  const call: ApiCall<z.infer<typeof OK_SCHEMA>> = {
    method: 'POST',
    path: '/api/v1/x',
    responseSchema: OK_SCHEMA,
    auth: 'none',
    idempotent: false,
  };
  const error = await client.request(call).catch((caught: unknown) => caught);
  if (!(error instanceof ApiError)) throw new Error('expected ApiError');
  return error;
}

describe('ApiError retryAfterSeconds', () => {
  it('carries the Retry-After header as seconds', async () => {
    expect((await failureOf(errorResponse(429, { 'Retry-After': '17' }))).retryAfterSeconds).toBe(17);
  });

  it('is null when the header is absent or not a number', async () => {
    expect((await failureOf(errorResponse(429, {}))).retryAfterSeconds).toBeNull();
    expect((await failureOf(errorResponse(429, { 'Retry-After': 'soon' }))).retryAfterSeconds).toBeNull();
  });
});
