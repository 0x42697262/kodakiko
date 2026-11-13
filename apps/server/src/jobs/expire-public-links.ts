import type { FastifyBaseLogger } from 'fastify';
import { publicLinksRepository } from '../adapters/db/public-links-repository.ts';
import type { ObjectStore } from '../adapters/storage/object-store.ts';
import { JOB_BATCH_SIZE, JOB_TIMEOUT_MS, ONE_VIEW_RETENTION_MS } from '../config/constants.ts';
import type { Settings } from '../config/settings.ts';
import { createPublicLinksService } from '../services/public-links-service.ts';
import { LOG_EVENTS } from '../text/events.ts';
import { JOB_LOCK_IDS } from './keys.ts';
import type { JobDefinition } from './scheduler.ts';

const JOB_NAME = 'expire-public-links';

export function expirePublicLinksJob(settings: Settings, store: ObjectStore, logger: FastifyBaseLogger): JobDefinition {
  return {
    name: JOB_NAME,
    lockId: JOB_LOCK_IDS.expirePublicLinks,
    intervalMs: settings.jobs.expirePublicLinksIntervalMs,
    timeoutMs: JOB_TIMEOUT_MS,
    run: async ({ db, now, signal }) => {
      const service = createPublicLinksService({
        db,
        settings,
        clock: { now: () => now },
        store,
        logger,
        proxyPath: () => '',
      });
      const oneViewCutoff = new Date(now.getTime() - ONE_VIEW_RETENTION_MS);
      const collectionIds = await publicLinksRepository.selectExpiredCollections(db, now, oneViewCutoff, JOB_BATCH_SIZE);
      let processed = 0;
      for (const collectionId of collectionIds) {
        if (signal.aborted) break;
        await db.transaction().execute((trx) => service.deleteLinkCollection(trx, collectionId, now));
        processed += 1;
      }
      if (processed > 0) logger.info({ event: LOG_EVENTS.linkExpired, count: processed });
      return { processed, remaining: collectionIds.length === JOB_BATCH_SIZE };
    },
  };
}
