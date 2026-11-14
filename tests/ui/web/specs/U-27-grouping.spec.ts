import { expect, signInThroughUi, test } from '../../support/fixtures.ts';
import { seedAccount, seedEntries, type SeededAccount } from '../../support/seed.ts';
import { uiUsername, type UiProject } from '../../support/usernames.ts';

const SYNC_WAIT_MS = 60_000;

let account: SeededAccount;

test.beforeAll(async ({}, testInfo) => {
  test.skip(testInfo.project.name !== 'desktop', 'U-27 runs in the desktop project only.');
  account = await seedAccount({ username: uiUsername('U-27', testInfo.project.name as UiProject, 0) });
  await seedEntries(account, [
    { takenAtUtc: '2026-07-01T08:00:00.000Z', offsetMinutes: 0, note: '', tags: [], media: ['photo-gps.jpg'] },
    { takenAtUtc: '2026-07-20T08:00:00.000Z', offsetMinutes: 0, note: '', tags: [], media: ['photo-portrait.jpg'] },
    { takenAtUtc: '2026-06-05T08:00:00.000Z', offsetMinutes: 0, note: '', tags: [], media: ['photo-portrait.jpg'] },
  ]);
});

test('U-27 grouping by month shows one header per month', async ({ page }) => {
  await signInThroughUi(page, account);
  await expect(page.getByTestId(/^gallery\.tile\.\d+$/)).toHaveCount(3, { timeout: SYNC_WAIT_MS });
  await expect(page.getByTestId(/^gallery\.dayHeader\.\d+$/)).toHaveCount(3);
  await page.getByTestId('gallery.filter').click();
  await page.getByTestId('sheet.tab.group').click();
  await page.getByTestId('sheet.group.month').click();
  await page.keyboard.press('Escape');
  await expect(page.getByTestId(/^gallery\.dayHeader\.\d+$/)).toHaveCount(2);
  await expect(page.getByTestId('gallery.dayHeader.0')).toHaveText('July 2026');
  await expect(page.getByTestId('gallery.dayHeader.1')).toHaveText('June 2026');
});
