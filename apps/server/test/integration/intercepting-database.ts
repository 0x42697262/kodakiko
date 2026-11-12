import {
  CamelCasePlugin,
  Kysely,
  PostgresDialect,
  type DatabaseConnection,
  type Driver,
} from 'kysely';
import pg from 'pg';
import type { Database } from '../../src/adapters/db/schema.ts';

const OBJECTS_UPDATE_PREFIX = 'update "objects"';
const INTERCEPT_POOL_MAX = 2;

class InterceptingDialect extends PostgresDialect {
  readonly #hook: () => Promise<void>;
  #armed = true;

  constructor(pool: pg.Pool, hook: () => Promise<void>) {
    super({ pool });
    this.#hook = hook;
  }

  override createDriver(): Driver {
    const inner = super.createDriver();
    const patched = new WeakSet<DatabaseConnection>();
    const patch = (connection: DatabaseConnection): DatabaseConnection => {
      if (patched.has(connection)) return connection;
      patched.add(connection);
      const original = connection.executeQuery.bind(connection);
      connection.executeQuery = async (query) => {
        if (this.#armed && query.sql.startsWith(OBJECTS_UPDATE_PREFIX)) {
          this.#armed = false;
          await this.#hook();
        }
        return original(query);
      };
      return connection;
    };
    return {
      init: () => inner.init(),
      acquireConnection: async () => patch(await inner.acquireConnection()),
      beginTransaction: (connection, settings) => inner.beginTransaction(connection, settings),
      commitTransaction: (connection) => inner.commitTransaction(connection),
      rollbackTransaction: (connection) => inner.rollbackTransaction(connection),
      releaseConnection: (connection) => inner.releaseConnection(connection),
      destroy: () => inner.destroy(),
    };
  }
}

export function createInterceptedDatabase(
  url: string,
  beforeFirstObjectsUpdate: () => Promise<void>,
): Kysely<Database> {
  const pool = new pg.Pool({ connectionString: url, max: INTERCEPT_POOL_MAX });
  return new Kysely<Database>({
    dialect: new InterceptingDialect(pool, beforeFirstObjectsUpdate),
    plugins: [new CamelCasePlugin()],
  });
}
