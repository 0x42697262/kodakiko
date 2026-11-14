import { expect, signInThroughUi, test } from '../../support/fixtures.ts';
import { seedAccount, seedEntries, type SeededAccount } from '../../support/seed.ts';
import { uiUsername, type UiProject } from '../../support/usernames.ts';

const SYNC_WAIT_MS = 60_000;

let account: SeededAccount;

test.beforeAll(async ({}, testInfo) => {
  test.skip(testInfo.project.name !== 'desktop', 'U-28 runs in the desktop project only.');
  account = await seedAccount({ username: uiUsername('U-28', testInfo.project.name as UiProject, 0) });
  await seedEntries(account, [
    { takenAtUtc: '2026-05-01T08:00:00.000Z', offsetMinutes: 0, note: '', tags: ['beach'], media: ['photo-gps.jpg'] },
    { takenAtUtc: '2026-05-02T08:00:00.000Z', offsetMinutes: 0, note: '', tags: ['beach', 'city'], media: ['photo-portrait.jpg'] },
  ]);
});

test('U-28 renaming a tag in the tags editor renames it on every entry and in the tabs', async ({ page }) => {
  await signInThroughUi(page, account);
  await expect(page.getByTestId(/^gallery\.tile\.\d+$/)).toHaveCount(2, { timeout: SYNC_WAIT_MS });
  await page.getByTestId('tabs.more').click();
  await page.getByTestId('more.tags').click();
  await expect(page.getByTestId('tags.row.0')).toContainText('beach');
  await expect(page.getByTestId('tags.row.0')).toContainText('2 entries');
  await page.getByTestId('tags.rename.0').click();
  await page.getByTestId('tags.nameField').fill('seaside');
  await page.getByTestId('tags.save').click();
  await expect(page.getByTestId('tags.row.0')).toContainText('seaside');
  await page.locator('[data-testid="settings.back"]:visible').click();
  await page.getByTestId('tabs.gallery').click();
  await expect(page.getByTestId('gallery.tagTab.0')).toContainText('seaside');
  await page.getByTestId('gallery.tagTab.0').click();
  await expect(page.getByTestId(/^gallery\.tile\.\d+$/)).toHaveCount(2);
});
