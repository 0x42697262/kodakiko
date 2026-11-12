import { sql, type Kysely, type Selectable } from 'kysely';
import {
  COLLATE_KEYWORD,
  COLLATION_C,
  COLLECTIONS_ID_CONSTRAINT,
  PG_UNIQUE_VIOLATION,
} from '../../config/constants.ts';
import type { CollectionKind } from '../../domain/enums.ts';
import type { CollectionsTable, Database } from './schema.ts';

export type NewHomeCollection = {
  readonly id: string;
  readonly ownerId: string;
  readonly keyByOwner: Buffer;
  readonly metadataBlob: Buffer;
  readonly now: Date;
};

export type InsertHomeResult = 'inserted' | 'idConflict';

export type CollectionRole = 'owner' | 'viewer';
export type CollectionRow = Selectable<CollectionsTable>;

export type ReadableCollection = {
  readonly id: string;
  readonly kind: CollectionKind;
  readonly role: CollectionRole;
  readonly ownerId: string;
  readonly ownerUsername: string;
  readonly keyMaterial: Buffer;
  readonly metadataBlob: Buffer;
  readonly lastSeq: number;
  readonly tombstonesPrunedThroughSeq: number;
  readonly createdAt: Date;
  readonly metaUpdatedAt: Date;
};

type Executor = Kysely<Database>;

type PgError = { readonly code?: string; readonly constraint?: string };

function insertHomeResultOf(error: unknown): InsertHomeResult {
  const { code, constraint } = error as PgError;
  if (code === PG_UNIQUE_VIOLATION && constraint === COLLECTIONS_ID_CONSTRAINT) {
    return 'idConflict';
  }
  throw error;
}

function collateClause() {
  return sql.raw(`${COLLATE_KEYWORD} "${COLLATION_C}"`);
}

function readableQuery(executor: Executor, userId: string) {
  return executor
    .selectFrom('collections')
    .innerJoin('users', 'users.id', 'collections.ownerId')
    .leftJoin('collectionShares', (join) =>
      join
        .onRef('collectionShares.collectionId', '=', 'collections.id')
        .on('collectionShares.recipientId', '=', userId),
    )
    .where((builder) =>
      builder.and([
        builder.or([
          builder('collections.ownerId', '=', userId),
          builder('collectionShares.recipientId', 'is not', null),
        ]),
        builder.or([
          builder('collections.kind', '<>', 'link'),
          builder('collections.ownerId', '=', userId),
        ]),
      ]),
    )
    .select([
      'collections.id',
      'collections.kind',
      'collections.ownerId',
      'users.username as ownerUsername',
      'collections.keyByOwner',
      'collectionShares.sealedCollectionKey',
      'collections.metadataBlob',
      'collections.lastSeq',
      'collections.tombstonesPrunedThroughSeq',
      'collections.createdAt',
      'collections.metaUpdatedAt',
    ]);
}

type ReadableSelection = Awaited<ReturnType<ReturnType<typeof readableQuery>['execute']>>[number];

function keyMaterialOf(row: ReadableSelection, owner: boolean): Buffer | null {
  return owner ? row.keyByOwner : row.sealedCollectionKey;
}

function toReadable(row: ReadableSelection, userId: string): ReadableCollection | undefined {
  const owner = row.ownerId === userId;
  const keyMaterial = keyMaterialOf(row, owner);
  if (keyMaterial === null) return undefined;
  return {
    id: row.id,
    kind: row.kind,
    role: owner ? 'owner' : 'viewer',
    ownerId: row.ownerId,
    ownerUsername: row.ownerUsername,
    keyMaterial,
    metadataBlob: row.metadataBlob,
    lastSeq: row.lastSeq,
    tombstonesPrunedThroughSeq: row.tombstonesPrunedThroughSeq,
    createdAt: row.createdAt,
    metaUpdatedAt: row.metaUpdatedAt,
  };
}

function readableRows(
  rows: readonly ReadableSelection[],
  userId: string,
): ReadableCollection[] {
  return rows.flatMap((row) => toReadable(row, userId) ?? []);
}

export function createCollectionsRepository(db: Kysely<Database>) {
  return {
    findById(executor: Executor, id: string): Promise<CollectionRow | undefined> {
      return executor.selectFrom('collections').selectAll().where('id', '=', id).executeTakeFirst();
    },

    async findReadable(
      executor: Executor,
      userId: string,
      id: string,
    ): Promise<ReadableCollection | undefined> {
      const row = await readableQuery(executor, userId)
        .where('collections.id', '=', id)
        .executeTakeFirst();
      return row === undefined ? undefined : toReadable(row, userId);
    },

    async listReadable(
      executor: Executor,
      userId: string,
      afterId: string | null,
      limit: number,
    ): Promise<ReadableCollection[]> {
      const collation = collateClause();
      let query = readableQuery(executor, userId);
      if (afterId !== null) {
        query = query.where(sql<boolean>`collections.id ${collation} > ${afterId}`);
      }
      const rows = await query
        .orderBy(sql`collections.id ${collation}`)
        .limit(limit)
        .execute();
      return readableRows(rows, userId);
    },

    async updateMetadata(
      executor: Executor,
      id: string,
      blob: Buffer,
      now: Date,
    ): Promise<void> {
      await executor
        .updateTable('collections')
        .set({ metadataBlob: blob, metaUpdatedAt: now, updatedAt: now })
        .where('id', '=', id)
        .execute();
    },

    async syncState(executor: Executor, userId: string): Promise<ReadableCollection[]> {
      const rows = await readableQuery(executor, userId)
        .orderBy(sql`collections.id ${collateClause()}`)
        .execute();
      return readableRows(rows, userId);
    },

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
