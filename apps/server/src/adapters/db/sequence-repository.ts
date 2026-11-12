import type { Transaction } from 'kysely';
import { sortedForLocking } from '../../domain/sync.ts';
import type { Database } from './schema.ts';

export const sequenceRepository = {
  async allocate(
    trx: Transaction<Database>,
    collectionIds: readonly string[],
    now: Date,
  ): Promise<Map<string, number> | undefined> {
    const seqByCollection = new Map<string, number>();
    for (const collectionId of sortedForLocking(collectionIds)) {
      const row = await trx
        .updateTable('collections')
        .set((builder) => ({ lastSeq: builder('lastSeq', '+', 1), updatedAt: now }))
        .where('id', '=', collectionId)
        .returning('lastSeq')
        .executeTakeFirst();
      if (row === undefined) return undefined;
      seqByCollection.set(collectionId, row.lastSeq);
    }
    return seqByCollection;
  },
};
