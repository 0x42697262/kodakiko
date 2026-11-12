import { sql, type Insertable, type Kysely, type Selectable } from 'kysely';
import type {
  Database,
  EntriesTable,
  EntryMembershipsTable,
  MediaTable,
  MembershipTombstonesTable,
} from './schema.ts';

export type EntryRow = Selectable<EntriesTable>;
export type NewEntry = Insertable<EntriesTable>;
export type NewMembership = Insertable<EntryMembershipsTable>;
export type NewTombstone = Insertable<MembershipTombstonesTable>;
export type MediaRow = Selectable<MediaTable>;
export type NewMedia = Insertable<MediaTable>;
export type InsertResult = 'inserted' | 'idConflict';

export type EntrySnapshot = {
  readonly entry: EntryRow;
  readonly collectionIds: readonly string[];
  readonly mediaIds: readonly string[];
};

type Executor = Kysely<Database>;
type InsertResultRows = { readonly numInsertedOrUpdatedRows?: bigint | undefined };

function insertedCount(result: InsertResultRows): number {
  return Number(result.numInsertedOrUpdatedRows ?? 0);
}

export const entriesRepository = {
  async insertEntry(trx: Executor, row: NewEntry): Promise<InsertResult> {
    const result = await trx
      .insertInto('entries')
      .values(row)
      .onConflict((conflict) => conflict.column('id').doNothing())
      .executeTakeFirst();
    return insertedCount(result) === 1 ? 'inserted' : 'idConflict';
  },

  findEntry(
    trx: Executor,
    id: string,
    options: { readonly forUpdate: boolean } = { forUpdate: false },
  ): Promise<EntryRow | undefined> {
    const query = trx.selectFrom('entries').selectAll().where('id', '=', id);
    return (options.forUpdate ? query.forUpdate() : query).executeTakeFirst();
  },

  async findEntrySnapshot(trx: Executor, id: string): Promise<EntrySnapshot | undefined> {
    const entry = await entriesRepository.findEntry(trx, id);
    if (entry === undefined) return undefined;
    const memberships = await trx
      .selectFrom('entryMemberships')
      .select('collectionId')
      .where('entryId', '=', id)
      .execute();
    const media = await trx.selectFrom('media').select('id').where('entryId', '=', id).execute();
    return {
      entry,
      collectionIds: memberships.map((row) => row.collectionId),
      mediaIds: media.map((row) => row.id),
    };
  },

  findReadable(trx: Executor, userId: string, id: string): Promise<EntryRow | undefined> {
    return trx
      .selectFrom('entries')
      .selectAll('entries')
      .where('entries.id', '=', id)
      .where((builder) =>
        builder.exists(
          builder
            .selectFrom('entryMemberships')
            .innerJoin('collections', 'collections.id', 'entryMemberships.collectionId')
            .select('entryMemberships.entryId')
            .whereRef('entryMemberships.entryId', '=', 'entries.id')
            .where((inner) =>
              inner.or([
                inner('collections.ownerId', '=', userId),
                inner.exists(
                  inner
                    .selectFrom('collectionShares')
                    .select('collectionShares.collectionId')
                    .whereRef('collectionShares.collectionId', '=', 'collections.id')
                    .where('collectionShares.recipientId', '=', userId),
                ),
              ]),
            ),
        ),
      )
      .executeTakeFirst();
  },

  async updateMetadata(
    trx: Executor,
    id: string,
    blob: Buffer,
    revision: number,
    now: Date,
  ): Promise<void> {
    await trx
      .updateTable('entries')
      .set({ metadataBlob: blob, revision, updatedAt: now })
      .where('id', '=', id)
      .execute();
  },

  async setTrashedAt(trx: Executor, id: string, value: Date | null, now: Date): Promise<void> {
    await trx
      .updateTable('entries')
      .set({ trashedAt: value, updatedAt: now })
      .where('id', '=', id)
      .execute();
  },

  async deleteEntry(trx: Executor, id: string): Promise<void> {
    await trx.deleteFrom('entries').where('id', '=', id).execute();
  },

  async insertMemberships(trx: Executor, rows: readonly NewMembership[]): Promise<void> {
    if (rows.length === 0) return;
    await trx.insertInto('entryMemberships').values([...rows]).execute();
  },

  async membershipCollectionIds(trx: Executor, entryId: string): Promise<string[]> {
    const rows = await trx
      .selectFrom('entryMemberships')
      .select('collectionId')
      .where('entryId', '=', entryId)
      .execute();
    return rows.map((row) => row.collectionId);
  },

  async updateMembershipSeqs(
    trx: Executor,
    entryId: string,
    seqByCollection: ReadonlyMap<string, number>,
  ): Promise<void> {
    if (seqByCollection.size === 0) return;
    const values = sql.join(
      [...seqByCollection].map(
        ([collectionId, seq]) => sql`(${collectionId}::text, ${seq}::bigint)`,
      ),
    );
    await sql`
      update entry_memberships as membership
      set seq = updated.seq
      from (values ${values}) as updated (collection_id, seq)
      where membership.entry_id = ${entryId}
        and membership.collection_id = updated.collection_id`.execute(trx);
  },

  async insertTombstones(trx: Executor, rows: readonly NewTombstone[]): Promise<void> {
    if (rows.length === 0) return;
    await trx.insertInto('membershipTombstones').values([...rows]).execute();
  },

  async selectCollectionsWithTombstonesBefore(
    trx: Executor,
    cutoff: Date,
    limit: number,
  ): Promise<string[]> {
    const rows = await trx
      .selectFrom('membershipTombstones')
      .select('collectionId')
      .distinct()
      .where('removedAt', '<', cutoff)
      .orderBy('collectionId')
      .limit(limit)
      .execute();
    return rows.map((row) => row.collectionId);
  },

  async deleteTombstonesBefore(
    trx: Executor,
    collectionId: string,
    cutoff: Date,
    limit: number,
  ): Promise<{ readonly count: number; readonly maxSeq: number }> {
    const result = await sql<{ seq: number }>`
      with doomed as (
        select collection_id, seq
        from membership_tombstones
        where collection_id = ${collectionId} and removed_at < ${cutoff}
        order by seq
        limit ${limit}
      )
      delete from membership_tombstones as tombstone
      using doomed
      where tombstone.collection_id = doomed.collection_id and tombstone.seq = doomed.seq
      returning tombstone.seq`.execute(trx);
    return {
      count: result.rows.length,
      maxSeq: result.rows.reduce((max, row) => Math.max(max, row.seq), 0),
    };
  },

  async raisePrunedThroughSeq(trx: Executor, collectionId: string, seq: number): Promise<void> {
    await sql`
      update collections
      set tombstones_pruned_through_seq = greatest(tombstones_pruned_through_seq, ${seq})
      where id = ${collectionId}`.execute(trx);
  },

  async deleteMembership(trx: Executor, collectionId: string, entryId: string): Promise<void> {
    await trx
      .deleteFrom('entryMemberships')
      .where('collectionId', '=', collectionId)
      .where('entryId', '=', entryId)
      .execute();
  },

  async insertMedia(trx: Executor, row: NewMedia): Promise<InsertResult> {
    const result = await trx
      .insertInto('media')
      .values(row)
      .onConflict((conflict) => conflict.column('id').doNothing())
      .executeTakeFirst();
    return insertedCount(result) === 1 ? 'inserted' : 'idConflict';
  },

  findMedia(
    trx: Executor,
    id: string,
    options: { readonly forUpdate: boolean } = { forUpdate: false },
  ): Promise<MediaRow | undefined> {
    const query = trx.selectFrom('media').selectAll().where('id', '=', id);
    return (options.forUpdate ? query.forUpdate() : query).executeTakeFirst();
  },

  async mediaIdsOfEntry(trx: Executor, entryId: string): Promise<string[]> {
    const rows = await trx
      .selectFrom('media')
      .select('id')
      .where('entryId', '=', entryId)
      .orderBy('id')
      .forUpdate()
      .execute();
    return rows.map((row) => row.id);
  },

  async countMedia(trx: Executor, entryId: string): Promise<number> {
    const row = await trx
      .selectFrom('media')
      .select((builder) => builder.fn.countAll<number>().as('total'))
      .where('entryId', '=', entryId)
      .executeTakeFirstOrThrow();
    return Number(row.total);
  },

  async deleteMedia(trx: Executor, id: string): Promise<void> {
    await trx.deleteFrom('media').where('id', '=', id).execute();
  },

  async selectTrashedBefore(trx: Executor, cutoff: Date, limit: number): Promise<string[]> {
    const rows = await trx
      .selectFrom('entries')
      .select('id')
      .where('trashedAt', 'is not', null)
      .where('trashedAt', '<=', cutoff)
      .orderBy('trashedAt')
      .limit(limit)
      .execute();
    return rows.map((row) => row.id);
  },

  async findOwnedIds(trx: Executor, userId: string, ids: readonly string[]): Promise<string[]> {
    if (ids.length === 0) return [];
    const rows = await trx
      .selectFrom('entries')
      .select('id')
      .where('ownerId', '=', userId)
      .where('id', 'in', [...ids])
      .execute();
    return rows.map((row) => row.id);
  },
};
