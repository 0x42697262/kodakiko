import { expect, signInThroughUi, test } from '../../support/fixtures.ts';
import { seedAccount, seedEntries, type SeededAccount } from '../../support/seed.ts';
import { uiUsername, type UiProject } from '../../support/usernames.ts';

const SYNC_WAIT_MS = 60_000;

let account: SeededAccount;

test.beforeAll(async ({}, testInfo) => {
  account = await seedAccount({ username: uiUsername('U-25', testInfo.project.name as UiProject, 0) });
  await seedEntries(account, [
    { takenAtUtc: '2026-09-01T08:00:00.000Z', offsetMinutes: 0, note: '', tags: ['beach'], media: ['photo-gps.jpg'] },
    { takenAtUtc: '2026-09-02T08:00:00.000Z', offsetMinutes: 0, note: '', tags: ['city'], media: ['photo-portrait.jpg'] },
    { takenAtUtc: '2026-09-03T08:00:00.000Z', offsetMinutes: 0, note: '', tags: ['city'], media: ['photo-portrait.jpg'] },
  ]);
});

test('U-25 tag tabs show All plus each tag, and selecting a tab shows only its entries', async ({ page }) => {
  await signInThroughUi(page, account);
  await expect(page.getByTestId(/^gallery\.tile\.\d+$/)).toHaveCount(3, { timeout: SYNC_WAIT_MS });
  await expect(page.getByTestId('gallery.tagTab.all')).toHaveAttribute('aria-selected', 'true');
  await expect(page.getByTestId('gallery.tagTab.0')).toContainText('beach');
  await expect(page.getByTestId('gallery.tagTab.1')).toContainText('city');
  await page.getByTestId('gallery.tagTab.1').click();
  await expect(page.getByTestId(/^gallery\.tile\.\d+$/)).toHaveCount(2);
  await page.getByTestId('gallery.tagTab.all').click();
  await expect(page.getByTestId(/^gallery\.tile\.\d+$/)).toHaveCount(3);
});
