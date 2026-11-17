import { describe, expect, it } from 'vitest';
import { z } from 'zod';
import { ROUTES } from '@kodakiko/contract';
import { NetworkError, createHttpClient, type ApiCall } from '../src/http.ts';

const BASE_URL = 'https://journal.example.test';
const LINK_ID = 'L'.repeat(22);

const openCall: ApiCall<{ ok: boolean }> = {
  method: 'POST',
  path: ROUTES.publicLinkOpen(LINK_ID),
  responseSchema: z.object({ ok: z.boolean() }),
  auth: { linkToken: 'link-token', viewToken: 'view-token' },
  idempotent: false,
};

function client(fetcher: typeof fetch) {
  return createHttpClient({ baseUrl: BASE_URL, fetcher, sleep: async () => undefined, random: () => 0.5 });
}

describe('link viewer http redirects', () => {
  it('asks fetch to refuse redirects so the X-Link-* headers never follow one', async () => {
    const inits: RequestInit[] = [];
    const fetcher = (async (_input: string | URL | Request, init?: RequestInit) => {
      inits.push(init ?? {});
      return new Response(JSON.stringify({ ok: true }), { status: 200 });
    }) as typeof fetch;
    await client(fetcher).request(openCall);
    expect(inits).toHaveLength(1);
    expect(inits[0]?.redirect).toBe('error');
  });

  it('reports a refused redirect (fetch TypeError) as a NetworkError', async () => {
    const fetcher = (async () => {
      throw new TypeError('Failed to fetch');
    }) as typeof fetch;
    await expect(client(fetcher).request(openCall)).rejects.toBeInstanceOf(NetworkError);
  });

  it('reports a redirect response that reaches the client as a NetworkError, not an ApiError', async () => {
    const fetcher = (async () =>
      new Response(null, { status: 302, headers: { Location: 'https://evil.example.test/' } })) as typeof fetch;
    await expect(client(fetcher).request(openCall)).rejects.toBeInstanceOf(NetworkError);
  });
});
