import { DAY_MS } from './constants.ts';
import type { Harness } from './harness.ts';
import { pruneTombstonesJob } from '@kodakiko/server/testing';

const ALLOCATION_STEP = 1;

export type ServerEntryVersion = { readonly revision: number; readonly metadataBlob: Buffer };

export async function serverEntry(harness: Harness, entryId: string): Promise<ServerEntryVersion> {
  return harness.db
    .selectFrom('entries')
    .select(['revision', 'metadataBlob'])
    .where('id', '=', entryId)
    .executeTakeFirstOrThrow();
}

export async function setQuota(
  harness: Harness,
  userId: string,
  quotaBytes: number | null,
): Promise<void> {
  await harness.db.updateTable('users').set({ quotaBytes }).where('id', '=', userId).execute();
}

export async function rewindEntry(
  harness: Harness,
  entryId: string,
  collectionId: string,
  older: ServerEntryVersion,
): Promise<void> {
  await harness.db
    .updateTable('entries')
    .set({ revision: older.revision, metadataBlob: older.metadataBlob })
    .where('id', '=', entryId)
    .execute();
  const allocated = await harness.db
    .updateTable('collections')
    .set((builder) => ({ lastSeq: builder('lastSeq', '+', ALLOCATION_STEP) }))
    .where('id', '=', collectionId)
    .returning('lastSeq')
    .executeTakeFirstOrThrow();
  await harness.db
    .updateTable('entryMemberships')
    .set({ seq: allocated.lastSeq })
    .where('collectionId', '=', collectionId)
    .where('entryId', '=', entryId)
    .execute();
}

export async function pruneTombstonesWithMovedClock(harness: Harness): Promise<void> {
  const retentionDays = harness.settings.sync.tombstoneRetentionDays;
  await pruneTombstonesJob(harness.settings).run({
    db: harness.db,
    now: new Date(Date.now() + (retentionDays + 1) * DAY_MS),
    signal: new AbortController().signal,
  });
}
