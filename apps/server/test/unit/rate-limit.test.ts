import { afterEach, describe, expect, it } from 'vitest';
import type { FastifyInstance } from 'fastify';
import { createRateLimitFor, ipRateLimitKey } from '../../src/http/rate-limits.ts';
import { RATE_LIMITS } from '../../src/config/constants.ts';
import { testSettings } from '../integration/helpers.ts';
import { buildUnitApp } from './support.ts';

const TEST_LIMIT = 2;
const TEST_WINDOW_MS = 60_000;
const SESSION_TOKEN_BODY_LENGTH = 43;
const SIGN_IN_MAX = 10;

function sessionToken(fill: string): string {
  return `kdk_s_${fill.repeat(SESSION_TOKEN_BODY_LENGTH)}`;
}
let app: FastifyInstance;

async function startApp(): Promise<void> {
  app = await buildUnitApp();
  app.get(
    '/test/limited',
    { config: { rateLimit: { max: TEST_LIMIT, timeWindow: TEST_WINDOW_MS } } },
    async () => ({ ok: true }),
  );
  await app.ready();
}

afterEach(async () => {
  await app.close();
});

describe('rate limiting', () => {
  it('answers 429 with Retry-After on the third call', async () => {
    await startApp();
    const statuses: number[] = [];
    let last = await app.inject({ method: 'GET', url: '/test/limited' });
    statuses.push(last.statusCode);
    last = await app.inject({ method: 'GET', url: '/test/limited' });
    statuses.push(last.statusCode);
    last = await app.inject({ method: 'GET', url: '/test/limited' });
    statuses.push(last.statusCode);
    expect(statuses).toEqual([200, 200, 429]);
    expect(last.headers['retry-after']).toBeDefined();
    expect(last.json().error.code).toBe('RATE_LIMITED');
  });

  it('sends only the remaining and retry-after headers', async () => {
    await startApp();
    const response = await app.inject({ method: 'GET', url: '/test/limited' });
    expect(response.headers['x-ratelimit-remaining']).toBe('1');
    expect(response.headers['x-ratelimit-limit']).toBeUndefined();
    expect(response.headers['x-ratelimit-reset']).toBeUndefined();
  });

  it('keys authenticated requests per token', async () => {
    await startApp();
    const call = (token: string) =>
      app.inject({
        method: 'GET',
        url: '/test/limited',
        headers: { authorization: `Bearer ${token}` },
      });
    await call(sessionToken('a'));
    await call(sessionToken('a'));
    expect((await call(sessionToken('a'))).statusCode).toBe(429);
    expect((await call(sessionToken('b'))).statusCode).toBe(200);
  });

  it('keys a bearer value that is not a session token by IP', async () => {
    await startApp();
    const call = (value: string) =>
      app.inject({
        method: 'GET',
        url: '/test/limited',
        headers: { authorization: `Bearer ${value}` },
      });
    await call('random-1');
    await call('random-2');
    expect((await call('random-3')).statusCode).toBe(429);
    expect((await call(sessionToken('c'))).statusCode).toBe(200);
  });

  it('keys a token with the wrong length by IP', async () => {
    await startApp();
    const call = (value: string) =>
      app.inject({
        method: 'GET',
        url: '/test/limited',
        headers: { authorization: `Bearer ${value}` },
      });
    await call(`kdk_s_${'a'.repeat(SESSION_TOKEN_BODY_LENGTH - 1)}`);
    await call(`kdk_s_${'a'.repeat(SESSION_TOKEN_BODY_LENGTH + 1)}`);
    expect((await call(`kdk_s_${'a'.repeat(SESSION_TOKEN_BODY_LENGTH - 2)}`)).statusCode).toBe(429);
    expect((await call(sessionToken('a'))).statusCode).toBe(200);
  });

  it('shares the IP bucket on sign-in routes whatever the bearer value', async () => {
    app = await buildUnitApp();
    app.get(
      '/test/sign-in',
      { config: createRateLimitFor(testSettings())('authSignIn') },
      async () => ({ ok: true }),
    );
    await app.ready();
    const statuses: number[] = [];
    for (let attempt = 0; attempt <= SIGN_IN_MAX; attempt += 1) {
      const response = await app.inject({
        method: 'GET',
        url: '/test/sign-in',
        headers: { authorization: `Bearer random-${attempt}` },
      });
      statuses.push(response.statusCode);
    }
    expect(statuses.slice(0, SIGN_IN_MAX).every((status) => status === 200)).toBe(true);
    expect(statuses[SIGN_IN_MAX]).toBe(429);
  });

  it('forces the IP key generator on the IP-keyed groups only', () => {
    const rateLimitFor = createRateLimitFor(testSettings());
    for (const group of [
      'authSignIn',
      'authSignUp',
      'publicLinkOpen',
      'publicLinkEntries',
      'publicLinkContent',
    ] as const) {
      expect(rateLimitFor(group)).toEqual({
        rateLimit: {
          max: RATE_LIMITS[group].max,
          timeWindow: RATE_LIMITS[group].timeWindowMs,
          keyGenerator: ipRateLimitKey,
        },
      });
    }
    expect(JSON.stringify(rateLimitFor('entries'))).not.toContain('keyGenerator');
  });

  it('builds route config from the limit table', () => {
    const enabled = createRateLimitFor(testSettings());
    expect(enabled('users')).toEqual({
      rateLimit: { max: RATE_LIMITS.users.max, timeWindow: RATE_LIMITS.users.timeWindowMs },
    });
  });

  it('turns limits off when the setting says so', () => {
    const disabled = createRateLimitFor(testSettings({ RATE_LIMITS_ENABLED: 'false' }));
    expect(disabled('users')).toEqual({ rateLimit: false });
  });
});
