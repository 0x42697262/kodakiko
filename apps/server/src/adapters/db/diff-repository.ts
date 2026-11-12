import { sql, type Kysely, type Selectable } from 'kysely';
import type {
  Database,
  EntriesTable,
  MediaTable,
  MembershipTombstonesTable,
  ObjectsTable,
} from './schema.ts';

type Executor = Kysely<Database>;

export type DiffMembershipRow = {
  readonly entryId: string;
  readonly entryKeyByCollection: Buffer;
  readonly seq: number;
  readonly trashedAt: Date | null;
};

export type DiffTombstoneRow = Pick<Selectable<MembershipTombstonesTable>, 'entryId' | 'seq'>;
export type DiffEntryRow = Selectable<EntriesTable>;
export type DiffMediaRow = Selectable<MediaTable>;
export type DiffObjectRow = Selectable<ObjectsTable>;

export function membershipsSinceQuery(
  trx: Executor,
  collectionId: string,
  since: number,
  limit: number,
) {
  return trx
    .selectFrom('entryMemberships')
    .innerJoin('entries', 'entries.id', 'entryMemberships.entryId')
    .select([
      'entryMemberships.entryId',
      'entryMemberships.entryKeyByCollection',
      'entryMemberships.seq',
      'entries.trashedAt',
    ])
    .where('entryMemberships.collectionId', '=', collectionId)
    .where('entryMemberships.seq', '>', since)
    .orderBy('entryMemberships.seq')
    .limit(limit + 1);
}

export const diffRepository = {
  membershipsSince(
    trx: Executor,
    collectionId: string,
    since: number,
    limit: number,
  ): Promise<DiffMembershipRow[]> {
    return membershipsSinceQuery(trx, collectionId, since, limit).execute();
  },

  tombstonesSince(
    trx: Executor,
    collectionId: string,
    since: number,
    limit: number,
  ): Promise<DiffTombstoneRow[]> {
    return trx
      .selectFrom('membershipTombstones')
      .select(['entryId', 'seq'])
      .where('collectionId', '=', collectionId)
      .where('seq', '>', since)
      .orderBy('seq')
      .limit(limit + 1)
      .execute();
  },

  async entriesByIds(trx: Executor, ids: readonly string[]): Promise<DiffEntryRow[]> {
    if (ids.length === 0) return [];
    return trx
      .selectFrom('entries')
      .selectAll()
      .where(sql<boolean>`id = any(${[...ids]}::text[])`)
      .execute();
  },

  async mediaByEntryIds(trx: Executor, entryIds: readonly string[]): Promise<DiffMediaRow[]> {
    if (entryIds.length === 0) return [];
    return trx
      .selectFrom('media')
      .selectAll()
      .where(sql<boolean>`entry_id = any(${[...entryIds]}::text[])`)
      .orderBy('createdAt')
      .orderBy('id')
      .execute();
  },

  async uploadedObjectsByMediaIds(
    trx: Executor,
    mediaIds: readonly string[],
  ): Promise<DiffObjectRow[]> {
    if (mediaIds.length === 0) return [];
    return trx
      .selectFrom('objects')
      .selectAll()
      .where('state', '=', 'uploaded')
      .where(sql<boolean>`media_id = any(${[...mediaIds]}::text[])`)
      .orderBy('id')
      .execute();
  },
};
