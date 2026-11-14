import { expect, signInThroughUi, test } from '../../support/fixtures.ts';
import { seedAccount, seedEntries, type SeededAccount } from '../../support/seed.ts';
import { uiUsername, type UiProject } from '../../support/usernames.ts';

const SYNC_WAIT_MS = 60_000;

let account: SeededAccount;

test.beforeAll(async ({}, testInfo) => {
  test.skip(testInfo.project.name !== 'desktop', 'U-24 runs in the desktop project only.');
  account = await seedAccount({ username: uiUsername('U-24', testInfo.project.name as UiProject, 0) });
  await seedEntries(account, [
    { takenAtUtc: '2026-10-01T08:00:00.000Z', offsetMinutes: 0, note: 'photo', tags: [], media: ['photo-gps.jpg'] },
    { takenAtUtc: '2026-10-02T08:00:00.000Z', offsetMinutes: 0, note: 'clip', tags: [], media: ['video-20s.mp4'] },
  ]);
});

test('U-24 the Filter/Sort/Display sheet filters to videos, switches to list mode and keeps both after a reload', async ({ page }) => {
  await signInThroughUi(page, account);
  await expect(page.getByTestId(/^gallery\.tile\.\d+$/)).toHaveCount(2, { timeout: SYNC_WAIT_MS });
  await page.getByTestId('gallery.filter').click();
  await page.getByTestId('sheet.filter.video').click();
  await expect(page.getByTestId('sheet.filter.video')).toHaveAttribute('aria-checked', 'true');
  await page.getByTestId('sheet.tab.display').click();
  await page.getByTestId('settings.displayMode.list').click();
  await page.keyboard.press('Escape');
  await expect(page.getByTestId(/^gallery\.tile\.\d+$/)).toHaveCount(1);
  await expect(page.getByTestId('gallery.tile.0')).toContainText('clip');

  await page.getByTestId('tabs.more').click();
  await page.getByTestId('more.lockNow').click();
  await page.getByTestId('lock.password').fill(account.password);
  await page.getByTestId('lock.unlockPassword').click();
  await page.getByTestId('tabs.gallery').click();
  await expect(page.getByTestId(/^gallery\.tile\.\d+$/)).toHaveCount(1, { timeout: SYNC_WAIT_MS });
  await expect(page.getByTestId('gallery.tile.0')).toContainText('clip');
});
