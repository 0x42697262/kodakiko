import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { ROUTE_PATTERNS } from '@kodakiko/contract';
import { createDatabase, databaseCheck } from '../../src/adapters/db/database.ts';
import { systemClock } from '../../src/platform/clock.ts';
import {
  buildTestApp,
  createPlaceholderObjectStore,
  createTestDatabase,
  ignorePoolError,
  testSettings,
  type TestDatabase,
} from './helpers.ts';

let testDatabase: TestDatabase;

beforeAll(async () => {
  testDatabase = await createTestDatabase();
});

afterAll(async () => {
  await testDatabase.drop();
});

describe('GET /health/ready', () => {
  it('is 200 while the database answers and 503 after the pool is destroyed', async () => {
    const settings = testSettings({ DATABASE_URL: testDatabase.url });
    const db = createDatabase(settings.database, ignorePoolError);
    const app = await buildTestApp({
      settings,
      db,
      clock: systemClock,
      readinessChecks: [databaseCheck(db)],
      objectStore: createPlaceholderObjectStore(),
    });
    const ready = await app.inject({ method: 'GET', url: ROUTE_PATTERNS.healthReady });
    expect(ready.statusCode).toBe(200);
    expect(ready.json()).toEqual({ status: 'ok' });

    await db.destroy();
    const unavailable = await app.inject({ method: 'GET', url: ROUTE_PATTERNS.healthReady });
    expect(unavailable.statusCode).toBe(503);
    expect(unavailable.json()).toEqual({ status: 'unavailable', failed: ['database'] });
    await app.close();
  });
});
