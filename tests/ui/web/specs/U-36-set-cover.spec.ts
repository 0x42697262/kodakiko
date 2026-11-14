import { expect, signInThroughUi, test } from '../../support/fixtures.ts';
import { seedAccount, seedEntries, type SeededAccount } from '../../support/seed.ts';
import { uiUsername, type UiProject } from '../../support/usernames.ts';

const SYNC_WAIT_MS = 60_000;

let account: SeededAccount;

test.beforeAll(async ({}, testInfo) => {
  test.skip(testInfo.project.name !== 'desktop', 'U-36 runs in the desktop project only.');
  account = await seedAccount({ username: uiUsername('U-36', testInfo.project.name as UiProject, 0) });
  await seedEntries(account, [
    { takenAtUtc: '2026-11-03T10:00:00.000Z', offsetMinutes: 0, note: 'pair', tags: [], media: ['photo-gps.jpg', 'video-20s.mp4'] },
  ]);
});

test('U-36 set as cover moves an item first, and removing asks first and keeps the last item', async ({ page }) => {
  await signInThroughUi(page, account);
  await expect(page.getByTestId('gallery.tile.0')).toBeVisible({ timeout: SYNC_WAIT_MS });
  await page.getByTestId('gallery.tile.0').click();
  await page.getByTestId('viewer.edit').click();
  const editor = page.getByTestId('edit.screen');
  await expect(editor.getByTestId('edit.dots')).toBeVisible();
  await editor.getByTestId('edit.mediaMenu.1').click();
  await page.getByTestId('edit.setCover').click();
  await editor.getByTestId('compose.back').click();
  await expect(page.getByTestId('viewer.play').first()).toBeInViewport();

  await page.getByTestId('viewer.edit').click();
  await page.getByTestId('edit.screen').getByTestId('edit.mediaMenu.1').click();
  await page.getByTestId('compose.removeItem').click();
  await page.getByTestId('edit.removeConfirm').click();
  await expect(page.getByTestId('edit.screen').getByTestId('edit.dots')).toHaveCount(0);
  await page.getByTestId('edit.screen').getByTestId('edit.mediaMenu.0').click();
  await page.getByTestId('compose.removeItem').click();
  await expect(page.getByTestId('edit.removeConfirm')).toHaveCount(0);
});
