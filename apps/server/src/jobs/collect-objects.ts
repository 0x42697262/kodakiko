import { objectsRepository, type ObjectRow } from '../adapters/db/objects-repository.ts';
import type { ObjectStore } from '../adapters/storage/object-store.ts';
import { JOB_BATCH_SIZE, JOB_TIMEOUT_MS } from '../config/constants.ts';
import type { Settings } from '../config/settings.ts';
import type { EventLogger } from '../platform/event-logger.ts';
import { LOG_EVENTS } from '../text/events.ts';
import { JOB_LOCK_IDS } from './keys.ts';
import type { JobDefinition } from './scheduler.ts';

const JOB_NAME = 'collect-objects';

function hasOpenMultipartUpload(row: ObjectRow): row is ObjectRow & { multipartUploadId: string } {
  return row.multipartUploadId !== null && row.uploadedAt === null;
}

export function collectObjectsJob(
  settings: Settings,
  store: ObjectStore,
  logger: EventLogger,
): JobDefinition {
  return {
    name: JOB_NAME,
    lockId: JOB_LOCK_IDS.collectObjects,
    intervalMs: settings.jobs.collectObjectsIntervalMs,
    timeoutMs: JOB_TIMEOUT_MS,
    run: async ({ db, now, signal }) => {
      const collectable = await objectsRepository.selectCollectable(db, now, JOB_BATCH_SIZE);
      let processed = 0;
      for (const row of collectable) {
        if (signal.aborted) break;
        try {
          if (hasOpenMultipartUpload(row)) {
            await store.abortMultipartUpload(row.objectKey, row.multipartUploadId);
          }
          await store.deleteObject(row.objectKey);
        } catch (error) {
          logger.warn({
            event: LOG_EVENTS.objectDeleteFailed,
            objectId: row.id,
            message: error instanceof Error ? error.message : String(error),
          });
          continue;
        }
        const removed = await db.transaction().execute(async (trx) => {
          const sizeBytes = await objectsRepository.deleteRow(trx, row.id);
          if (sizeBytes !== undefined) {
            await objectsRepository.adjustUsedBytes(trx, row.ownerId, -sizeBytes);
          }
          return sizeBytes;
        });
        if (removed !== undefined) processed += 1;
      }
      return { processed, remaining: processed === JOB_BATCH_SIZE };
    },
  };
}
