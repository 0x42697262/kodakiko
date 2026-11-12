import type { Transaction } from 'kysely';
import { entriesRepository } from '../adapters/db/entries-repository.ts';
import type { Database } from '../adapters/db/schema.ts';
import { sequenceRepository } from '../adapters/db/sequence-repository.ts';
import { AppError } from '../domain/errors.ts';

export async function allocateSeqs(
  trx: Transaction<Database>,
  collectionIds: readonly string[],
  now: Date,
): Promise<Map<string, number>> {
  const seqByCollection = await sequenceRepository.allocate(trx, collectionIds, now);
  if (seqByCollection === undefined) throw new AppError('NOT_FOUND');
  return seqByCollection;
}

export function createSequenceService() {
  async function bumpEntry(trx: Transaction<Database>, entryId: string, now: Date): Promise<void> {
    const collectionIds = await entriesRepository.membershipCollectionIds(trx, entryId);
    const seqByCollection = await allocateSeqs(trx, collectionIds, now);
    await entriesRepository.updateMembershipSeqs(trx, entryId, seqByCollection);
  }

  return { bumpEntry, allocate: allocateSeqs };
}

export type SequenceService = ReturnType<typeof createSequenceService>;
