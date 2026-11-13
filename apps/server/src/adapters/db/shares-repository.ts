import type { Kysely } from 'kysely';
import { PG_UNIQUE_VIOLATION } from '../../config/constants.ts';
import type { Database } from './schema.ts';

type Executor = Kysely<Database>;

export type ShareRow = {
  readonly collectionId: string;
  readonly recipientId: string;
  readonly sealedCollectionKey: Buffer;
  readonly createdAt: Date;
};

export type ShareListItem = {
  readonly recipientUserId: string;
  readonly recipientUsername: string;
  readonly createdAt: Date;
};

export type InsertShareResult = 'inserted' | 'alreadyShared';

export const sharesRepository = {
  async insert(executor: Executor, row: ShareRow): Promise<InsertShareResult> {
    try {
      await executor.insertInto('collectionShares').values(row).execute();
      return 'inserted';
    } catch (error) {
      if ((error as { readonly code?: string }).code === PG_UNIQUE_VIOLATION) return 'alreadyShared';
      throw error;
    }
  },

  find(executor: Executor, collectionId: string, recipientId: string): Promise<ShareRow | undefined> {
    return executor
      .selectFrom('collectionShares')
      .selectAll()
      .where('collectionId', '=', collectionId)
      .where('recipientId', '=', recipientId)
      .executeTakeFirst();
  },

  async list(executor: Executor, collectionId: string): Promise<ShareListItem[]> {
    return executor
      .selectFrom('collectionShares')
      .innerJoin('users', 'users.id', 'collectionShares.recipientId')
      .select([
        'collectionShares.recipientId as recipientUserId',
        'users.username as recipientUsername',
        'collectionShares.createdAt',
      ])
      .where('collectionShares.collectionId', '=', collectionId)
      .orderBy('collectionShares.createdAt')
      .execute();
  },

  async delete(executor: Executor, collectionId: string, recipientId: string): Promise<void> {
    await executor
      .deleteFrom('collectionShares')
      .where('collectionId', '=', collectionId)
      .where('recipientId', '=', recipientId)
      .execute();
  },

  async count(executor: Executor, collectionId: string): Promise<number> {
    const row = await executor
      .selectFrom('collectionShares')
      .select((builder) => builder.fn.countAll<string>().as('count'))
      .where('collectionId', '=', collectionId)
      .executeTakeFirstOrThrow();
    return Number(row.count);
  },
};
