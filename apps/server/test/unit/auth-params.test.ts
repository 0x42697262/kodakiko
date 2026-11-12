import { describe, expect, it } from 'vitest';
import type { Kysely } from 'kysely';
import type { Database } from '../../src/adapters/db/schema.ts';
import { createFailedSignInTracker } from '../../src/adapters/memory/failed-sign-in-tracker.ts';
import { systemClock } from '../../src/platform/clock.ts';
import { silentLogger } from '../../src/platform/silent-logger.ts';
import { createAuthService } from '../../src/services/auth-service.ts';
import { testSettings } from '../integration/helpers.ts';

const QUERIED = 'the database was queried';
const WINDOW_MS = 60_000;

const throwingDatabase = new Proxy(
  {},
  {
    get: () => {
      throw new Error(QUERIED);
    },
  },
) as Kysely<Database>;

function service() {
  return createAuthService({
    db: throwingDatabase,
    settings: testSettings(),
    clock: systemClock,
    failedSignIns: createFailedSignInTracker({ windowMs: WINDOW_MS, maxEntries: 10 }),
    logger: silentLogger,
    checkOpenSignUpRate: async () => true,
  });
}

describe('params for malformed usernames', () => {
  it('answers fake params without a database query', async () => {
    for (const name of ['Not A Valid Name!', 'ab', 'x'.repeat(40), 'has space']) {
      const result = await service().params(name);
      expect(result.salt).toHaveLength(16);
      expect(result.opsLimit).toBe(16);
      expect(result.memLimitBytes).toBe(268_435_456);
    }
  });

  it('still queries the database for a well-formed username', async () => {
    await expect(service().params('well-formed')).rejects.toThrow(QUERIED);
  });
});
