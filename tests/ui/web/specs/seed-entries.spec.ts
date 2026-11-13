import { expect, test } from '../../support/fixtures.ts';
import { readServerJournal } from '../../support/server-state.ts';
import { seedAccount, seedEntries, type SeededAccount } from '../../support/seed.ts';
import { uiUsername, type UiProject } from '../../support/usernames.ts';

const SEED_ENTRIES = [
  {
    takenAtUtc: '2026-03-03T09:00:00.000Z',
    offsetMinutes: 60,
    note: 'Walk to the lake',
    tags: ['lake', 'walk'],
    media: ['photo-gps.jpg', 'photo-portrait.jpg'],
  },
  {
    takenAtUtc: '2026-03-05T18:30:00.000Z',
    offsetMinutes: -300,
    note: 'Dinner',
    tags: [],
    media: ['screenshot.png'],
  },
] as const;

const SERVER_WAIT_MS = 60_000;

let account: SeededAccount;

test.beforeAll(async ({}, testInfo) => {
  account = await seedAccount({
    username: uiUsername('X-10-seed', testInfo.project.name as UiProject, 0),
  });
});

test('seedEntries creates the entries through the real service and syncs every object', async () => {
  const seeded = await seedEntries(account, SEED_ENTRIES);

  expect(seeded).toHaveLength(2);
  expect(new Set(seeded.map((item) => item.entryId)).size).toBe(2);
  await expect
    .poll(() => readServerJournal(account), { timeout: SERVER_WAIT_MS })
    .toEqual({ entryCount: 2, trashedCount: 0, mediaCount: 3, objectCount: 6 });
});
