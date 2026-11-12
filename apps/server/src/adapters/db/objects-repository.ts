import { sql, type Insertable, type Kysely, type Selectable, type Transaction } from 'kysely';
import {
  OBJECTS_ID_CONSTRAINT,
  OBJECTS_PENDING_VARIANT_CONSTRAINT,
  PG_UNIQUE_VIOLATION,
} from '../../config/constants.ts';
import type { Database, ObjectsTable } from './schema.ts';

export type ObjectRow = Selectable<ObjectsTable>;
export type NewObject = Insertable<ObjectsTable>;
export type InsertPendingResult = 'inserted' | 'idConflict' | 'pendingExists';
export type MediaOwner = { readonly ownerId: string; readonly entryId: string };
export type OwnerUsage = {
  readonly ownerId: string;
  readonly usedBytes: number;
  readonly sumBytes: number;
};

type Executor = Kysely<Database>;
type PgError = { readonly code?: string; readonly constraint?: string };

function insertPendingResultOf(error: unknown): InsertPendingResult {
  const { code, constraint } = error as PgError;
  if (code === PG_UNIQUE_VIOLATION && constraint === OBJECTS_ID_CONSTRAINT) return 'idConflict';
  if (code === PG_UNIQUE_VIOLATION && constraint === OBJECTS_PENDING_VARIANT_CONSTRAINT) {
    return 'pendingExists';
  }
  throw error;
}

