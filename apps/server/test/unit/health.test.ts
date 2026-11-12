import { afterEach, describe, expect, it } from 'vitest';
import type { FastifyInstance } from 'fastify';
import { ROUTE_PATTERNS } from '@kodakiko/contract';
import type { ReadinessCheck } from '../../src/services/readiness.ts';
import { testSettings } from '../integration/helpers.ts';
import { buildUnitApp } from './support.ts';

let app: FastifyInstance;

const passing: ReadinessCheck = { name: 'alpha', check: async () => undefined };
const failing: ReadinessCheck = {
  name: 'beta',
  check: async () => {
    throw new Error('down');
  },
};

afterEach(async () => {
  await app.close();
});

describe('health routes', () => {
  it('is ready when every check passes', async () => {
    app = await buildUnitApp(testSettings(), [passing]);
    const response = await app.inject({ method: 'GET', url: ROUTE_PATTERNS.healthReady });
    expect(response.statusCode).toBe(200);
    expect(response.json()).toEqual({ status: 'ok' });
  });

  it('names the failed checks with 503', async () => {
    app = await buildUnitApp(testSettings(), [passing, failing]);
    const response = await app.inject({ method: 'GET', url: ROUTE_PATTERNS.healthReady });
    expect(response.statusCode).toBe(503);
    expect(response.json()).toEqual({ status: 'unavailable', failed: ['beta'] });
  });

  it('is never rate limited', async () => {
    app = await buildUnitApp(testSettings({}), [passing]);
    for (let call = 0; call < 200; call += 1) {
      const response = await app.inject({ method: 'GET', url: ROUTE_PATTERNS.healthLive });
      expect(response.statusCode).toBe(200);
    }
  });
});
