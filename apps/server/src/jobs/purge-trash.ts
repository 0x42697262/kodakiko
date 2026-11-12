import { entriesRepository } from '../adapters/db/entries-repository.ts';
import { JOB_BATCH_SIZE, JOB_TIMEOUT_MS } from '../config/constants.ts';
import type { Settings } from '../config/settings.ts';
import { trashCutoff } from '../domain/entries.ts';
import type { EventLogger } from '../platform/event-logger.ts';
import type { EntriesService } from '../services/entries-service.ts';
import { LOG_EVENTS } from '../text/events.ts';
import { JOB_LOCK_IDS } from './keys.ts';
import type { JobDefinition } from './scheduler.ts';

const JOB_NAME = 'purge-trash';

export function purgeTrashJob(
  settings: Settings,
  entriesService: EntriesService,
  logger: EventLogger,
): JobDefinition {
  return {
    name: JOB_NAME,
    lockId: JOB_LOCK_IDS.purgeTrash,
    intervalMs: settings.jobs.purgeTrashIntervalMs,
    timeoutMs: JOB_TIMEOUT_MS,
    run: async ({ db, now, signal }) => {
      const cutoff = trashCutoff(now, settings.entries.trashDays);
      const ids = await entriesRepository.selectTrashedBefore(db, cutoff, JOB_BATCH_SIZE);
      let processed = 0;
      for (const id of ids) {
        if (signal.aborted) break;
        const removed = await entriesService.removeAsSystem(id, { onlyIfTrashedBefore: cutoff });
        if (removed) processed += 1;
      }
      if (processed > 0) logger.info({ event: LOG_EVENTS.entryPurged, count: processed });
      return { processed, remaining: ids.length === JOB_BATCH_SIZE };
    },
  };
}
