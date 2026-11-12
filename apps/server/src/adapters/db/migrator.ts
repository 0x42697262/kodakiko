import type { Kysely } from 'kysely';
import { Migrator, type MigrationProvider } from 'kysely/migration';
import { SYSTEM_TEXT } from '../../text/system.ts';
import { MIGRATIONS } from './migrations/index.ts';

const staticProvider: MigrationProvider = {
  getMigrations: async () => MIGRATIONS,
};

export function createMigrator<Schema>(db: Kysely<Schema>): Migrator {
  return new Migrator({ db, provider: staticProvider });
}

export async function migrateToLatest<Schema>(db: Kysely<Schema>): Promise<string[]> {
  const { error, results = [] } = await createMigrator(db).migrateToLatest();
  const failed = results.find((result) => result.status === 'Error');
  if (error !== undefined || failed !== undefined) {
    throw error instanceof Error
      ? error
      : new Error(SYSTEM_TEXT.migrationFailed(failed?.migrationName));
  }
  return results
    .filter((result) => result.status === 'Success')
    .map((result) => result.migrationName);
}
