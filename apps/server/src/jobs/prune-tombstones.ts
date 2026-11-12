import { entriesRepository } from '../adapters/db/entries-repository.ts';
import { JOB_BATCH_SIZE, JOB_TIMEOUT_MS } from '../config/constants.ts';
import type { Settings } from '../config/settings.ts';
import { tombstoneCutoff } from '../domain/entries.ts';
import { JOB_LOCK_IDS } from './keys.ts';
import type { JobDefinition } from './scheduler.ts';

const JOB_NAME = 'prune-tombstones';

export function pruneTombstonesJob(settings: Settings): JobDefinition {
  return {
    name: JOB_NAME,
    lockId: JOB_LOCK_IDS.pruneTombstones,
    intervalMs: settings.jobs.pruneTombstonesIntervalMs,
    timeoutMs: JOB_TIMEOUT_MS,
    run: async ({ db, now, signal }) => {
      const cutoff = tombstoneCutoff(now, settings.sync.tombstoneRetentionDays);
      const collectionIds = await entriesRepository.selectCollectionsWithTombstonesBefore(
        db,
        cutoff,
        JOB_BATCH_SIZE,
      );
      let processed = 0;
      for (const collectionId of collectionIds) {
        if (signal.aborted || processed >= JOB_BATCH_SIZE) break;
        const budget = JOB_BATCH_SIZE - processed;
        const deleted = await db.transaction().execute(async (trx) => {
          const result = await entriesRepository.deleteTombstonesBefore(
            trx,
            collectionId,
            cutoff,
            budget,
          );
          if (result.count > 0) {
            await entriesRepository.raisePrunedThroughSeq(trx, collectionId, result.maxSeq);
          }
          return result.count;
        });
        processed += deleted;
      }
      return { processed, remaining: processed >= JOB_BATCH_SIZE };
    },
  };
}
