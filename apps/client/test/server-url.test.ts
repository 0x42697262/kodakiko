import { describe, expect, it } from 'vitest';
import { ROUTES, type PublicConfigDto } from '@kodakiko/contract';
import { createServerUrlStore } from '../src/adapters/server-url/server-url';

const VALID_CONFIG: PublicConfigDto = {
  apiVersion: 1,
  signupMode: 'invite',
  sharingEnabled: false,
  publicLinksEnabled: false,
  maxMediaPerEntry: 20,
  maxUploadBytes: 1024,
  defaultQuotaBytes: 0,
  trashDays: 30,
  accountDeletionGraceDays: 14,
  serverVersion: '0.1.0',
};
const INVITE_TOKEN = 'kdk_i_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA';

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'content-type': 'application/json' },
  });
}

function buildStore(
  handler: (url: string, init: RequestInit) => Promise<Response>,
  allowLocalHttp = false,
  timeoutMs = 1000,
) {
  const requested: string[] = [];
  let stored: string | null = null;
  const fetcher = (async (input: string | URL | Request, init?: RequestInit) => {
    requested.push(String(input));
    return handler(String(input), init ?? {});
  }) as typeof fetch;
  const store = createServerUrlStore({
    fetcher,
    timeoutMs,
    allowLocalHttp,
    storage: {
      get: async () => stored,
      set: async (value) => {
        stored = value;
      },
    },
  });
  return { store, requested, stored: () => stored };
}

const okHandler = async () => jsonResponse(200, VALID_CONFIG);

describe('server url store', () => {
  it('prefixes a bare host with https and stores the origin', async () => {
    const { store, requested, stored } = buildStore(okHandler);
    await expect(store.choose('example.com')).resolves.toEqual({
      ok: true,
      baseUrl: 'https://example.com',
      config: VALID_CONFIG,
      inviteToken: null,
    });
    expect(requested).toEqual([`https://example.com${ROUTES.config()}`]);
    expect(stored()).toBe('https://example.com');
    await expect(store.current()).resolves.toBe('https://example.com');
  });

  it('keeps host, port and drops the path', async () => {
    const { store } = buildStore(okHandler);
    const choice = await store.choose('https://example.com:8443/some/path');
    expect(choice).toMatchObject({
      ok: true,
      baseUrl: 'https://example.com:8443',
      inviteToken: null,
    });
  });

  it('refuses http without any request', async () => {
    const { store, requested } = buildStore(okHandler);
    await expect(store.choose('http://x')).resolves.toEqual({ ok: false, reason: 'not_https' });
    expect(requested).toEqual([]);
  });

  it('extracts the invite token from an invite url', async () => {
    const { store, requested } = buildStore(okHandler);
    const choice = await store.choose(`https://example.com/invite#${INVITE_TOKEN}`);
    expect(choice).toMatchObject({
      ok: true,
      baseUrl: 'https://example.com',
      inviteToken: INVITE_TOKEN,
    });
    expect(requested).toEqual([`https://example.com${ROUTES.config()}`]);
  });

  it('ignores a fragment on a path that is not /invite', async () => {
    const { store } = buildStore(okHandler);
    const choice = await store.choose(`https://example.com/other#${INVITE_TOKEN}`);
    expect(choice).toMatchObject({ ok: true, inviteToken: null });
  });

  it('reports not_kodakiko for a config with the wrong shape or a non-2xx answer', async () => {
    const wrongShape = buildStore(async () => jsonResponse(200, { hello: 'world' }));
    await expect(wrongShape.store.choose('example.com')).resolves.toEqual({
      ok: false,
      reason: 'not_kodakiko',
    });
    const notFound = buildStore(async () => new Response('nope', { status: 404 }));
    await expect(notFound.store.choose('example.com')).resolves.toEqual({
      ok: false,
      reason: 'not_kodakiko',
    });
    const notJson = buildStore(async () => new Response('<html>', { status: 200 }));
    await expect(notJson.store.choose('example.com')).resolves.toEqual({
      ok: false,
      reason: 'not_kodakiko',
    });
    expect(wrongShape.stored()).toBeNull();
  });

  it('reports unreachable on a network failure and on timeout, storing nothing', async () => {
    const failing = buildStore(async () => {
      throw new TypeError('network down');
    });
    await expect(failing.store.choose('example.com')).resolves.toEqual({
      ok: false,
      reason: 'unreachable',
    });
    expect(failing.stored()).toBeNull();

    const hanging = buildStore(
      (_url, init) =>
        new Promise<Response>((_resolve, reject) => {
          init.signal?.addEventListener('abort', () =>
            reject(new DOMException('aborted', 'AbortError')),
          );
        }),
      false,
      10,
    );
    await expect(hanging.store.choose('example.com')).resolves.toEqual({
      ok: false,
      reason: 'unreachable',
    });
  });

  it('reports invalid for empty input and unsupported schemes', async () => {
    const { store, requested } = buildStore(okHandler);
    await expect(store.choose('   ')).resolves.toEqual({ ok: false, reason: 'invalid' });
    await expect(store.choose('ftp://example.com')).resolves.toEqual({
      ok: false,
      reason: 'invalid',
    });
    await expect(store.choose('https://')).resolves.toEqual({ ok: false, reason: 'invalid' });
    expect(requested).toEqual([]);
  });

  it('accepts local http addresses only when allowLocalHttp is true', async () => {
    const allowed = buildStore(okHandler, true);
    await expect(allowed.store.choose('http://localhost:9580')).resolves.toMatchObject({
      ok: true,
      baseUrl: 'http://localhost:9580',
    });
    await expect(allowed.store.choose('http://127.0.0.1:9580')).resolves.toMatchObject({
      ok: true,
      baseUrl: 'http://127.0.0.1:9580',
    });
    await expect(allowed.store.choose('http://10.0.2.2:9580')).resolves.toMatchObject({
      ok: true,
      baseUrl: 'http://10.0.2.2:9580',
    });
    await expect(allowed.store.choose('http://example.com')).resolves.toEqual({
      ok: false,
      reason: 'not_https',
    });

    const refused = buildStore(okHandler, false);
    await expect(refused.store.choose('http://localhost:9580')).resolves.toEqual({
      ok: false,
      reason: 'not_https',
    });
    await expect(refused.store.choose('http://127.0.0.1:9580')).resolves.toEqual({
      ok: false,
      reason: 'not_https',
    });
    await expect(refused.store.choose('http://10.0.2.2:9580')).resolves.toEqual({
      ok: false,
      reason: 'not_https',
    });
    expect(refused.requested).toEqual([]);
  });

  it('returns null from current before anything was chosen', async () => {
    const { store } = buildStore(okHandler);
    await expect(store.current()).resolves.toBeNull();
  });
});
