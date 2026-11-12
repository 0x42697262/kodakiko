import type { FastifyInstance } from 'fastify';
import { buildApp } from '../../src/app.ts';
import { createDatabase } from '../../src/adapters/db/database.ts';
import type { ReadinessCheck } from '../../src/services/readiness.ts';
import { systemClock } from '../../src/platform/clock.ts';
import type { Settings } from '../../src/config/settings.ts';
import { ignorePoolError, testSettings } from '../integration/helpers.ts';

export async function buildUnitApp(
  settings: Settings = testSettings(),
  readinessChecks: readonly ReadinessCheck[] = [],
): Promise<FastifyInstance> {
  const db = createDatabase(settings.database, ignorePoolError);
  return buildApp({ settings, db, clock: systemClock, readinessChecks });
}
