import { CamelCasePlugin, Kysely, PostgresDialect, sql } from 'kysely';
import pg from 'pg';
import { INT8_OID, READY_CHECK_TIMEOUT_MS, DATABASE_CHECK_NAME } from '../../config/constants.ts';
import type { Settings } from '../../config/settings.ts';
import { SYSTEM_TEXT } from '../../text/system.ts';
import type { ReadinessCheck } from '../../services/readiness.ts';
import type { Database } from './schema.ts';

function parseSafeInteger(value: string): number {
  const parsed = BigInt(value);
  if (parsed > BigInt(Number.MAX_SAFE_INTEGER) || parsed < BigInt(Number.MIN_SAFE_INTEGER)) {
    throw new RangeError(SYSTEM_TEXT.integerOutOfRange);
  }
  return Number(parsed);
}

pg.types.setTypeParser(INT8_OID, parseSafeInteger);

export type PoolErrorHandler = (error: Error) => void;

export function createDatabase(
  config: Settings['database'],
  onPoolError: PoolErrorHandler,
): Kysely<Database> {
  const pool = new pg.Pool({
    connectionString: config.url,
    max: config.poolMax,
    connectionTimeoutMillis: config.connectTimeoutMs,
    statement_timeout: config.statementTimeoutMs,
  });
  pool.on('error', onPoolError);
  return new Kysely<Database>({
    dialect: new PostgresDialect({ pool }),
    plugins: [new CamelCasePlugin()],
  });
}

function rejectAfter(milliseconds: number): { promise: Promise<never>; cancel(): void } {
  let timer: NodeJS.Timeout | undefined;
  const promise = new Promise<never>((_, reject) => {
    timer = setTimeout(() => reject(new Error(SYSTEM_TEXT.readinessTimeout)), milliseconds);
  });
  return { promise, cancel: () => clearTimeout(timer) };
}

export function databaseCheck(db: Kysely<Database>): ReadinessCheck {
  return {
    name: DATABASE_CHECK_NAME,
    async check() {
      const timeout = rejectAfter(READY_CHECK_TIMEOUT_MS);
      try {
        await Promise.race([sql`select 1`.execute(db), timeout.promise]);
      } finally {
        timeout.cancel();
      }
    },
  };
}
