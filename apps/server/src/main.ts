import { createDatabase, databaseCheck } from './adapters/db/database.ts';
import { migrateToLatest } from './adapters/db/migrator.ts';
import { createS3ObjectStore } from './adapters/storage/s3-object-store.ts';
import { buildApp } from './app.ts';
import { EXIT_CODES } from './config/constants.ts';
import { loadSettings } from './config/settings.ts';
import { SettingsError } from './domain/errors.ts';
import { objectsRepository } from './adapters/db/objects-repository.ts';
import { abortStaleUploadsJob } from './jobs/abort-stale-uploads.ts';
import { collectObjectsJob } from './jobs/collect-objects.ts';
import { eraseAccountsJob } from './jobs/erase-accounts.ts';
import { expireSessionsJob } from './jobs/expire-sessions.ts';
import { pruneTombstonesJob } from './jobs/prune-tombstones.ts';
import { expirePublicLinksJob } from './jobs/expire-public-links.ts';
import { purgeTrashJob } from './jobs/purge-trash.ts';
import { reconcileUsageJob } from './jobs/reconcile-usage.ts';
import { createScheduler } from './jobs/scheduler.ts';
import { systemClock as clock } from './platform/clock.ts';
import { exitWith, fatalLine, onShutdownSignal } from './platform/process.ts';
import { createEntriesService } from './services/entries-service.ts';
import { bucketCheck } from './services/readiness.ts';
import { createSequenceService } from './services/sequence-service.ts';
import { LOG_EVENTS } from './text/events.ts';

try {
  const settings = loadSettings(process.env);
  const logPoolError = (error: Error): void =>
    app.log.error({ event: LOG_EVENTS.dbPoolError, message: error.message });
  const db = createDatabase(settings.database, logPoolError);
  await migrateToLatest(db).catch((error: unknown) => {
    exitWith(EXIT_CODES.failure, fatalLine(LOG_EVENTS.dbMigrationFailed, error));
  });
  const objectStore = createS3ObjectStore(settings.storage);
  const readinessChecks = [databaseCheck(db), bucketCheck(objectStore)];
  const app = await buildApp({ settings, db, clock, readinessChecks, objectStore });
  const enabled = settings.jobs.enabled;
  const entriesService = createEntriesService({
    db,
    settings,
    clock,
    objectsRepository,
    sequenceService: createSequenceService(),
    logger: app.log,
  });
  const jobs = [
    expireSessionsJob(settings),
    collectObjectsJob(settings, objectStore, app.log),
    abortStaleUploadsJob(settings, objectStore, app.log),
    reconcileUsageJob(settings, app.log),
    purgeTrashJob(settings, entriesService, app.log),
    eraseAccountsJob(settings, app.log),
    pruneTombstonesJob(settings),
    expirePublicLinksJob(settings, objectStore, app.log),
  ];
  const scheduler = createScheduler({ db, clock, logger: app.log, jobs, enabled });
  scheduler.start();
  const { port, host, version, shutdownTimeoutMs } = settings.server;
  await app.listen({ port, host });
  app.log.info({ event: LOG_EVENTS.serverStarted, port, version });
  onShutdownSignal(shutdownTimeoutMs, async () => {
    app.log.info({ event: LOG_EVENTS.serverStopping });
    await scheduler.stop();
    await app.close();
    await db.destroy();
  });
} catch (error) {
  if (!(error instanceof SettingsError)) throw error;
  exitWith(EXIT_CODES.failure, error.message);
}
