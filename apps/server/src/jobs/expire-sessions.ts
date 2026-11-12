import { createSessionsRepository } from '../adapters/db/sessions-repository.ts';
import { JOB_BATCH_SIZE, JOB_TIMEOUT_MS } from '../config/constants.ts';
import type { Settings } from '../config/settings.ts';
import { JOB_LOCK_IDS } from './keys.ts';
import type { JobDefinition } from './scheduler.ts';

const JOB_NAME = 'expire-sessions';

export function expireSessionsJob(settings: Settings): JobDefinition {
  return {
    name: JOB_NAME,
    lockId: JOB_LOCK_IDS.expireSessions,
    intervalMs: settings.jobs.expireSessionsIntervalMs,
    timeoutMs: JOB_TIMEOUT_MS,
    run: async ({ db, now }) => {
      const deleted = await createSessionsRepository(db).deleteExpired(now, JOB_BATCH_SIZE);
      return { processed: deleted, remaining: deleted === JOB_BATCH_SIZE };
    },
  };
}
