import { afterEach, describe, expect, it } from 'vitest';
import type { FastifyInstance } from 'fastify';
import { ROUTE_PATTERNS } from '@kodakiko/contract';
import { testSettings } from '../integration/helpers.ts';
import { buildUnitApp } from './support.ts';

const SECURE_ENV = {
  REQUIRE_HTTPS: 'true',
  PUBLIC_BASE_URL: 'https://kodakiko.example.com',
  S3_PUBLIC_ENDPOINT: 'https://s3.kodakiko.example.com',
};
let app: FastifyInstance;

afterEach(async () => {
  await app.close();
});

describe('HTTPS requirement', () => {
  it('refuses plain http', async () => {
    app = await buildUnitApp(testSettings(SECURE_ENV));
    const response = await app.inject({ method: 'GET', url: ROUTE_PATTERNS.config });
    expect(response.statusCode).toBe(400);
    expect(response.json().error.code).toBe('HTTPS_REQUIRED');
  });

  it('accepts a request a trusted proxy marked as https', async () => {
    app = await buildUnitApp(testSettings({ ...SECURE_ENV, TRUSTED_PROXIES: 'loopback' }));
    const response = await app.inject({
      method: 'GET',
      url: ROUTE_PATTERNS.config,
      headers: { 'x-forwarded-proto': 'https' },
    });
    expect(response.statusCode).toBe(200);
  });

  it('ignores the forwarded header when no proxy is trusted', async () => {
    app = await buildUnitApp(testSettings({ ...SECURE_ENV, TRUSTED_PROXIES: '' }));
    const response = await app.inject({
      method: 'GET',
      url: ROUTE_PATTERNS.config,
      headers: { 'x-forwarded-proto': 'https' },
    });
    expect(response.statusCode).toBe(400);
  });

  it('ignores the forwarded header from a peer outside the trusted list', async () => {
    app = await buildUnitApp(testSettings({ ...SECURE_ENV, TRUSTED_PROXIES: '203.0.113.0/24' }));
    const response = await app.inject({
      method: 'GET',
      url: ROUTE_PATTERNS.config,
      headers: { 'x-forwarded-proto': 'https' },
    });
    expect(response.statusCode).toBe(400);
  });

  it('exempts the health routes', async () => {
    app = await buildUnitApp(testSettings(SECURE_ENV));
    const response = await app.inject({ method: 'GET', url: ROUTE_PATTERNS.healthLive });
    expect(response.statusCode).toBe(200);
    expect(response.json()).toEqual({ status: 'ok' });
  });
});
