import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { sql } from 'kysely';
import { createTestDatabase, type TestDatabase } from './helpers.ts';

let testDatabase: TestDatabase;

beforeAll(async () => {
  testDatabase = await createTestDatabase();
  await sql`create table int8_probe (id integer primary key, amount bigint not null)`.execute(
    testDatabase.db,
  );
});

afterAll(async () => {
  await testDatabase.drop();
});

describe('bigint columns', () => {
  it('round-trips a safe value as a number', async () => {
    await sql`insert into int8_probe values (1, 9007199254740991)`.execute(testDatabase.db);
    const result = await sql<{
      amount: number;
    }>`select amount from int8_probe where id = 1`.execute(testDatabase.db);
    expect(result.rows[0]?.amount).toBe(Number.MAX_SAFE_INTEGER);
  });

  it('throws for a value above the safe integer range', async () => {
    await sql`insert into int8_probe values (2, 9007199254740993)`.execute(testDatabase.db);
    await expect(
      sql`select amount from int8_probe where id = 2`.execute(testDatabase.db),
    ).rejects.toThrow(RangeError);
  });
});
