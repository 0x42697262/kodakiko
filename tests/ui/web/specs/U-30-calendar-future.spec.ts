import { expect, signInThroughUi, test } from '../../support/fixtures.ts';
import { seedAccount, seedEntries, type SeededAccount } from '../../support/seed.ts';
import { uiUsername, type UiProject } from '../../support/usernames.ts';

const SYNC_WAIT_MS = 60_000;
const DAY_OF_MONTH = 10;
const MONTHS_AHEAD = 2;
const MONTH_NAMES = ['January', 'February', 'March', 'April', 'May', 'June', 'July', 'August', 'September', 'October', 'November', 'December'];

function monthsFromNow(offset: number): Date {
  const now = new Date();
  return new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth() + offset, DAY_OF_MONTH, 12));
}

function yearMonth(date: Date): string {
  return date.toISOString().slice(0, 7);
}

let account: SeededAccount;

test.beforeAll(async ({}, testInfo) => {
  test.skip(testInfo.project.name !== 'desktop', 'U-30 runs in the desktop project only.');
  account = await seedAccount({ username: uiUsername('U-30', testInfo.project.name as UiProject, 0) });
  await seedEntries(account, [
    { takenAtUtc: monthsFromNow(0).toISOString(), offsetMinutes: 0, note: 'now', tags: [], media: ['photo-gps.jpg'] },
    { takenAtUtc: monthsFromNow(MONTHS_AHEAD).toISOString(), offsetMinutes: 0, note: 'later', tags: [], media: ['photo-portrait.jpg'] },
  ]);
});

test('U-30 the calendar skips empty future months, stops after the last one, and refuses jumps to empty future months', async ({ page }) => {
  const later = monthsFromNow(MONTHS_AHEAD);
  await signInThroughUi(page, account);
  await expect(page.getByTestId(/^gallery\.tile\.\d+$/)).toHaveCount(2, { timeout: SYNC_WAIT_MS });
  await page.getByTestId('tabs.calendar').click();
  await page.getByTestId('calendar.next').click();
  await expect(page.getByTestId('calendar.monthTitle')).toContainText(MONTH_NAMES[later.getUTCMonth()] ?? '');
  await expect(page.getByTestId('calendar.next')).toBeDisabled();
  await page.getByTestId('calendar.jump').click();
  await page.getByTestId('calendar.jumpField').fill(yearMonth(monthsFromNow(1)));
  await page.getByTestId('calendar.jumpField').press('Enter');
  await expect(page.getByTestId('calendar.jumpInvalid')).toHaveText('No entries in that month yet.');
});
