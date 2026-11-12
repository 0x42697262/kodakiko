import { objectsRepository } from '../adapters/db/objects-repository.ts';
import type { ObjectStore } from '../adapters/storage/object-store.ts';
import { JOB_BATCH_SIZE, JOB_TIMEOUT_MS } from '../config/constants.ts';
import type { Settings } from '../config/settings.ts';
import { objectDeleteAfter, staleUploadCutoff } from '../domain/objects.ts';
import type { EventLogger } from '../platform/event-logger.ts';
import { LOG_EVENTS } from '../text/events.ts';
import { JOB_LOCK_IDS } from './keys.ts';
import type { JobDefinition } from './scheduler.ts';

const JOB_NAME = 'abort-stale-uploads';

export function abortStaleUploadsJob(
  settings: Settings,
  store: ObjectStore,
  logger: EventLogger,
): JobDefinition {
  const { storage } = settings;
  return {
    name: JOB_NAME,
    lockId: JOB_LOCK_IDS.abortStaleUploads,
    intervalMs: settings.jobs.abortStaleUploadsIntervalMs,
    timeoutMs: JOB_TIMEOUT_MS,
    run: async ({ db, now, signal }) => {
      const cutoff = staleUploadCutoff(now, storage.pendingUploadHours);
      const stale = await objectsRepository.selectStalePending(db, cutoff, JOB_BATCH_SIZE);
      const deleteAfter = objectDeleteAfter(now, storage);
      let processed = 0;
      for (const row of stale) {
        if (signal.aborted) break;
        const claimed = await db
          .transaction()
          .execute((trx) => objectsRepository.markPendingDeleting(trx, row.id, deleteAfter));
        if (!claimed) continue;
        processed += 1;
        if (row.multipartUploadId === null) continue;
        try {
          await store.abortMultipartUpload(row.objectKey, row.multipartUploadId);
        } catch (error) {
          logger.warn({
            event: LOG_EVENTS.objectAbortFailed,
            objectId: row.id,
            message: error instanceof Error ? error.message : String(error),
          });
        }
      }
      return { processed, remaining: processed === JOB_BATCH_SIZE };
    },
  };
}
