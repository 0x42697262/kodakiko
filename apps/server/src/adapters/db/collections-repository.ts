import type { Kysely } from 'kysely';
import { COLLECTIONS_ID_CONSTRAINT, PG_UNIQUE_VIOLATION } from '../../config/constants.ts';
import type { Database } from './schema.ts';

export type NewHomeCollection = {
  readonly id: string;
  readonly ownerId: string;
  readonly keyByOwner: Buffer;
  readonly metadataBlob: Buffer;
  readonly now: Date;
};

export type InsertHomeResult = 'inserted' | 'idConflict';

type PgError = { readonly code?: string; readonly constraint?: string };

function insertHomeResultOf(error: unknown): InsertHomeResult {
  const { code, constraint } = error as PgError;
  if (code === PG_UNIQUE_VIOLATION && constraint === COLLECTIONS_ID_CONSTRAINT) {
    return 'idConflict';
  }
  throw error;
}

export function createCollectionsRepository(db: Kysely<Database>) {
  return {
    async insertHome(home: NewHomeCollection): Promise<InsertHomeResult> {
      try {
        await db
          .insertInto('collections')
          .values({
            id: home.id,
            ownerId: home.ownerId,
            kind: 'home',
            keyByOwner: home.keyByOwner,
            metadataBlob: home.metadataBlob,
            createdAt: home.now,
            updatedAt: home.now,
            metaUpdatedAt: home.now,
          })
          .execute();
        return 'inserted';
      } catch (error) {
        return insertHomeResultOf(error);
      }
    },
  };
}
