import { expect, pasteFile, signInThroughUi, test } from '../../support/fixtures.ts';
import { seedAccount, seedEntries, type SeededAccount } from '../../support/seed.ts';
import { uiUsername, type UiProject } from '../../support/usernames.ts';

const SYNC_WAIT_MS = 60_000;
const SCRIM_X = 640;
const SCRIM_Y = 40;

let account: SeededAccount;

test.beforeAll(async ({}, testInfo) => {
  test.skip(testInfo.project.name !== 'desktop', 'U-39 runs in the desktop project only.');
  account = await seedAccount({ username: uiUsername('U-39', testInfo.project.name as UiProject, 0) });
  await seedEntries(account, [
    { takenAtUtc: '2026-11-03T10:00:00.000Z', offsetMinutes: 0, note: 'pair', tags: [], media: ['photo-gps.jpg', 'photo-portrait.jpg'] },
  ]);
});

test('U-39 the share picker starts with every item checked, and pasting a picture adds it to the entry', async ({ page }) => {
  await signInThroughUi(page, account);
  await expect(page.getByTestId('gallery.tile.0')).toBeVisible({ timeout: SYNC_WAIT_MS });
  await page.getByTestId('gallery.tile.0').click();
  await page.getByTestId('viewer.shareAction').click();
  await page.getByTestId('sheet.tab.apps').click();
  const picker = page.getByTestId('share.picker');
  await expect(picker).toBeVisible();
  await expect(page.getByTestId('share.item.0')).toHaveAttribute('aria-checked', 'true');
  await expect(page.getByTestId('share.item.1')).toHaveAttribute('aria-checked', 'true');
  await expect(page.getByTestId('share.send')).toContainText('Share 2 items');
  await page.getByTestId('share.item.1').click();
  await expect(page.getByTestId('share.item.1')).toHaveAttribute('aria-checked', 'false');
  await expect(page.getByTestId('share.send')).toContainText('Share 1 item');
  await expect(page.getByTestId('share.leavesEncryption')).toBeVisible();
  await page.mouse.click(SCRIM_X, SCRIM_Y);
  await expect(picker).toHaveCount(0);

  await page.getByTestId('viewer.edit').click();
  const editor = page.getByTestId('edit.screen');
  await expect(editor.getByTestId('edit.page.1')).toBeAttached();
  await pasteFile(page, 'screenshot.png');
  await expect(editor.getByTestId('edit.page.2')).toBeAttached({ timeout: SYNC_WAIT_MS });
  await expect(editor.getByTestId('edit.dot.2')).toBeVisible();
});