export const objectsRepository = {
  findById(trx: Executor, id: string): Promise<ObjectRow | undefined> {
    return trx.selectFrom('objects').selectAll().where('id', '=', id).executeTakeFirst();
  },

  async insertPending(trx: Executor, row: NewObject): Promise<InsertPendingResult> {
    try {
      await trx.insertInto('objects').values(row).execute();
      return 'inserted';
    } catch (error) {
      return insertPendingResultOf(error);
    }
  },

  async setMultipartUploadId(trx: Executor, id: string, uploadId: string): Promise<boolean> {
    const updated = await trx
      .updateTable('objects')
      .set({ multipartUploadId: uploadId })
      .where('id', '=', id)
      .where('multipartUploadId', 'is', null)
      .returning('id')
      .executeTakeFirst();
    return updated !== undefined;
  },

  async markUploaded(
    trx: Transaction<Database>,
    id: string,
    now: Date,
    replacedDeleteAfter: Date,
  ): Promise<ObjectRow | undefined> {
    const row = await trx
      .selectFrom('objects')
      .selectAll()
      .where('id', '=', id)
      .forUpdate()
      .executeTakeFirst();
    if (row === undefined || row.state === 'deleting') return undefined;
    if (row.state === 'uploaded') return row;
    await trx
      .updateTable('objects')
      .set({ state: 'deleting', deleteAfter: replacedDeleteAfter })
      .where('mediaId', '=', row.mediaId)
      .where('variant', '=', row.variant)
      .where('state', '=', 'uploaded')
      .where('id', '<>', id)
      .execute();
    return trx
      .updateTable('objects')
      .set({ state: 'uploaded', uploadedAt: now })
      .where('id', '=', id)
      .returningAll()
      .executeTakeFirstOrThrow();
  },

  async markDeleting(trx: Executor, id: string, deleteAfter: Date): Promise<void> {
    await trx
      .updateTable('objects')
      .set({ state: 'deleting', deleteAfter })
      .where('id', '=', id)
      .where('state', '<>', 'deleting')
      .execute();
  },

  async markPendingDeleting(trx: Executor, id: string, deleteAfter: Date): Promise<boolean> {
    const moved = await trx
      .updateTable('objects')
      .set({ state: 'deleting', deleteAfter })
      .where('id', '=', id)
      .where('state', '=', 'pending')
      .returning('id')
      .executeTakeFirst();
    return moved !== undefined;
  },

  async markDeletingByMediaIds(
    trx: Executor,
    mediaIds: readonly string[],
    deleteAfter: Date,
  ): Promise<void> {
    if (mediaIds.length === 0) return;
    await trx
      .updateTable('objects')
      .set({ state: 'deleting', deleteAfter })
      .where('mediaId', 'in', [...mediaIds])
      .where('state', 'in', ['pending', 'uploaded'])
      .execute();
  },

  async adjustUsedBytes(trx: Executor, userId: string, delta: number): Promise<void> {
    await trx
      .updateTable('users')
      .set((builder) => ({ usedBytes: builder('usedBytes', '+', delta) }))
      .where('id', '=', userId)
      .execute();
  },

  async findMediaOwner(trx: Executor, mediaId: string): Promise<MediaOwner | undefined> {
    return trx
      .selectFrom('media')
      .innerJoin('entries', 'entries.id', 'media.entryId')
      .select(['entries.ownerId as ownerId', 'media.entryId as entryId'])
      .where('media.id', '=', mediaId)
      .executeTakeFirst();
  },

  async canRead(trx: Executor, userId: string, objectId: string): Promise<boolean> {
    const result = await sql<{ allowed: boolean }>`
      select exists (
        select 1
        from objects
        join media on media.id = objects.media_id
        join entry_memberships on entry_memberships.entry_id = media.entry_id
        join collections on collections.id = entry_memberships.collection_id
        where objects.id = ${objectId}
          and (
            collections.owner_id = ${userId}
            or exists (
              select 1
              from collection_shares
              where collection_shares.collection_id = collections.id
                and collection_shares.recipient_id = ${userId}
            )
          )
      ) as allowed`.execute(trx);
    return result.rows[0]?.allowed === true;
  },

  selectCollectable(trx: Executor, now: Date, limit: number): Promise<ObjectRow[]> {
    return trx
      .selectFrom('objects')
      .selectAll()
      .where('state', '=', 'deleting')
      .where('deleteAfter', '<', now)
      .orderBy('deleteAfter')
      .limit(limit)
      .execute();
  },

  async deleteRow(trx: Executor, id: string): Promise<number | undefined> {
    const removed = await trx
      .deleteFrom('objects')
      .where('id', '=', id)
      .where('state', '=', 'deleting')
      .returning('sizeBytes')
      .executeTakeFirst();
    return removed?.sizeBytes;
  },

  selectStalePending(trx: Executor, cutoff: Date, limit: number): Promise<ObjectRow[]> {
    return trx
      .selectFrom('objects')
      .selectAll()
      .where('state', '=', 'pending')
      .where('createdAt', '<', cutoff)
      .orderBy('createdAt')
      .limit(limit)
      .execute();
  },

  async sumSizesByOwner(trx: Executor): Promise<OwnerUsage[]> {
    const result = await sql<{ ownerId: string; usedBytes: number; sumBytes: number }>`
      select users.id as owner_id,
             users.used_bytes as used_bytes,
             cast(coalesce(sum(objects.size_bytes), 0) as bigint) as sum_bytes
      from users
      left join objects on objects.owner_id = users.id
      group by users.id, users.used_bytes`.execute(trx);
    return result.rows;
  },

  async sumSizeOfOwner(trx: Executor, userId: string): Promise<number> {
    const result = await sql<{ sumBytes: number }>`
      select cast(coalesce(sum(size_bytes), 0) as bigint) as sum_bytes
      from objects
      where owner_id = ${userId}`.execute(trx);
    return result.rows[0]?.sumBytes ?? 0;
  },

  async setUsedBytes(trx: Executor, userId: string, value: number): Promise<void> {
    await trx.updateTable('users').set({ usedBytes: value }).where('id', '=', userId).execute();
  },

  async countPresignsSince(trx: Executor, userId: string, since: Date): Promise<number> {
    const row = await trx
      .selectFrom('objects')
      .select((builder) => builder.fn.countAll<number>().as('total'))
      .where('ownerId', '=', userId)
      .where('createdAt', '>=', since)
      .executeTakeFirstOrThrow();
    return Number(row.total);
  },
};
