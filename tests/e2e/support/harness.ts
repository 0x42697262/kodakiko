import { randomBytes } from 'node:crypto';
import { readFileSync } from 'node:fs';
import pg from 'pg';
import type { Sodium } from '@kodakiko/core';
import { createNodeSodium } from '@kodakiko/client/test-support';
import {
  buildApp,
  createDatabase,
  createS3ObjectStore,
  loadSettings,
  migrateToLatest,
} from '@kodakiko/server/testing';
import {
  ADMIN_DATABASE_ENV,
  DATABASE_CONNECT_TIMEOUT_MS,
  DATABASE_NAME_PREFIX,
  DATABASE_NAME_RANDOM_BYTES,
  DATABASE_POOL_MAX,
  DATABASE_STATEMENT_TIMEOUT_MS,
  DEFAULT_TEST_DATABASE_URL,
  E2E_ENV,
  GARAGE_BUCKET,
  GARAGE_ENDPOINT,
  GARAGE_KEY_FILE,
  LISTEN_HOST,
  LISTEN_PORT,
} from './constants.ts';

type Settings = ReturnType<typeof loadSettings>;
type Database = ReturnType<typeof createDatabase>;

export type Harness = {
  readonly baseUrl: string;
  readonly settings: Settings;
  readonly db: Database;
  readonly sodium: Sodium;
  readonly close: () => Promise<void>;
};

export type HarnessOptions = { readonly env?: Readonly<Record<string, string>> };

type GarageKey = { readonly accessKeyId: string; readonly secretAccessKey: string };

function adminDatabaseUrl(): string {
  return process.env[ADMIN_DATABASE_ENV] ?? DEFAULT_TEST_DATABASE_URL;
}

function garageKey(): GarageKey {
  return JSON.parse(readFileSync(GARAGE_KEY_FILE, 'utf8')) as GarageKey;
}

async function withAdminClient<Result>(work: (client: pg.Client) => Promise<Result>): Promise<Result> {
  const client = new pg.Client({ connectionString: adminDatabaseUrl() });
  await client.connect();
  try {
    return await work(client);
  } finally {
    await client.end();
  }
}

async function createScratchDatabase(): Promise<{ readonly name: string; readonly url: string }> {
  const name = `${DATABASE_NAME_PREFIX}${randomBytes(DATABASE_NAME_RANDOM_BYTES).toString('hex')}`;
  await withAdminClient((client) => client.query(`CREATE DATABASE ${name}`));
  const url = new URL(adminDatabaseUrl());
  url.pathname = `/${name}`;
  return { name, url: url.toString() };
}

function dropScratchDatabase(name: string): Promise<unknown> {
  return withAdminClient((client) => client.query(`DROP DATABASE IF EXISTS ${name} WITH (FORCE)`));
}

function ignorePoolError(): void {}

export async function startHarness(options: HarnessOptions = {}): Promise<Harness> {
  const scratch = await createScratchDatabase();
  const key = garageKey();
  const settings = loadSettings({
    ...E2E_ENV,
    DATABASE_URL: scratch.url,
    S3_ENDPOINT: GARAGE_ENDPOINT,
    S3_PUBLIC_ENDPOINT: GARAGE_ENDPOINT,
    S3_BUCKET: GARAGE_BUCKET,
    S3_ACCESS_KEY_ID: key.accessKeyId,
    S3_SECRET_ACCESS_KEY: key.secretAccessKey,
    ...options.env,
  });
  const db = createDatabase(
    {
      url: scratch.url,
      poolMax: DATABASE_POOL_MAX,
      statementTimeoutMs: DATABASE_STATEMENT_TIMEOUT_MS,
      connectTimeoutMs: DATABASE_CONNECT_TIMEOUT_MS,
    },
    ignorePoolError,
  );
  await migrateToLatest(db);
  const app = await buildApp({
    settings,
    db,
    clock: { now: () => new Date() },
    readinessChecks: [],
    objectStore: createS3ObjectStore(settings.storage),
  });
  await app.ready();
  const baseUrl = await app.listen({ port: LISTEN_PORT, host: LISTEN_HOST });
  return {
    baseUrl,
    settings,
    db,
    sodium: await createNodeSodium(),
    close: async () => {
      await app.close();
      await db.destroy();
      await dropScratchDatabase(scratch.name);
    },
  };
}
