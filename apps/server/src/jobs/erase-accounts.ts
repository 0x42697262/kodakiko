import { objectsRepository } from '../adapters/db/objects-repository.ts';
import { createSessionsRepository } from '../adapters/db/sessions-repository.ts';
import { createUsersRepository } from '../adapters/db/users-repository.ts';
import { JOB_BATCH_SIZE, JOB_TIMEOUT_MS } from '../config/constants.ts';
import type { Settings } from '../config/settings.ts';
import type { EventLogger } from '../platform/event-logger.ts';
import { LOG_EVENTS } from '../text/events.ts';
import { JOB_LOCK_IDS } from './keys.ts';
import type { JobDefinition } from './scheduler.ts';

const JOB_NAME = 'erase-accounts';

export function eraseAccountsJob(settings: Settings, logger: EventLogger): JobDefinition {
  return {
    name: JOB_NAME,
    lockId: JOB_LOCK_IDS.eraseAccounts,
    intervalMs: settings.jobs.eraseAccountsIntervalMs,
    timeoutMs: JOB_TIMEOUT_MS,
    run: async ({ db, now, signal }) => {
      const due = await createUsersRepository(db).selectDueForErasure(now, JOB_BATCH_SIZE);
      let processed = 0;
      for (const candidate of due) {
        if (signal.aborted) break;
        const erased = await db.transaction().execute(async (trx) => {
          const users = createUsersRepository(trx);
          const user = await users.findByIdForUpdate(candidate.id);
          const stillDue =
            user?.status === 'pending_deletion' &&
            user.deletionDueAt !== null &&
            user.deletionDueAt < now;
          if (!stillDue) return false;
          await createSessionsRepository(trx).deleteAllForUser(candidate.id);
          await objectsRepository.markDeletingByOwner(trx, candidate.id, now, JOB_BATCH_SIZE);
          if ((await objectsRepository.countByOwner(trx, candidate.id)) > 0) return false;
          await users.deleteById(candidate.id);
          return true;
        });
        if (!erased) continue;
        processed += 1;
        logger.info({ event: LOG_EVENTS.accountErased, userId: candidate.id });
      }
      return { processed, remaining: due.length === JOB_BATCH_SIZE };
    },
  };
}
