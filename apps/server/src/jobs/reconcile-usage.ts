import { objectsRepository } from '../adapters/db/objects-repository.ts';
import { createUsersRepository } from '../adapters/db/users-repository.ts';
import { JOB_TIMEOUT_MS } from '../config/constants.ts';
import type { Settings } from '../config/settings.ts';
import type { EventLogger } from '../platform/event-logger.ts';
import { LOG_EVENTS } from '../text/events.ts';
import { JOB_LOCK_IDS } from './keys.ts';
import type { JobDefinition } from './scheduler.ts';

const JOB_NAME = 'reconcile-usage';

export function reconcileUsageJob(settings: Settings, logger: EventLogger): JobDefinition {
  return {
    name: JOB_NAME,
    lockId: JOB_LOCK_IDS.reconcileUsage,
    intervalMs: settings.jobs.reconcileUsageIntervalMs,
    timeoutMs: JOB_TIMEOUT_MS,
    run: async ({ db, signal }) => {
      const candidates = await objectsRepository.sumSizesByOwner(db);
      let processed = 0;
      for (const candidate of candidates) {
        if (signal.aborted) break;
        if (candidate.usedBytes === candidate.sumBytes) continue;
        const corrected = await db.transaction().execute(async (trx) => {
          const user = await createUsersRepository(trx).findByIdForUpdate(candidate.ownerId);
          if (user === undefined) return false;
          const sumBytes = await objectsRepository.sumSizeOfOwner(trx, candidate.ownerId);
          if (user.usedBytes === sumBytes) return false;
          await objectsRepository.setUsedBytes(trx, candidate.ownerId, sumBytes);
          logger.info({
            event: LOG_EVENTS.usageReconciled,
            userId: candidate.ownerId,
            oldBytes: user.usedBytes,
            newBytes: sumBytes,
          });
          return true;
        });
        if (corrected) processed += 1;
      }
      return { processed, remaining: false };
    },
  };
}
