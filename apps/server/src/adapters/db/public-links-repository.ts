import { sql, type Kysely, type Selectable } from 'kysely';
import type { Database, PublicLinksTable } from './schema.ts';

type Executor = Kysely<Database>;

export type PublicLinkRow = Selectable<PublicLinksTable>;

export const publicLinksRepository = {
  async insert(executor: Executor, row: Omit<PublicLinkRow, 'viewCount'>): Promise<PublicLinkRow> {
    return executor.insertInto('publicLinks').values(row).returningAll().executeTakeFirstOrThrow();
  },

  findById(executor: Executor, id: string): Promise<PublicLinkRow | undefined> {
    return executor.selectFrom('publicLinks').selectAll().where('id', '=', id).executeTakeFirst();
  },

  findByCollection(executor: Executor, collectionId: string): Promise<PublicLinkRow | undefined> {
    return executor.selectFrom('publicLinks').selectAll().where('collectionId', '=', collectionId).executeTakeFirst();
  },

  listByOwner(executor: Executor, ownerId: string): Promise<PublicLinkRow[]> {
    return executor
      .selectFrom('publicLinks')
      .innerJoin('collections', 'collections.id', 'publicLinks.collectionId')
      .selectAll('publicLinks')
      .where('collections.ownerId', '=', ownerId)
      .orderBy('publicLinks.createdAt', 'desc')
      .execute();
  },

  async countByOwner(executor: Executor, ownerId: string): Promise<number> {
    const row = await executor
      .selectFrom('publicLinks')
      .innerJoin('collections', 'collections.id', 'publicLinks.collectionId')
      .select((builder) => builder.fn.countAll<string>().as('count'))
      .where('collections.ownerId', '=', ownerId)
      .executeTakeFirstOrThrow();
    return Number(row.count);
  },

  async countView(executor: Executor, id: string): Promise<boolean> {
    const result = await sql<{ viewCount: number }>`
      update public_links
      set view_count = view_count + 1
      where id = ${id} and (max_views is null or view_count < max_views)
      returning view_count`.execute(executor);
    return result.rows.length === 1;
  },

  async selectExpiredCollections(executor: Executor, now: Date, oneViewCutoff: Date, limit: number): Promise<string[]> {
    const rows = await executor
      .selectFrom('publicLinks')
      .select('collectionId')
      .where((builder) =>
        builder.or([
          builder('expiresAt', '<', now),
          builder.and([
            builder('maxViews', 'is not', null),
            builder('viewCount', '>=', builder.ref('maxViews')),
            builder('createdAt', '<', oneViewCutoff),
          ]),
        ]),
      )
      .limit(limit)
      .execute();
    return rows.map((row) => row.collectionId);
  },
};
