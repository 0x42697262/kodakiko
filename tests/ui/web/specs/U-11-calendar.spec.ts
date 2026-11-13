import { calendarDayId, calendarEntries } from '../../support/calendar-entries.ts';
import { expect, signInThroughUi, test } from '../../support/fixtures.ts';
import { seedAccount, seedEntries, type SeededAccount } from '../../support/seed.ts';
import { uiUsername, type UiProject } from '../../support/usernames.ts';

const TILES = /^gallery\.tile\.\d+$/;
const SYNC_WAIT_MS = 60_000;
const SEVENTEENTH = 17;

let account: SeededAccount;
let seededAt: number;

test.beforeAll(async ({}, testInfo) => {
  account = await seedAccount({
    username: uiUsername('U-11', testInfo.project.name as UiProject, 0),
  });
  seededAt = Date.now();
  await seedEntries(account, calendarEntries(seededAt));
});

test('U-11 the calendar shows days with entries and opens a day', async ({ page }) => {
  await signInThroughUi(page, account);
  await expect(page.getByTestId(TILES)).toHaveCount(2, { timeout: SYNC_WAIT_MS });

  await page.getByTestId('tabs.calendar').click();
  const day = page.getByTestId(calendarDayId(seededAt, SEVENTEENTH));
  await expect(day).toBeVisible();
  await expect(day).toHaveAttribute('aria-label', /, 1 entry$/);
  await day.click();

  await expect(page.getByTestId('day.screen')).toBeVisible();
  await expect(page.getByTestId('gallery.tile.0')).toBeVisible();
  await expect(page.getByTestId(TILES)).toHaveCount(1);
});
