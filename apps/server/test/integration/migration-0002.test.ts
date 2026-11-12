import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { sql, type Kysely } from 'kysely';
import { MIGRATIONS } from '../../src/adapters/db/migrations/index.ts';
import { down, up } from '../../src/adapters/db/migrations/0002_objects_part_size.ts';
import { createTestDatabase, type TestDatabase } from './helpers.ts';

let testDatabase: TestDatabase;

function untyped(): Kysely<unknown> {
  return testDatabase.db as unknown as Kysely<unknown>;
}

async function partSizeColumn() {
  const result = await sql<{ dataType: string; isNullable: string }>`
    select data_type as "dataType", is_nullable as "isNullable"
    from information_schema.columns
    where table_name = 'objects' and column_name = 'part_size_bytes'`.execute(untyped());
  return result.rows[0];
}

beforeAll(async () => {
  testDatabase = await createTestDatabase();
});

afterAll(async () => {
  await testDatabase.drop();
});

describe('migration 0002_objects_part_size', () => {
  it('is registered after 0001_initial', () => {
    expect(Object.keys(MIGRATIONS)).toEqual(['0001_initial', '0002_objects_part_size']);
  });

  it('adds a nullable integer part_size_bytes to objects', async () => {
    expect(await partSizeColumn()).toEqual({ dataType: 'integer', isNullable: 'YES' });
  });

  it('goes down and up again', async () => {
    await down(untyped());
    expect(await partSizeColumn()).toBeUndefined();
    await up(untyped());
    expect(await partSizeColumn()).toEqual({ dataType: 'integer', isNullable: 'YES' });
  });
});
