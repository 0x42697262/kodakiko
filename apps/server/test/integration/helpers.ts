import { randomBytes } from 'node:crypto';
import type { FastifyInstance } from 'fastify';
import type { Kysely } from 'kysely';
import pg from 'pg';
import { buildApp, type AppDeps } from '../../src/app.ts';
import { loadSettings, type Settings } from '../../src/config/settings.ts';
import { createDatabase } from '../../src/adapters/db/database.ts';
import type { Database } from '../../src/adapters/db/schema.ts';
import { migrateToLatest } from '../../src/adapters/db/migrator.ts';
import { DEFAULT_TEST_DATABASE_URL } from './test-constants.ts';

const RANDOM_NAME_BYTES = 8;
const TEST_POOL_MAX = 5;
const TEST_STATEMENT_TIMEOUT_MS = 10_000;
const TEST_CONNECT_TIMEOUT_MS = 5000;

export function ignorePoolError(): void {}

export function adminDatabaseUrl(): string {
  return process.env['TEST_DATABASE_URL'] ?? DEFAULT_TEST_DATABASE_URL;
}

export type TestDatabase = {
  readonly db: Kysely<Database>;
  readonly url: string;
  drop(): Promise<void>;
};

export async function createTestDatabase(): Promise<TestDatabase> {
  const name = `kodakiko_test_${randomBytes(RANDOM_NAME_BYTES).toString('hex')}`;
  const admin = new pg.Client({ connectionString: adminDatabaseUrl() });
  await admin.connect();
  await admin.query(`CREATE DATABASE ${name}`);
  await admin.end();
  const url = new URL(adminDatabaseUrl());
  url.pathname = `/${name}`;
  const db = createDatabase(
    {
      url: url.toString(),
      poolMax: TEST_POOL_MAX,
      statementTimeoutMs: TEST_STATEMENT_TIMEOUT_MS,
      connectTimeoutMs: TEST_CONNECT_TIMEOUT_MS,
    },
    ignorePoolError,
  );
  await migrateToLatest(db);
  return {
    db,
    url: url.toString(),
    async drop() {
      await db.destroy();
      const dropper = new pg.Client({ connectionString: adminDatabaseUrl() });
      await dropper.connect();
      await dropper.query(`DROP DATABASE IF EXISTS ${name} WITH (FORCE)`);
      await dropper.end();
    },
  };
}

const FIXED_TEST_ENV: Readonly<Record<string, string>> = Object.freeze({
  PUBLIC_BASE_URL: 'http://localhost:8080',
  REQUIRE_HTTPS: 'false',
  DATABASE_URL: DEFAULT_TEST_DATABASE_URL,
  LOG_LEVEL: 'fatal',
  JOBS_ENABLED: 'false',
});

export function testSettings(overrides: Readonly<Record<string, string>> = {}): Settings {
  return loadSettings({ ...FIXED_TEST_ENV, ...overrides });
}

export async function buildTestApp(deps: AppDeps): Promise<FastifyInstance> {
  const app = await buildApp(deps);
  await app.ready();
  return app;
}
