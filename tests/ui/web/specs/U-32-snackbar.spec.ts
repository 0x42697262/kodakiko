import { expect, signInThroughUi, test } from '../../support/fixtures.ts';
import { seedAccount, seedEntries, type SeededAccount } from '../../support/seed.ts';
import { uiUsername, type UiProject } from '../../support/usernames.ts';

const SYNC_WAIT_MS = 60_000;

let account: SeededAccount;

test.beforeAll(async ({}, testInfo) => {
  test.skip(testInfo.project.name !== 'desktop', 'U-32 runs in the desktop project only.');
  account = await seedAccount({ username: uiUsername('U-32', testInfo.project.name as UiProject, 0) });
  await seedEntries(account, [
    { takenAtUtc: '2026-11-03T10:00:00.000Z', offsetMinutes: 0, note: 'snack', tags: [], media: ['photo-gps.jpg'] },
  ]);
});

test('U-32 trashing from the viewer shows a snackbar whose Undo brings the entry back', async ({ page }) => {
  await signInThroughUi(page, account);
  await expect(page.getByTestId('gallery.tile.0')).toBeVisible({ timeout: SYNC_WAIT_MS });
  await page.getByTestId('gallery.tile.0').click();
  await page.getByTestId('viewer.more').click();
  await page.getByTestId('viewer.trash').click();
  await expect(page.getByTestId('gallery.noticeTrashed')).toHaveText('Moved to trash.');
  await expect(page.getByTestId('gallery.tile.0')).toHaveCount(0);
  await page.getByTestId('gallery.noticeUndo').click();
  await expect(page.getByTestId('gallery.tile.0')).toBeVisible({ timeout: SYNC_WAIT_MS });
  await expect(page.getByTestId('gallery.noticeTrashed')).toHaveCount(0);
});
