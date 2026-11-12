import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { ROUTE_PATTERNS } from '@kodakiko/contract';
import { createS3ObjectStore } from '../../src/adapters/storage/s3-object-store.ts';
import { systemClock } from '../../src/platform/clock.ts';
import { bucketCheck } from '../../src/services/readiness.ts';
import { databaseCheck } from '../../src/adapters/db/database.ts';
import {
  buildTestApp,
  createTestDatabase,
  createTestObjectStore,
  testSettings,
  type TestDatabase,
} from './helpers.ts';

const CLOSED_PORT_ENDPOINT = 'http://127.0.0.1:1';

let testDatabase: TestDatabase;

beforeAll(async () => {
  testDatabase = await createTestDatabase();
});

afterAll(async () => {
  await testDatabase.drop();
});

describe('11. readiness bucket check', () => {
  it('reports 503 with failed bucket when the endpoint refuses connections', async () => {
    const settings = testSettings({
      S3_ENDPOINT: CLOSED_PORT_ENDPOINT,
      S3_PUBLIC_ENDPOINT: CLOSED_PORT_ENDPOINT,
    });
    const store = createS3ObjectStore(settings.storage);
    const app = await buildTestApp({
      settings,
      db: testDatabase.db,
      clock: systemClock,
      readinessChecks: [databaseCheck(testDatabase.db), bucketCheck(store)],
      objectStore: store,
    });
    const response = await app.inject({ method: 'GET', url: ROUTE_PATTERNS.healthReady });
    expect(response.statusCode).toBe(503);
    expect(response.json()).toEqual({ status: 'unavailable', failed: ['bucket'] });
    await app.close();
  });

  it('is ready against the test Garage', async () => {
    const store = createTestObjectStore();
    const settings = testSettings();
    const app = await buildTestApp({
      settings,
      db: testDatabase.db,
      clock: systemClock,
      readinessChecks: [databaseCheck(testDatabase.db), bucketCheck(store)],
      objectStore: store,
    });
    const response = await app.inject({ method: 'GET', url: ROUTE_PATTERNS.healthReady });
    expect(response.statusCode).toBe(200);
    await app.close();
  });

  it('names the check bucket', () => {
    expect(bucketCheck(createTestObjectStore()).name).toBe('bucket');
  });
});
