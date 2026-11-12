import { afterAll, beforeAll, describe, expect, it, vi } from 'vitest';
import { sql } from 'kysely';
import pg from 'pg';
import { createDatabase } from '../../src/adapters/db/database.ts';
import {
  adminDatabaseUrl,
  createTestDatabase,
  testSettings,
  type TestDatabase,
} from './helpers.ts';

const WAIT_MS = 5000;

let testDatabase: TestDatabase;

beforeAll(async () => {
  testDatabase = await createTestDatabase();
});

afterAll(async () => {
  await testDatabase.drop();
});

describe('database pool errors', () => {
  it('reports an idle client error to the handler and keeps working', async () => {
    const onPoolError = vi.fn();
    const settings = testSettings({ DATABASE_URL: testDatabase.url });
    const db = createDatabase(settings.database, onPoolError);
    await sql`select 1`.execute(db);

    const admin = new pg.Client({ connectionString: adminDatabaseUrl() });
    await admin.connect();
    const databaseName = new URL(testDatabase.url).pathname.slice(1);
    await admin.query(
      'select pg_terminate_backend(pid) from pg_stat_activity where datname = $1 and pid <> pg_backend_pid()',
      [databaseName],
    );
    await admin.end();

    await vi.waitFor(() => expect(onPoolError).toHaveBeenCalled(), { timeout: WAIT_MS });
    expect(onPoolError.mock.calls[0]?.[0]).toBeInstanceOf(Error);
    await expect(sql`select 1`.execute(db)).resolves.toBeDefined();
    await db.destroy();
  });
});
