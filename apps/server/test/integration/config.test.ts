import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import type { FastifyInstance } from 'fastify';
import { publicConfigSchema, ROUTE_PATTERNS } from '@kodakiko/contract';
import { systemClock } from '../../src/platform/clock.ts';
import {
  buildTestApp,
  createPlaceholderObjectStore,
  createTestDatabase,
  testSettings,
  type TestDatabase,
} from './helpers.ts';

let testDatabase: TestDatabase;
let app: FastifyInstance;

beforeAll(async () => {
  testDatabase = await createTestDatabase();
  app = await buildTestApp({
    settings: testSettings({
      SERVER_VERSION: '1.2.3',
      FLAG_SHARING: 'true',
      MAX_MEDIA_PER_ENTRY: '12',
    }),
    db: testDatabase.db,
    clock: systemClock,
    readinessChecks: [],
    objectStore: createPlaceholderObjectStore(),
  });
});

afterAll(async () => {
  await app.close();
  await testDatabase.drop();
});

describe('GET /api/v1/config', () => {
  it('returns exactly the settings-derived public config', async () => {
    const response = await app.inject({ method: 'GET', url: ROUTE_PATTERNS.config });
    expect(response.statusCode).toBe(200);
    expect(publicConfigSchema.parse(response.json())).toEqual({
      apiVersion: 1,
      signupMode: 'invite',
      sharingEnabled: true,
      publicLinksEnabled: false,
      maxMediaPerEntry: 12,
      maxUploadBytes: 3758096384,
      defaultQuotaBytes: 107374182400,
      trashDays: 30,
      accountDeletionGraceDays: 7,
      serverVersion: '1.2.3',
    });
    expect(Object.keys(response.json())).toHaveLength(10);
  });

  it('sets the request id and security headers', async () => {
    const response = await app.inject({ method: 'GET', url: ROUTE_PATTERNS.config });
    expect(response.headers['x-request-id']).toMatch(/^[A-Za-z0-9_-]{22}$/);
    expect(response.headers['cache-control']).toBe('no-store');
    expect(response.headers['content-security-policy']).toBe(
      "default-src 'none';frame-ancestors 'none'",
    );
    expect(response.headers['referrer-policy']).toBe('no-referrer');
    expect(response.headers['strict-transport-security']).toBe(
      'max-age=63072000; includeSubDomains; preload',
    );
    expect(response.headers['cross-origin-resource-policy']).toBe('same-origin');
    expect(response.headers['cross-origin-opener-policy']).toBeUndefined();
    expect(response.headers['cross-origin-embedder-policy']).toBeUndefined();
  });

  it('sets no-store on every /api path and nowhere else', async () => {
    for (const url of ['/api', '/api/v2/x', '/api?x=1']) {
      const response = await app.inject({ method: 'GET', url });
      expect(response.statusCode).toBe(404);
      expect(response.headers['cache-control']).toBe('no-store');
    }
    const outside = await app.inject({ method: 'GET', url: '/apiary' });
    expect(outside.headers['cache-control']).toBeUndefined();
  });

  it('sets no-store on api errors too', async () => {
    const response = await app.inject({ method: 'GET', url: '/api/v1/nothing' });
    expect(response.statusCode).toBe(404);
    expect(response.headers['cache-control']).toBe('no-store');
  });
});
