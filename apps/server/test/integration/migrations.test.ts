import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { sql, type Kysely } from 'kysely';
import { down, up } from '../../src/adapters/db/migrations/0001_initial.ts';
import { createTestDatabase, type TestDatabase } from './helpers.ts';

const EXPECTED_TABLES = [
  'collection_shares',
  'collections',
  'entries',
  'entry_memberships',
  'invites',
  'media',
  'membership_tombstones',
  'objects',
  'public_links',
  'sessions',
  'users',
];

const EXPECTED_INDEXES = [
  'collection_shares_recipient_id_idx',
  'collections_one_home_per_owner',
  'collections_owner_id_idx',
  'entries_owner_id_idx',
  'entries_trash_purge_idx',
  'entry_memberships_entry_id_idx',
  'entry_memberships_feed_idx',
  'media_entry_id_idx',
  'objects_gc_idx',
  'objects_one_pending_per_variant',
  'objects_one_uploaded_per_variant',
  'objects_owner_id_idx',
  'objects_pending_idx',
  'sessions_expires_at_idx',
  'sessions_user_id_idx',
];

const VALID_ID = 'AAAAAAAAAAAAAAAAAAAAAA';
const SHORT_ID = 'AAAAAAAAAAAAAAAAAAAAA';
const ONE_GIB = 1073741824;
const OPS_LIMIT = 4;

let testDatabase: TestDatabase;

function untyped(): Kysely<unknown> {
  return testDatabase.db as unknown as Kysely<unknown>;
}

beforeAll(async () => {
  testDatabase = await createTestDatabase();
});

afterAll(async () => {
  await testDatabase.drop();
});

async function tableNames(): Promise<string[]> {
  const result = await sql<{ tableName: string }>`
    select table_name as "tableName" from information_schema.tables
    where table_schema = 'public' and table_name not like 'kysely_%'
    order by table_name`.execute(testDatabase.db);
  return result.rows.map((row) => row.tableName);
}

async function indexNames(): Promise<string[]> {
  const result = await sql<{ indexName: string }>`
    select indexname as "indexName" from pg_indexes
    where schemaname = 'public' and indexname not like '%_pkey' and indexname not like '%_key'
    order by indexname`.execute(testDatabase.db);
  return result.rows.map((row) => row.indexName);
}

function insertUser(id: string, username: string, memLimitBytes: number) {
  const hash = Buffer.alloc(32, 1);
  return sql`
    insert into users (id, username, role, status, login_key_hash, recovery_login_key_hash,
      kdf_salt, kdf_ops_limit, kdf_mem_limit_bytes, master_key_by_kek, master_key_by_recovery,
      recovery_key_by_master, public_key, private_key_by_master, created_at, updated_at)
    values (${id}, ${username}, 'member', 'active', ${hash}, ${hash}, ${Buffer.alloc(16, 2)},
      ${OPS_LIMIT}, ${memLimitBytes}, ${hash}, ${hash}, ${hash}, ${hash}, ${hash}, now(), now())
  `.execute(testDatabase.db);
}

describe('migration 0001_initial', () => {
  it('creates all eleven tables and the named indexes', async () => {
    expect(await tableNames()).toEqual(EXPECTED_TABLES);
    const indexes = await indexNames();
    for (const name of EXPECTED_INDEXES) {
      expect(indexes).toContain(name);
    }
  });

  it('enforces the CHECK constraints', async () => {
    await expect(insertUser(VALID_ID, 'alice', ONE_GIB)).resolves.toBeDefined();
    await expect(insertUser('BBBBBBBBBBBBBBBBBBBBBB', 'bob', 1)).rejects.toThrow(
      /check constraint/i,
    );
    await expect(insertUser(SHORT_ID, 'carol', ONE_GIB)).rejects.toThrow(/check constraint/i);
  });

  it('goes down and up again', async () => {
    await down(untyped());
    expect(await tableNames()).toEqual([]);
    await up(untyped());
    expect(await tableNames()).toEqual(EXPECTED_TABLES);
  });
});
