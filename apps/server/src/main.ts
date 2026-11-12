import { createDatabase, databaseCheck } from './adapters/db/database.ts';
import { migrateToLatest } from './adapters/db/migrator.ts';
import { buildApp } from './app.ts';
import { EXIT_CODES } from './config/constants.ts';
import { loadSettings } from './config/settings.ts';
import { SettingsError } from './domain/errors.ts';
import { expireSessionsJob } from './jobs/expire-sessions.ts';
import { createScheduler } from './jobs/scheduler.ts';
import { systemClock as clock } from './platform/clock.ts';
import { exitWith, fatalLine, onShutdownSignal } from './platform/process.ts';
import { LOG_EVENTS } from './text/events.ts';

try {
  const settings = loadSettings(process.env);
  const logPoolError = (error: Error): void =>
    app.log.error({ event: LOG_EVENTS.dbPoolError, message: error.message });
  const db = createDatabase(settings.database, logPoolError);
  await migrateToLatest(db).catch((error: unknown) => {
    exitWith(EXIT_CODES.failure, fatalLine(LOG_EVENTS.dbMigrationFailed, error));
  });
  const readinessChecks = [databaseCheck(db)];
  const app = await buildApp({ settings, db, clock, readinessChecks });
  const enabled = settings.jobs.enabled;
  const jobs = [expireSessionsJob(settings)];
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
