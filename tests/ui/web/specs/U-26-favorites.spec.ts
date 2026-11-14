import { expect, signInThroughUi, test } from '../../support/fixtures.ts';
import { seedAccount, seedEntries, type SeededAccount } from '../../support/seed.ts';
import { uiUsername, type UiProject } from '../../support/usernames.ts';

const SYNC_WAIT_MS = 60_000;

let account: SeededAccount;

test.beforeAll(async ({}, testInfo) => {
  test.skip(testInfo.project.name !== 'desktop', 'U-26 runs in the desktop project only.');
  account = await seedAccount({ username: uiUsername('U-26', testInfo.project.name as UiProject, 0) });
  await seedEntries(account, [
    { takenAtUtc: '2026-08-01T08:00:00.000Z', offsetMinutes: 0, note: 'keep', tags: [], media: ['photo-gps.jpg'] },
    { takenAtUtc: '2026-08-02T08:00:00.000Z', offsetMinutes: 0, note: 'other', tags: [], media: ['photo-portrait.jpg'] },
  ]);
});

test('U-26 a favorite shows under the Favorites tab and the filter can hide it', async ({ page }) => {
  await signInThroughUi(page, account);
  await expect(page.getByTestId(/^gallery\.tile\.\d+$/)).toHaveCount(2, { timeout: SYNC_WAIT_MS });
  await page.getByTestId('gallery.tile.1').click();
  await page.getByTestId('viewer.favorite').click();
  await expect(page.getByTestId('viewer.favorite')).toContainText('Favorited');
  await page.getByTestId('viewer.back').click();
  await page.getByTestId('gallery.tagTab.favorites').click();
  await expect(page.getByTestId(/^gallery\.tile\.\d+$/)).toHaveCount(1);
  await page.getByTestId('gallery.tagTab.all').click();
  await expect(page.getByTestId(/^gallery\.tile\.\d+$/)).toHaveCount(2);
  await page.getByTestId('gallery.filter').click();
  await page.getByTestId('sheet.filter.favorite').click();
  await page.getByTestId('sheet.filter.favorite').click();
  await page.keyboard.press('Escape');
  await expect(page.getByTestId(/^gallery\.tile\.\d+$/)).toHaveCount(1);
});
